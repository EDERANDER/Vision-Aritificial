package com.unap.vision

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class MainViewModel(application: Application) : AndroidViewModel(application) {

    // State
    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _responseText = MutableStateFlow("Mantén pulsado para iniciar el análisis.")
    val responseText = _responseText.asStateFlow()

    // Services
    private val generativeModel: GenerativeModel
    private var textToSpeech: TextToSpeech? = null
    private val vibrator: Vibrator
    private var isTtsInitialized = false

    // Analysis
    val bitmapFlow = MutableStateFlow<Bitmap?>(null)
    private var analysisJob: Job? = null
    @Volatile private var shouldContinueAnalysis = false

    init {
        // Initialize services
        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )

        vibrator = application.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        Log.d("GeminiVision", "Vibrator hardware present: ${vibrator.hasVibrator()}")

        try {
            textToSpeech = TextToSpeech(application) { status ->
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

    fun startAnalysis() {
        if (analysisJob?.isActive == true) return

        shouldContinueAnalysis = true
        _isLoading.value = true
        _responseText.value = "Analizando..."

        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            var lastProcessedBitmap: Bitmap? = null
            while (shouldContinueAnalysis) {
                val currentBitmap = bitmapFlow.value
                if (currentBitmap != null && currentBitmap != lastProcessedBitmap) {
                    lastProcessedBitmap = currentBitmap
                    
                    // Haptic feedback
                    if (vibrator.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                        } else {
                            @Suppress("DEPRECATION")
                            vibrator.vibrate(100)
                        }
                    }

                    try {
                        val inputContent = content {
                            image(currentBitmap)
                            text("Eres un asistente de visión en tiempo real. Describe concisa y brevemente la escena que se presenta en la imagen. La respuesta debe ser una única frase simple y clara, formulada estrictamente en español.")
                        }

                        val response = generativeModel.generateContent(inputContent)
                        val description = response.text ?: "No se pudo generar una descripción."

                        _responseText.value = description

                        if (isTtsInitialized && description.isNotBlank() && !description.contains("No se pudo")) {
                            textToSpeech?.speak(description, TextToSpeech.QUEUE_FLUSH, null, null)
                        }

                    } catch (e: Exception) {
                        Log.e("GeminiVision", "Error during Gemini API call", e)
                        val errorMessage = e.localizedMessage ?: "Unknown error"
                        if (errorMessage.contains("overloaded", ignoreCase = true) || errorMessage.contains("503")) {
                            Log.w("GeminiVision", "Model overloaded. Skipping this frame.")
                        } else {
                            _responseText.value = "Error: $errorMessage"
                        }
                    }
                }
                delay(750)
            }
        }
    }

    fun stopAnalysis() {
        shouldContinueAnalysis = false
        analysisJob = null
        textToSpeech?.stop()
        _isLoading.value = false
        _responseText.value = "Mantén pulsado para iniciar el análisis."
    }

    override fun onCleared() {
        super.onCleared()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        Log.d("GeminiVision", "ViewModel cleared and resources released.")
    }
}
