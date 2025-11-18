package com.unap.vision

import android.app.Application
import android.graphics.Bitmap
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // --- STATE ---
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _responseText = MutableStateFlow("Mantén pulsado para hablar.")
    val responseText = _responseText.asStateFlow()

    private val _spokenText = MutableStateFlow("")
    val spokenText = _spokenText.asStateFlow()

    // --- SERVICES ---
    private val generativeModel: GenerativeModel
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val speechRecognitionManager: SpeechRecognitionManager

    // --- ANALYSIS ---
    val bitmapFlow = MutableStateFlow<Bitmap?>(null)

    init {
        // Initialize services
        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )

        setupTextToSpeech(application)

        speechRecognitionManager = SpeechRecognitionManager(application)
        observeSpeechRecognition()
    }

    private fun setupTextToSpeech(context: Application) {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsInitialized = true
                    textToSpeech?.language = Locale("es", "ES")
                    textToSpeech?.setSpeechRate(1.25f)
                    Log.d("GeminiVision", "TTS initialized successfully.")
                } else {
                    Log.e("GeminiVision", "TTS initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiVision", "FATAL: Exception during TextToSpeech instance creation", e)
        }
    }

    private fun observeSpeechRecognition() {
        speechRecognitionManager.recognitionState
            .onEach { state ->
                when (state) {
                    is RecognitionState.Idle -> {
                        // Do nothing
                    }
                    is RecognitionState.Listening -> {
                        _responseText.value = "Escuchando..."
                        _spokenText.value = "" // Clear previous spoken text
                    }
                    is RecognitionState.Result -> {
                        _spokenText.value = state.text
                        if (state.text.isNotBlank()) {
                            analyzeImageWithVoicePrompt(state.text)
                        } else {
                            _responseText.value = "No te he entendido. Prueba de nuevo."
                        }
                    }
                    is RecognitionState.Error -> {
                        _responseText.value = "Error de voz: ${state.error}"
                    }
                }
            }.launchIn(viewModelScope)
    }

    fun startListening() {
        speechRecognitionManager.startListening()
    }

    fun stopListening() {
        speechRecognitionManager.stopListening()
    }

    private fun analyzeImageWithVoicePrompt(voicePrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentBitmap = bitmapFlow.value
            if (currentBitmap == null) {
                _responseText.value = "Error: No se ha capturado ninguna imagen."
                return@launch
            }

            _isLoading.value = true
            _responseText.value = "Analizando..."

            var success = false
            var attempts = 0
            val maxAttempts = 3

            while (!success && attempts < maxAttempts) {
                try {
                    val basePrompt = "Eres un asistente de visión en tiempo real. Describe la escena en la imagen de forma natural y fluida. Importante: La respuesta debe ser únicamente texto plano, sin ningún tipo de formato como negritas, cursivas o asteriscos. Habla en español."
                    val combinedPrompt = "$basePrompt\n\nPregunta del usuario: $voicePrompt"

                    val inputContent = content {
                        image(currentBitmap)
                        text(combinedPrompt)
                    }

                    val response = generativeModel.generateContent(inputContent)
                    val description = response.text ?: "No se pudo generar una descripción."

                    _responseText.value = description

                    if (isTtsInitialized && description.isNotBlank() && !description.contains("No se pudo")) {
                        textToSpeech?.speak(description, TextToSpeech.QUEUE_FLUSH, null, null)
                    }
                    success = true // Mark as success to exit the loop

                } catch (e: Exception) {
                    attempts++
                    Log.e("GeminiVision", "Error during Gemini API call (attempt $attempts)", e)
                    val errorMessage = e.localizedMessage ?: "Error desconocido"

                    if (errorMessage.contains("503", ignoreCase = true) && attempts < maxAttempts) {
                        _responseText.value = "Modelo sobrecargado, reintentando... ($attempts/$maxAttempts)"
                        delay(1000) // Wait for 1 second before retrying
                    } else {
                        _responseText.value = "Error: $errorMessage"
                        break // Exit loop on non-503 error or max attempts reached
                    }
                }
            }

            _isLoading.value = false
            // Reset to idle state after analysis
            viewModelScope.launch {
                _spokenText.value = ""
                // Do not reset responseText here to allow user to see the result
            }
        }
    }



    override fun onCleared() {
        super.onCleared()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        speechRecognitionManager.destroy()
        Log.d("GeminiVision", "ViewModel cleared and resources released.")
    }
}
