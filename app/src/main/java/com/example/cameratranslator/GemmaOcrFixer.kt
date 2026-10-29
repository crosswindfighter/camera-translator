package com.example.cameratranslator

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * Gelişmiş OCR düzeltme: OCR'ın bozduğu İngilizce altyazıyı ("wejuSrask fim") çeviriden
 * önce isteğe bağlı Gemma 4 E2B modeliyle düzeltir ("we just ask him"). Çeviriyi yine
 * seçili motor (OPUS vb.) yapar; PC testinde Gemma'nın Türkçesi OPUS'tan zayıf çıktı,
 * İngilizce düzeltmesi ise iyiydi.
 *
 * Gecikmeyi düşük tutmak için:
 *  - Yalnızca sözlükte olmayan/bozuk görünen kelime içeren cümleler modele gider.
 *  - Model [TIMEOUT_MS] içinde cevap vermezse orijinal cümle kullanılır.
 *  - Modelin cevabı orijinalden çok farklıysa (uydurma riski) kullanılmaz.
 */
object GemmaOcrFixer {

    sealed class Status {
        object Off : Status()
        object Loading : Status()
        data class Ready(val backend: String) : Status()
        data class Failed(val message: String) : Status()
    }

    private const val TAG = "GemmaOcrFixer"
    private const val TIMEOUT_MS = 2500L
    private const val MAX_OUTPUT_TOKENS = 96
    private const val MAX_TOTAL_TOKENS = 1024
    private const val WORD_LIST_ASSET = "en_words.txt"

    // PC testinde (OCR loglarındaki gerçek cümleler) en iyi sonucu veren talimat ve örnekler
    private const val SYSTEM_PROMPT =
        "This English video game subtitle was read from a TV by OCR and may contain recognition errors " +
            "(wrong letters, merged or split words, digits instead of letters, rn read as m). " +
            "Rewrite it with the errors fixed. Every word must be a real English word or a name; fix words that are not. " +
            "Change as little as possible: keep slang, swearing, names and punctuation. " +
            "If it is already correct, return it unchanged. Output ONLY the corrected English subtitle.\n\n" +
            "Examples:\n" +
            "Wh0 the fuck are you speakirnıgj to? -> Who the fuck are you speaking to?\n" +
            "Gimme mne a hug yeah. -> Gimme a hug, yeah.\n" +
            "Thar is-irs part ofthe grieving process -> That is first part of the grieving process\n" +
            "We gota firnidl thye rest -> We gotta find the rest\n" +
            "Sounds ike he's living in LosSantos. -> Sounds like he's living in Los Santos."

    /** Sözlükte olmayan ama altyazılarda sık geçen ünlemler/argo. */
    private val EXTRA_WORDS = setOf(
        "oh", "ohh", "uh", "uhh", "um", "umm", "hmm", "ah", "ahh", "huh", "whoa", "wow", "ok", "okay",
        "nah", "yep", "yup", "yeah", "hey", "heh", "ha", "haha", "mm", "mhm", "shh", "ugh", "ow", "oops",
        "gonna", "gotta", "wanna", "gimme", "lemme", "dunno", "y'all", "ain", "c'mon", "homie", "bro", "dude", "yo", "ya", "cuz", "fam",
        // Kısaltma kökleri (didn't -> didn)
        "didn", "wasn", "isn", "doesn", "couldn", "wouldn", "shouldn", "hasn", "haven", "hadn", "weren", "aren", "won", "can"
    )
    private val SPACES = Regex("\\s+")
    private val MIXED_DIGIT = Regex("\\p{L}\\d|\\d\\p{L}")

    // LiteRT-LM motoru tek iş parçacığından kullanılır
    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val busy = AtomicBoolean(false)
    private var loadJob: Job? = null

    @Volatile private var engine: Engine? = null
    @Volatile private var activeConversation: Conversation? = null
    @Volatile private var words: Set<String> = emptySet()

    /** Ayarlardaki "Gelişmiş OCR düzeltme" anahtarı. */
    @Volatile var enabled: Boolean = false
        private set

