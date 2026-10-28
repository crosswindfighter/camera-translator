package com.example.cameratranslator

/**
 * OPUS'un bilmediği oyun/sokak argosunu, çeviriden önce modelin anladığı sade
 * İngilizceye çevirir. Örn. "…tricked out, dog." -> "…modified, dude." ->
 * "…modifiye edilmiş, dostum." (önce "…kandırılmalı, köpek." çıkıyordu).
 *
 * Her kural bilgisayarda OPUS modeliyle önce/sonra test edilerek seçildi; OPUS'un
 * zaten doğru çevirdikleri (homie -> kanka, "Twenty Gs" -> yirmi bin dolar,
 * "holy shit" -> vay anasını, "my bad", "snitch") bilerek listede yok.
 * Hitap kuralları yalnızca virgülden sonra ve cümle/öbek sonunda eşleşir:
 * "The dog barked" gibi gerçek anlamlar etkilenmez.
 *
 * Sadece OPUS (İngilizce -> Türkçe) için kullanılır.
 */
object SlangNormalizer {

    /** Hitap sonu: ardından noktalama ya da metin sonu gelmeli. */
    private const val VOCATIVE_END = "(?=\\s*[.!?,]|\\s*$)"

    private val RULES: List<Pair<Regex, String>> = listOf(
        // Hitaplar (köpek, kuzen, zenci gibi düz çevrilmesin)
        "(?<=,\\s)(dog|dawg)$VOCATIVE_END" to "dude",
        "(?<=,\\s)(homes|holmes)$VOCATIVE_END" to "buddy",
        "(?<=,\\s)ese$VOCATIVE_END|^ese(?=,)" to "man",
        "(?<=,\\s)(cuz|fam|bruh|brah)$VOCATIVE_END" to "dude",
        "\\bmy (nigga|nigguh|niggah)\\b" to "my brother",
        "\\b(nigga|nigguh|niggah)s\\b" to "guys",
        "\\b(nigga|nigguh|niggah)\\b" to "man",
        "\\bmy g\\b" to "my man",

        // Kalıplar
        "\\band shit\\b" to "and stuff",
        "\\bno shit\\b" to "no kidding",
        "\\bno cap\\b" to "no lie",
        "\\bfive-?o\\b|\\b5-0\\b" to "the cops",
        "\\btricked[- ]out\\b" to "modified",
        "\\brepo\\b" to "repossess",
        "\\bstrapped\\b" to "armed",
        "\\bgat\\b" to "gun",
        "\\bmy whip\\b" to "my car",
        "\\bnice ride\\b" to "nice car",
        "\\bthe hood\\b" to "the neighborhood",
        "\\blet's bail\\b" to "let's leave",

        // Konuşma dili kısaltmaları
        "\\bgonna\\b" to "going to",
        "\\bwanna\\b" to "want to",
        "\\bgotta\\b" to "have to",
        "\\blemme\\b" to "let me",
        "\\bgimme\\b" to "give me",
        "\\bdunno\\b" to "don't know",
        "\\by'all\\b" to "you all",
        "\\bain't got\\b" to "don't have",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    fun normalize(text: String): String {
        var result = text
        for ((regex, replacement) in RULES) {
            result = regex.replace(result) { match -> keepCase(match.value, replacement) }
        }
        return result
    }

    /** "Dog" -> "Dude", "dog" -> "dude": cümle başı büyük harf korunsun. */
    private fun keepCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}
