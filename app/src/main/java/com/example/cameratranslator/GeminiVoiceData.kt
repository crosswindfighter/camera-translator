package com.example.cameratranslator

data class GeminiVoiceInfo(
    val name: String,
    val description: String,
    val gender: VoiceGender
)

object GeminiVoiceRegistry {
    val voices = listOf(
        // Male Voices
        GeminiVoiceInfo("Puck", "Canlı & Enerjik Erkek Karakter Sesi", VoiceGender.MALE),
        GeminiVoiceInfo("Charon", "Derin & Dramatik Erkek Karakter Sesi", VoiceGender.MALE),
        GeminiVoiceInfo("Fenrir", "Güçlü & Tok Savaşçı Sesi", VoiceGender.MALE),
        
        // Female Voices
        GeminiVoiceInfo("Kore", "Sıcak & Doğal Kadın Karakter Sesi", VoiceGender.FEMALE),
        GeminiVoiceInfo("Aoede", "Akıcı & Berrak Anlatıcı Sesi", VoiceGender.FEMALE)
    )

    fun getVoiceByName(name: String): GeminiVoiceInfo {
        return voices.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: voices.first()
    }

    fun getVoicesByGender(gender: VoiceGender): List<GeminiVoiceInfo> {
        return voices.filter { it.gender == gender }
    }
}
