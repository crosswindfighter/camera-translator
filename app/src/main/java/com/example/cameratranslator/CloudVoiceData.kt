package com.example.cameratranslator

data class CloudVoiceInfo(
    val id: String,
    val family: String,      // "Chirp 3: HD"
    val gender: VoiceGender, // FEMALE, MALE
    val isTurkish: Boolean
)

object CloudVoiceRegistry {
    // Official Real Google Cloud Text-to-Speech Chirp 3: HD voices for Turkish (tr-TR)
    val turkishVoices = listOf(
        // Male Chirp 3: HD Voices
        CloudVoiceInfo("tr-TR-Chirp3-HD-Puck", "Chirp 3: HD", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Charon", "Chirp 3: HD", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Fenrir", "Chirp 3: HD", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Orus", "Chirp 3: HD", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Enceladus", "Chirp 3: HD", VoiceGender.MALE, true),
        
        // Female Chirp 3: HD Voices
        CloudVoiceInfo("tr-TR-Chirp3-HD-Kore", "Chirp 3: HD", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Aoede", "Chirp 3: HD", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Despina", "Chirp 3: HD", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Gacrux", "Chirp 3: HD", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Chirp3-HD-Sulafat", "Chirp 3: HD", VoiceGender.FEMALE, true)
    )

    // Official Real Google Cloud Text-to-Speech Chirp 3: HD voices for English (en-US)
    val englishVoices = listOf(
        CloudVoiceInfo("en-US-Chirp3-HD-Puck", "Chirp 3: HD", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Chirp3-HD-Charon", "Chirp 3: HD", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Chirp3-HD-Fenrir", "Chirp 3: HD", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Chirp3-HD-Kore", "Chirp 3: HD", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Chirp3-HD-Aoede", "Chirp 3: HD", VoiceGender.FEMALE, false)
    )

    fun getVoiceById(id: String): CloudVoiceInfo? {
        return turkishVoices.firstOrNull { it.id.equals(id, ignoreCase = true) }
            ?: englishVoices.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}
