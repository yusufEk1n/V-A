package com.example.virtualassistant.assistant.util

import android.content.Context
import com.google.cloud.texttospeech.v1.*
import com.google.auth.oauth2.GoogleCredentials
import java.io.ByteArrayInputStream

class TextToSpeechManager(context: Context) {
    private lateinit var textToSpeechClient: TextToSpeechClient

    private var credentials : GoogleCredentials

    init {
        val credentialsJson = context.assets.open("keys/tts.json")
        val credentialsStream = ByteArrayInputStream(credentialsJson.readBytes())

        credentials = GoogleCredentials.fromStream(credentialsStream)
    }

    fun synthesizeSpeech(text: String): ByteArray {

        val settings = TextToSpeechSettings.newBuilder().setCredentialsProvider { credentials }.build()

        textToSpeechClient = TextToSpeechClient.create(settings)

        val input = SynthesisInput.newBuilder().setText(text).build()
        val voice = VoiceSelectionParams.newBuilder().setLanguageCode("tr-TR").build()
        val audioConfig = AudioConfig.newBuilder().setAudioEncoding(AudioEncoding.LINEAR16).build()

        val response = textToSpeechClient.synthesizeSpeech(input, voice, audioConfig)

        return response.audioContent.toByteArray()
    }

    fun close() {
        textToSpeechClient.close()
    }
}
