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

    /**
     * Google modlarında da uygulanan dar kurallar: Google'ın da yanlış çevirdiği görülenler
     * ("tricked out, dog" -> "kandırılmış, köpek", "nigga" -> "zenci"). Hitaplar yalnızca virgülden
     * sonra ve öbek sonunda eşleşir; başka cümlelere dokunmaz. Google'ın zaten doğru çevirdiği
     * kalıplar (gonna, no shit, strapped…) burada bilerek yok.
     */
    private val SHARED_RULES: List<Pair<Regex, String>> = listOf(
        // Hitaplar (köpek, kuzen, zenci gibi düz çevrilmesin)
        "(?<=,\\s)(dog|dawg)$VOCATIVE_END" to "dude",
        "(?<=,\\s)(homes|holmes)$VOCATIVE_END" to "buddy",
        "(?<=,\\s)ese$VOCATIVE_END|^ese(?=,)" to "man",
        "(?<=,\\s)(cuz|fam|bruh|brah)$VOCATIVE_END" to "dude",
        "\\bmy (nigga|nigguh|niggah)\\b" to "my brother",
        "\\b(nigga|nigguh|niggah)s\\b" to "guys",
        "\\b(nigga|nigguh|niggah)\\b" to "man",
        "\\btricked[- ]out\\b" to "modified",
        // "Mr. Revolutionary" -> "Bay. Devrimci" (nokta cümleyi bölüyordu)
        "\\bMrs\\.(?=\\s+[A-Z])" to "Mrs",
        "\\bMr\\.(?=\\s+[A-Z])" to "Mr",
        "\\bDr\\.(?=\\s+[A-Z])" to "Dr",
        // "Bunu anladım" çıkıyordu; "I got this covered" gibi devamı olanlara dokunmaz
        "\\bI got this(?=[,.!?]*$)" to "I can handle this",
        // "Çatlayan ne" çıkıyordu
        "\\bwhat's crack(?:ing|in)\\b'?" to "what's up",
        // Sık OCR hataları
        "'ern\\b" to "'em",
        "\\byou'l\\b" to "you'll",
        "\\bthey'l\\b" to "they'll",
        "\\bwe'l\\b" to "we'll",
        "\\baheads up\\b" to "a heads up",
        // Kesmesi düşmüş "we'll": "Well talk about that later" ("as well be" hariç)
        "(?<!\\bas )\\bwell(?= (talk|see|be|have|get|go|need|do|make|figure|find|meet|handle|take)\\b)" to "we'll",
        "(?<=\\b(?:we|you|they|I|he|she) )gpt\\b" to "got",
        "\\bsiting(?= (there|here|around)\\b)" to "sitting",
        "\\bthere'[Il]\\b" to "there'll",
        "\\bthat'[Il]\\b" to "that'll",
        "\\bit'[Il]\\b" to "it'll",
        "\\bshe'[Il]\\b" to "she'll",
        "\\bhe'[Il]\\b" to "he'll",
        // "T poured my heart" -> I poured (cümle başında ya da "and"den sonra); "T, you got" ve "Uncle T" etkilenmez
        "(?:^|(?<=[.!?]\\s)|(?<=\\band\\s))(?-i:T)(?= [a-z])(?! (?:shirt|shirts|minus)\\b)" to "I",
        // "Were taking a chopper" -> We're ("Were you" gibi gerçek kullanımlar etkilenmez)
        "(?:^|(?<=[.!?]\\s))(?-i:Were)(?= (?!being\\b)[a-z]+ing\\b)" to "We're",
        // "a team df agents" -> of ("my bf" etkilenmez)
        "(?<!\\b(?:my|your|her|his|the|a) )\\b[bd]f(?= [a-z])" to "of",
        // "I shouldn'ta asked" -> "Sormam gerekirdi" (ters anlam) çıkıyordu
        "\\bshouldn'?ta\\b" to "shouldn't have",
        "\\bwouldn'?ta\\b" to "wouldn't have",
        "\\bcouldn'?ta\\b" to "couldn't have",
        "\\bmy nig\\b" to "my brother",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    /** Yalnızca OPUS için (Google bunları zaten doğru çeviriyor). */
    private val RULES: List<Pair<Regex, String>> = listOf(
        "\\bmy g\\b" to "my man",

        // Kalıplar
        "\\band shit\\b" to "and stuff",
        "\\bno shit\\b" to "no kidding",
        "\\bno cap\\b" to "no lie",
        "\\bfive-?o\\b|\\b5-0\\b" to "the cops",
        "\\brepo\\b" to "repossess",
        // "You're strapped in" (kemer/emniyet) "Silahlısın" oluyordu: in/down/into ile gelince dokunma
        "\\bstrapped\\b(?!\\s+(in|down|into|to)\\b)" to "armed",
        "\\blet's bounce\\b" to "let's go",
        "\\bgat\\b" to "gun",
        "\\bmy whip\\b" to "my car",
        "\\bnice ride\\b" to "nice car",
        "\\bthe hood\\b" to "the neighborhood",
        "\\blet's bail\\b" to "let's leave",
        // "ain't gonna be cool" OPUS'ta ters anlama dönüyordu ("hiç sorun olmaz")
        "\\b(ain't|aint|isn't|is not|not) (gonna|going to) be cool\\b" to "won't be happy",
        "\\bwon't be cool\\b" to "won't be happy",
        // GTA görev metni: "Lose the Cops." -> "Polisleri bırakın" çıkıyordu
        "\\blose the (cops|police)\\b" to "escape the cops",

        // Konuşma dili kısaltmaları
        "\\bgonna\\b" to "going to",
        "\\bwanna\\b" to "want to",
        // "it's gotta be" = "it's got to be" ("it's have to be" çıkıyordu)
        "(?<=\\b(?:it|he|she|that|there)'s\\s)gotta\\b" to "got to",
        "\\bgotta\\b" to "have to",
        "\\blemme\\b" to "let me",
        "\\bgimme\\b" to "give me",
        "\\bdunno\\b" to "don't know",
        "\\by'all\\b" to "you all",
        "\\bain't got\\b" to "don't have",
        "\\bokey[- ]doke(y)?\\b" to "okay",
        // "Seni tutan ne?" çıkıyordu
        "\\bwhat kept (?:ya|you)\\b" to "what took you so long",
        // "casing the joint" -> "esrarı kovalamıyorduk" çıkıyordu
        "\\bcasing the (joint|place)\\b" to "scouting the place",
        "\\bcased the (joint|place)\\b" to "scouted the place",
        "\\bcase the (joint|place)\\b" to "scout the place",
        // "called shotgun" -> "pompalı tüfek çağırdı" çıkıyordu
        "\\bcalled shotgun\\b" to "claimed the front seat",
        "\\bcall shotgun\\b" to "claim the front seat",
        // Telsizde "Roger" = anlaşıldı ("Roger November…" -> "Roger … tarafından yazıldı" çıkıyordu)
        "\\broger that\\b" to "understood",
        "^Roger(?=,?\\s+(November|Niner|Alpha|Bravo|Charlie|Delta|Echo|Foxtrot|Tango|Victor|Whiskey|Zulu)\\b|[.!]?$)" to "Understood",

        // Oyun logundaki hatalar ("homies" -> "homolar", "rapel" -> "tecavüz" gibi). Bu grup OPUS modeliyle
        // önce/sonra test edilmedi; modelin bildiği sade kelimelere çevirir.
        "\\bhomies\\b" to "buddies",
        "\\bstoolies\\b" to "snitches",
        "\\bstoolie\\b" to "snitch",
        "\\brap+elling\\b" to "climbing down",
        "\\brap+el\\b" to "climb down",
        "\\bget crackin[g']?" to "get moving",
        "\\bhit me up\\b" to "call me",
        "\\bblow this (?:joint|place)\\b" to "get out of here",
        "\\bconversate\\b" to "talk",
        "\\bjudgemental\\b" to "judgmental",
        "\\bfire in the hole\\b" to "take cover",
        "\\bfinna\\b|\\bfin'? to\\b" to "going to",
        "\\byo'?self\\b" to "yourself",
        "\\byo'(?=\\s)" to "your",
        // Argoda clap = vurmak ("beni alkışlamak için" çıkıyordu); "clap your hands" etkilenmez
        "\\bclap(?= (?:me|him|her|them|us|your|some|that|this)\\b)(?! (?:your|them|those|some) hands\\b)" to "shoot",
        "\\bclapping(?= (?:me|him|her|them|us|your|some|that|this)\\b)(?! (?:your|them|those|some) hands\\b)" to "shooting",
        "(?<=\\bto )clap(?=[,.!?]*$)" to "shoot",
        "(?<=\\b(?:got|get|getting|wasn't|was|be) )clapped\\b" to "shot",
    ).map { (pattern, replacement) -> Regex(pattern, RegexOption.IGNORE_CASE) to replacement }

    /** Kekeleme: "p-p-pretend", "A-A-And", "M-M-M-Mrs." -> pretend, And, Mrs. (OPUS döngüye giriyordu) */
    private val STUTTER = Regex("\\b([A-Za-z])-(?:\\1-)*(?=\\1)", RegexOption.IGNORE_CASE)

    /** Google modları için: yalnızca ortak dar kurallar. */
    fun normalizeShared(text: String): String {
        var result = text
        for ((regex, replacement) in SHARED_RULES) {
            result = regex.replace(result) { match -> keepCase(match.value, replacement) }
        }
        return result
    }

    fun normalize(text: String): String {
        var result = normalizeShared(STUTTER.replace(text, ""))
        for ((regex, replacement) in RULES) {
            result = regex.replace(result) { match -> keepCase(match.value, replacement) }
        }
        return result
    }

    /** "Dog" -> "Dude", "dog" -> "dude": cümle başı büyük harf korunsun. */
    private fun keepCase(original: String, replacement: String): String =
        if (original.firstOrNull()?.isUpperCase() == true) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}

