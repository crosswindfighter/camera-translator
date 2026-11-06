package com.example.cameratranslator

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Kamera modunda okumalar üst üste bozulursa odağın kaçtığını varsayar. Ekstra karartmada periyodik netleme
 * kapalı olduğundan (karanlıkta odak "netlendi" deyip yanlış yere netleyebiliyor) mercek sen dokunana kadar
 * bulanık kalıyordu (testte ~80 sn). Eşik gerçek loglarla seçildi: son 6 satırın 4'ü bozuksa.
 */
object OcrBlurWatch {
    private const val WINDOW = 6
    private const val MIN_BAD = 4
    private const val MIN_WORDS = 2

    private val SUFFIX = Regex("('s|n't|'re|'ll|'ve|'d|'m|in')$")
    private val WORD = Regex("[A-Za-z']{2,}")
    private val recent = ArrayDeque<Boolean>()

    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val refocusRequests: SharedFlow<Unit> = requests

    /** Onaylanan her kamera okumasıyla çağrılır. */
    @Synchronized
    fun onConfirmed(text: String) {
        if (!OcrTextFixer.isDictionaryLoaded) return
        val words = WORD.findAll(text).map { it.value }.toList()
        if (words.size < MIN_WORDS) return
        val unknown = words.count { word ->
            val lower = word.lowercase()
            !OcrTextFixer.isDictionaryWord(lower.trim('\'')) && !OcrTextFixer.isDictionaryWord(SUFFIX.replace(lower, ""))
        }
        recent.addLast(unknown * 3 >= words.size)
        if (recent.size > WINDOW) recent.removeFirst()
        if (recent.size == WINDOW && recent.count { it } >= MIN_BAD) {
            recent.clear()
            requests.tryEmit(Unit)
        }
    }
}