    private val _status = MutableStateFlow<Status>(Status.Off)
    val status: StateFlow<Status> = _status

    fun setEnabled(context: Context, value: Boolean) {
        enabled = value
        if (value) load(context) else release()
    }

    /** Açıksa ve model yüklüyse motoru arka planda hazırlar (birkaç saniye sürebilir). */
    fun load(context: Context) {
        if (!enabled || engine != null || loadJob?.isActive == true) return
        if (!GemmaModelDownloader.isInstalled()) return
        val modelFile = GemmaModelDownloader.modelFile() ?: return
        val appContext = context.applicationContext

        _status.value = Status.Loading
        loadJob = scope.launch {
            if (words.isEmpty()) words = loadWords(appContext)
            val cacheDir = File(appContext.cacheDir, "gemma").apply { mkdirs() }.absolutePath
            val created = createEngine(modelFile, Backend.GPU(), cacheDir)?.let { it to "GPU" }
                ?: createEngine(modelFile, Backend.CPU(), cacheDir)?.let { it to "CPU" }
            when {
                created == null -> _status.value = Status.Failed("Model açılamadı")
                !enabled -> created.first.close() // yüklenirken kapatıldı
                else -> {
                    engine = created.first
                    _status.value = Status.Ready(created.second)
                    Log.d(TAG, "Gemma ready on ${created.second}")
                }
            }
        }
    }

    /** Motoru kapatır ve belleği boşaltır (anahtar kapatılınca / model silinmeden önce). */
    fun release() {
        loadJob?.cancel()
        activeConversation?.let { runCatching { it.cancelProcess() } }
        scope.launch {
            // Bu arada anahtar tekrar açıldıysa motoru kapatma
            if (enabled) return@launch
            engine?.let { runCatching { it.close() } }
            engine = null
            _status.value = Status.Off
        }
    }

    /**
     * Cümle bozuk görünüyorsa Gemma ile düzeltilmiş halini, değilse (veya model hazır
     * değilse, zaman aşımında, güvenilmez cevapta) olduğu gibi döndürür.
     */
    suspend fun fix(text: String): String {
        val currentEngine = engine ?: return text
        if (!enabled || !needsFix(text)) return text
        // Önceki düzeltme hâlâ sürüyorsa bu cümleyi bekletme
        if (!busy.compareAndSet(false, true)) return text

        val startedAt = SystemClock.elapsedRealtime()
        val request = scope.async {
            try {
                currentEngine.createConversation(conversationConfig()).use { conversation ->
                    activeConversation = conversation
                    conversation.sendMessage(Message.user(text)).contents.toString()
                }
            } finally {
                activeConversation = null
                busy.set(false)
            }
        }

        val raw = try {
            withTimeoutOrNull(TIMEOUT_MS) { request.await() }
        } catch (e: CancellationException) {
            throw e // yeni altyazı geldi, çeviri işi iptal edildi
        } catch (e: Exception) {
            Log.e(TAG, "Fix failed", e)
            return text
        }
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (raw == null) {
            activeConversation?.let { runCatching { it.cancelProcess() } }
            Log.w(TAG, "Timeout after ${elapsed}ms, using OCR text: \"$text\"")
            return text
        }

        val fixed = sanitize(raw)
        return if (isTrustworthy(text, fixed)) {
            Log.d(TAG, "Fixed in ${elapsed}ms: \"$text\" -> \"$fixed\"")
            fixed
        } else {
            Log.w(TAG, "Rejected (${elapsed}ms): \"$text\" -> \"$fixed\"")
            text
        }
    }

