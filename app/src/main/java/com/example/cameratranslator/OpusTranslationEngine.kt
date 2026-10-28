package com.example.cameratranslator

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer

/**
 * Offline+ çeviri motoru: Helsinki-NLP OPUS-MT (opus-mt-tc-big-en-tr), ONNX int8.
 * Tamamen telefonda çalışır; internet, API anahtarı veya kota gerekmez.
 *
 * Model dosyaları (bilgisayarda hazırlanıp telefona kopyalanır):
 *   Android/data/com.example.cameratranslator/files/opus_en_tr/
 *     encoder_model.onnx, decoder_model_merged.onnx, source_pieces.tsv, vocab.json
 *
 * Sadece İngilizce -> Türkçe. Ters yön için CompositeTranslationEngine ML Kit'e düşer.
 */
class OpusTranslationEngine(context: Context) : ITranslationEngine {

    override var isTrToEn: Boolean = false
    override var mode: TranslationMode = TranslationMode.OFFLINE_PLUS
    private val _status = MutableStateFlow<TranslationStatus>(TranslationStatus.Idle)
    override val status: StateFlow<TranslationStatus> = _status
    private val _isBusy = MutableStateFlow(false)
    override val isBusy: StateFlow<Boolean> = _isBusy

    val modelDir: File? = context.getExternalFilesDir(MODEL_DIR_NAME)

    private val loadMutex = Mutex()
    private val runMutex = Mutex()
    private var env: OrtEnvironment? = null
    private var encoder: OrtSession? = null
    private var decoder: OrtSession? = null
    private var tokenizer: MarianTokenizer? = null

    val isReady: Boolean get() = _status.value == TranslationStatus.Ready

    override suspend fun prepare() {
        withContext(Dispatchers.IO) { loadModel() }
    }

    private suspend fun loadModel() {
        loadMutex.withLock {
            if (encoder != null) return@withLock
            val dir = modelDir
            val missing = REQUIRED_FILES.filter { dir == null || !File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                Log.w(TAG, "Model files missing in $dir: $missing")
                _status.value = TranslationStatus.Error("Offline+ modeli bulunamadı")
                return@withLock
            }
            _status.value = TranslationStatus.DownloadingModel
            try {
                val startedAt = SystemClock.elapsedRealtime()
                val environment = OrtEnvironment.getEnvironment()
                val options = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(NUM_THREADS)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                tokenizer = MarianTokenizer.load(File(dir, "source_pieces.tsv"), File(dir, "vocab.json"))
                encoder = environment.createSession(File(dir, "encoder_model.onnx").path, options)
                decoder = environment.createSession(File(dir, "decoder_model_merged.onnx").path, options)
                env = environment
                _status.value = TranslationStatus.Ready
                Log.d(TAG, "Loaded in ${SystemClock.elapsedRealtime() - startedAt} ms")
            } catch (e: Exception) {
                Log.e(TAG, "Model load failed", e)
                releaseSessions()
                _status.value = TranslationStatus.Error("Offline+ modeli yüklenemedi")
            }
        }
    }

    override suspend fun translate(text: String): String = withContext(Dispatchers.Default) {
        if (text.isBlank() || isTrToEn || !isReady) return@withContext ""
        runMutex.withLock {
            _isBusy.value = true
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // Cümle cümle çevirmek, modelin çok cümleli altyazılarda ilk cümleyi
                // atlamasını önlüyor (testte "Siktir, kanka." kayboluyordu).
                val result = SENTENCE_SPLIT.split(text.trim())
                    .filter { it.isNotBlank() }
                    .joinToString(" ") { translateSentence(it) }
                    .trim()
                Log.d(TAG, "Translated in ${SystemClock.elapsedRealtime() - startedAt} ms")
                result
            } catch (e: Exception) {
                Log.e(TAG, "Translate error", e)
                ""
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Açgözlü (greedy) çözümleme, KV önbellekli birleşik decoder ile. */
    private fun translateSentence(sentence: String): String {
        val environment = env ?: return ""
        val enc = encoder ?: return ""
        val dec = decoder ?: return ""
        val tok = tokenizer ?: return ""

        val inputIds = tok.encode(sentence)
        val inputShape = longArrayOf(1, inputIds.size.toLong())
        val outputNames = dec.outputNames.toList()

        val closeables = mutableListOf<AutoCloseable>()
        try {
            val idsTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(inputIds), inputShape)
            val maskTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(inputIds.size) { 1L }), inputShape)
            closeables += idsTensor
            closeables += maskTensor

            val encoderResult = enc.run(mapOf("input_ids" to idsTensor, "attention_mask" to maskTensor))
            closeables += encoderResult
            val hidden = encoderResult.get(0) as OnnxTensor

            // İlk adımda geçmiş yok: sıfır uzunluklu tensörler (use_cache_branch=false iken kullanılmaz)
            val past = HashMap<String, OnnxTensor>()
            for (layer in 0 until DECODER_LAYERS) for (kind in KV_KINDS) for (part in KV_PARTS) {
                val empty = OnnxTensor.createTensor(environment, FloatBuffer.allocate(0), longArrayOf(1, HEADS.toLong(), 0, HEAD_DIM.toLong()))
                closeables += empty
                past["past_key_values.$layer.$kind.$part"] = empty
            }

            val output = ArrayList<Int>()
            var token = PAD_ID
            var previous: OrtSession.Result? = null
            for (step in 0 until MAX_NEW_TOKENS) {
                val tokenTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(longArrayOf(token.toLong())), longArrayOf(1, 1))
                val cacheTensor = OnnxTensor.createTensor(environment, booleanArrayOf(step > 0))
                val feeds = HashMap<String, OnnxTensor>(past)
                feeds["encoder_attention_mask"] = maskTensor
                feeds["input_ids"] = tokenTensor
                feeds["encoder_hidden_states"] = hidden
                feeds["use_cache_branch"] = cacheTensor

                val result = dec.run(feeds)
                tokenTensor.close()
                cacheTensor.close()

                token = argmax((result.get(0) as OnnxTensor).floatBuffer)

                // Önbellek kolu encoder K/V'yi geri vermiyor (sahte şekil döner):
                // encoder K/V ilk adımdan kalır, her adımda sadece decoder K/V güncellenir.
                for (i in 1 until outputNames.size) {
                    val name = outputNames[i]
                    if (step == 0 || ".decoder." in name) {
                        past[name.replace("present", "past_key_values")] = result.get(i) as OnnxTensor
                    }
                }
                if (step == 0) {
                    closeables += result // encoder K/V bu sonuçta, cümle bitene kadar açık kalmalı
                } else {
                    previous?.close()
                    previous = result
                }

                if (token == EOS_ID) break
                output += token
            }
            previous?.close()
            return tok.decode(output)
        } finally {
            closeables.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun argmax(logits: FloatBuffer): Int {
        var best = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (i in 0 until logits.remaining()) {
            if (i == PAD_ID) continue // HF generation_config: bad_words_ids = [[pad]]
            val v = logits.get(i)
            if (v > bestValue) {
                bestValue = v
                best = i
            }
        }
        return best
    }

    private fun releaseSessions() {
        runCatching { encoder?.close() }
        runCatching { decoder?.close() }
        encoder = null
        decoder = null
        tokenizer = null
    }

    override fun close() {
        releaseSessions()
        _status.value = TranslationStatus.Idle
    }

    companion object {
        private const val TAG = "OpusEngine"
        const val MODEL_DIR_NAME = "opus_en_tr"
        private val REQUIRED_FILES = listOf("encoder_model.onnx", "decoder_model_merged.onnx", "source_pieces.tsv", "vocab.json")

        private const val NUM_THREADS = 4
        private const val MAX_NEW_TOKENS = 128
        private const val DECODER_LAYERS = 6
        private const val HEADS = 16
        private const val HEAD_DIM = 64
        private val KV_KINDS = listOf("decoder", "encoder")
        private val KV_PARTS = listOf("key", "value")

        private const val PAD_ID = 57059
        private const val EOS_ID = 43741

        private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")
    }
}