/**
 * Google'ın bağlamsız kısa cümlelerde yaptığı Türkçe hataları (testlerde tekrar eden, yalnızca kesin olanlar):
 * "Okay" -> "Tamam aşkım", "No." -> "HAYIR.", "Wait." -> "Beklemek." Komut düzeltmeleri yalnızca İngilizcede de o komut
 * tek başına bir cümleyse ve Türkçede de tek başına çıktıysa uygulanır.
 */
object GoogleTurkishFixer {
    private val OKAY = Regex("\\bTamam aşkım\\b")
    private val NO_CAPS = Regex("(?<!\\p{L})HAYIR(?!\\p{L})")
    private val COMMANDS = listOf(
        Triple("wait", "Beklemek", "Bekle"),
        Triple("stay", "Kalmak", "Kal"),
        Triple("clear", "Temizlemek", "Temiz"),
        Triple("move", "Taşınmak", "Yürü"),
        Triple("move it", "Hareket ettir", "Yürü"),
        Triple("steady", "Sabit durmak", "Sakin ol"),
        Triple("easy does it", "Kolay mı", "Yavaş ol"),
        Triple("easy now", "Şimdi kolay", "Yavaş ol")
    ).map { (english, wrong, right) ->
        Triple(
            // Ardından noktalama + yeni cümle ya da metin sonu gelmeli: "Wait for me" eşleşmez
            Regex("(?:^|[.!?]\\s+)$english(?:[.!?]+(?:\\s|$)|$)", RegexOption.IGNORE_CASE),
            Regex("(?:^|(?<=[.!?]\\s))$wrong(?=[.!?]|$)"),
            right
        )
    }

    fun fix(source: String, translated: String): String {
        var result = NO_CAPS.replace(OKAY.replace(translated, "Tamam"), "Hayır")
        for ((english, wrong, right) in COMMANDS) {
            if (english.containsMatchIn(source)) result = wrong.replace(result, right)
        }
        return result
    }
}