    private fun createEngine(modelFile: File, backend: Backend, cacheDir: String): Engine? {
        var created: Engine? = null
        return try {
            created = Engine(
                EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = backend,
                    maxNumTokens = MAX_TOTAL_TOKENS,
                    cacheDir = cacheDir
                )
            )
            created.initialize()
            created
        } catch (e: Throwable) {
            // UnsatisfiedLinkError dahil: GPU sürücüsü / yerel kütüphane yoksa CPU denenir
            Log.e(TAG, "Engine init failed on ${backend.name}", e)
            runCatching { created?.close() }
            null
        }
    }

    private fun conversationConfig() = ConversationConfig(
        systemInstruction = Contents.of(SYSTEM_PROMPT),
        // topK=1: her seferinde en olası cevap (PC testindeki temperature 0 ile aynı)
        samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 1.0),
        maxOutputToken = MAX_OUTPUT_TOKENS,
        thinkingConfig = ThinkingConfig(enableThinking = false)
    )

    private fun loadWords(context: Context): Set<String> = try {
        context.assets.open(WORD_LIST_ASSET).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
        }
    } catch (e: Exception) {
        Log.e(TAG, "Word list could not be loaded", e)
        emptySet()
    }

    /** Cümlede sözlükte olmayan ya da OCR bozukluğu gibi görünen kelime var mı? */
    internal fun needsFix(text: String): Boolean {
        var sentenceStart = true
        for (token in text.split(SPACES)) {
            val core = token.trim { !it.isLetterOrDigit() && it != '\'' }.trim('\'')
            if (core.isNotEmpty() && isSuspicious(core, sentenceStart)) return true
            if (token.isNotBlank()) {
                sentenceStart = token.trimEnd('"', '\'', ')').lastOrNull()?.let { it in ".!?" } == true
            }
        }
        return false
    }

    private fun isSuspicious(word: String, sentenceStart: Boolean): Boolean {
        if (MIXED_DIGIT.containsMatchIn(word)) return true // b9, cowD9y
        if (word.all { it.isDigit() }) return false
        // Kısaltmalar: don't -> don, I've -> I, cousin's -> cousin
        val stem = word.substringBefore('\'')
        if (stem.isEmpty()) return false
        if (!stem.all { it.isLetter() }) return true
        if (stem.length == 1) return stem.lowercase() !in setOf("a", "i", "o")

        val isCapitalized = stem[0].isUpperCase() && stem.drop(1).all { it.isLowerCase() }
        // Cümle ortasında büyük harfle başlayan kelime büyük ihtimalle isim (Townley, Los Santos)
        if (isCapitalized && !sentenceStart) return false
        // Kelime içinde rastgele büyük harf: "wejuSrask", "MIchaelJOwnley" (FBI, LSPD gibi tamamı büyük olanlar hariç)
        if (stem.drop(1).any { it.isUpperCase() } && !stem.all { it.isUpperCase() }) return true
        if (stem.all { it.isUpperCase() }) return false

        return !isKnown(stem.lowercase())
    }

    private fun isKnown(word: String): Boolean {
        if (words.isEmpty()) return true // sözlük yüklenemediyse modeli boşuna çağırma
        if (word in words || word in EXTRA_WORDS) return true
        if (word.endsWith("s") && word.dropLast(1) in words) return true
        if (word.endsWith("in") && (word + "g") in words) return true // messin', fuckin
        return false
    }

    /** Modelin cevabından yalnızca düzeltilmiş cümleyi alır. */
    private fun sanitize(raw: String): String {
        var line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
        if ("->" in line) line = line.substringAfterLast("->").trim()
        return line.trim('"', '“', '”', '`').trim()
    }

    /** Uydurma koruması: cevap orijinal OCR metnine yeterince benzemeli. */
    private fun isTrustworthy(original: String, fixed: String): Boolean {
        if (fixed.isBlank()) return false
        val a = original.lowercase()
        val b = fixed.lowercase()
        val lengthRatio = b.length.toFloat() / a.length.coerceAtLeast(1)
        if (lengthRatio < 0.6f || lengthRatio > 1.6f) return false
        val similarity = 1f - levenshtein(a, b).toFloat() / max(a.length, b.length)
        return similarity >= 0.55f
    }

    private fun levenshtein(left: String, right: String): Int {
        val prev = IntArray(right.length + 1) { it }
        val curr = IntArray(right.length + 1)
        for (i in 1..left.length) {
            curr[0] = i
            for (j in 1..right.length) {
                val cost = if (left[i - 1] == right[j - 1]) 0 else 1
                curr[j] = min(min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            prev.indices.forEach { prev[it] = curr[it] }
        }
        return prev[right.length]
    }
}