/**
 * MarianTokenizer'ın Kotlin karşılığı (SentencePiece unigram + ortak sözlük).
 * Bilgisayarda resmi tokenizer ile birebir aynı sonucu verdiği doğrulandı.
 */
private class MarianTokenizer(
    private val pieceScores: HashMap<String, Float>,
    private val pieceToId: HashMap<String, Int>,
    private val idToPiece: Array<String?>
) {
    private val maxPieceLength = pieceScores.keys.maxOf { it.length }
    private val unknownScore = pieceScores.values.min() - 10f

    fun encode(text: String): LongArray {
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
            .trim()
            .split(WHITESPACE)
            .joinToString(SPACE_MARK)
        val s = SPACE_MARK + normalized
        val n = s.length

        // Viterbi: en yüksek toplam skorlu parçalama
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val backStart = IntArray(n + 1)
        val backPiece = arrayOfNulls<String>(n + 1)
        best[0] = 0f
        for (i in 0 until n) {
            if (best[i] == Float.NEGATIVE_INFINITY) continue
            val limit = minOf(maxPieceLength, n - i)
            for (len in 1..limit) {
                val piece = s.substring(i, i + len)
                val score = pieceScores[piece] ?: continue
                if (best[i] + score > best[i + len]) {
                    best[i + len] = best[i] + score
                    backStart[i + len] = i
                    backPiece[i + len] = piece
                }
            }
            if (best[i + 1] == Float.NEGATIVE_INFINITY) {
                // Kaynak sözlükte olmayan karakter olduğu gibi kalır, ortak sözlükte aranır
                best[i + 1] = best[i] + unknownScore
                backStart[i + 1] = i
                backPiece[i + 1] = s.substring(i, i + 1)
            }
        }

        val ids = ArrayList<Long>()
        var j = n
        while (j > 0) {
            ids += (backPiece[j]?.let { pieceToId[it] } ?: UNK_ID).toLong()
            j = backStart[j]
        }
        ids.reverse()
        ids += EOS_ID.toLong()
        return ids.toLongArray()
    }

    fun decode(ids: List<Int>): String = buildString {
        for (id in ids) {
            if (id == EOS_ID || id == PAD_ID) continue
            append(idToPiece.getOrNull(id) ?: continue)
        }
    }.replace(SPACE_MARK, " ").trim()

    companion object {
        private const val SPACE_MARK = "▁"
        private val WHITESPACE = Regex("\\s+")
        private const val UNK_ID = 52508
        private const val EOS_ID = 43741
        private const val PAD_ID = 57059

        fun load(piecesFile: File, vocabFile: File): MarianTokenizer {
            val scores = HashMap<String, Float>(40_000)
            piecesFile.forEachLine(Charsets.UTF_8) { line ->
                val tab = line.lastIndexOf('\t')
                if (tab > 0) scores[line.substring(0, tab)] = line.substring(tab + 1).toFloat()
            }
            val json = JSONObject(vocabFile.readText(Charsets.UTF_8))
            val toId = HashMap<String, Int>(json.length() * 2)
            var maxId = 0
            for (key in json.keys()) {
                val id = json.getInt(key)
                toId[key] = id
                if (id > maxId) maxId = id
            }
            val toPiece = arrayOfNulls<String>(maxId + 1)
            for ((piece, id) in toId) toPiece[id] = piece
            return MarianTokenizer(scores, toId, toPiece)
        }
    }
}
