package com.example.cameratranslator

data class ElevenLabsVoiceInfo(
    val id: String,
    val name: String,
    val description: String,
    val gender: VoiceGender
)

object ElevenLabsVoiceRegistry {
    val voices = listOf(
        // Female Voice Actors (Bella as primary verified free voice)
        ElevenLabsVoiceInfo("EXAVITQu4vr4xnSDxMaL", "Bella / Sarah", "Sıcak & Canlı Oyun Sesi", VoiceGender.FEMALE),
        ElevenLabsVoiceInfo("21m00Tcm4TlvDq8ikWAM", "Rachel", "Duygulu & Doğal Anlatıcı", VoiceGender.FEMALE),
        ElevenLabsVoiceInfo("AZnzlk1XvdvUeBnXmlld", "Domi", "Güçlü & Karakteristik", VoiceGender.FEMALE),
        ElevenLabsVoiceInfo("MF3mGyEYCl7XYWbV9V6O", "Elli", "Genç & Akıcı", VoiceGender.FEMALE),
        
        // Male Voice Actors (Adam & Antoni as primary verified free voices)
        ElevenLabsVoiceInfo("pNInz6obpgDQGcFmaJgB", "Adam", "Derin & Güçlü Oyun Aktörü", VoiceGender.MALE),
        ElevenLabsVoiceInfo("ErXwobaYiN019PkySvjV", "Antoni", "Tok & Karizmatik Oyun Aktörü", VoiceGender.MALE),
        ElevenLabsVoiceInfo("TxGEQNHWrfWFTfGW9XjX", "Josh", "Derin & Dramatik Karakter Sesi", VoiceGender.MALE),
        ElevenLabsVoiceInfo("VR6AewLTigWG4xSOukaG", "Arnold", "Sert & Epik Savaşçı Sesi", VoiceGender.MALE),
        ElevenLabsVoiceInfo("JBFqnCBsd6RMkjVDRZzb", "George", "Sıcak & Karizmatik", VoiceGender.MALE)
    )

    fun getVoiceById(id: String): ElevenLabsVoiceInfo {
        return voices.firstOrNull { it.id == id } ?: voices.first()
    }

    fun getVoicesByGender(gender: VoiceGender): List<ElevenLabsVoiceInfo> {
        return voices.filter { it.gender == gender }
    }
}
