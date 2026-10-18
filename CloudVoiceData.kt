package com.example.cameratranslator

data class CloudVoiceInfo(
    val id: String,
    val family: String,      // "WaveNet", "Neural2", "Studio", "Standard"
    val gender: VoiceGender, // FEMALE, MALE
    val isTurkish: Boolean
)

object CloudVoiceRegistry {
    // Official Google Cloud Text-to-Speech voices for Turkish (tr-TR)
    // Note: Google Cloud TTS offers WaveNet and Standard voices for Turkish.
    val turkishVoices = listOf(
        CloudVoiceInfo("tr-TR-Wavenet-A", "WaveNet", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Wavenet-B", "WaveNet", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Wavenet-C", "WaveNet", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Wavenet-D", "WaveNet", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Wavenet-E", "WaveNet", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Standard-A", "Standard", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Standard-B", "Standard", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Standard-C", "Standard", VoiceGender.FEMALE, true),
        CloudVoiceInfo("tr-TR-Standard-D", "Standard", VoiceGender.MALE, true),
        CloudVoiceInfo("tr-TR-Standard-E", "Standard", VoiceGender.MALE, true)
    )

    // Official Google Cloud Text-to-Speech voices for English (en-US)
    val englishVoices = listOf(
        CloudVoiceInfo("en-US-Neural2-F", "Neural2", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Neural2-D", "Neural2", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Neural2-A", "Neural2", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Neural2-C", "Neural2", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Neural2-H", "Neural2", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Neural2-I", "Neural2", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Neural2-J", "Neural2", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Studio-O", "Studio", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Studio-Q", "Studio", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Wavenet-F", "WaveNet", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Wavenet-D", "WaveNet", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Wavenet-A", "WaveNet", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Wavenet-B", "WaveNet", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Wavenet-C", "WaveNet", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Wavenet-E", "WaveNet", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Standard-F", "Standard", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Standard-D", "Standard", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Standard-A", "Standard", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Standard-B", "Standard", VoiceGender.MALE, false),
        CloudVoiceInfo("en-US-Standard-C", "Standard", VoiceGender.FEMALE, false),
        CloudVoiceInfo("en-US-Standard-E", "Standard", VoiceGender.MALE, false)
    )

    fun getVoiceById(id: String): CloudVoiceInfo? {
        return turkishVoices.firstOrNull { it.id == id }
            ?: englishVoices.firstOrNull { it.id == id }
    }
}
