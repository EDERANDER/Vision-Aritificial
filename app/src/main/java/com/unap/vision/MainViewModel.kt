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

/**
 * ViewModel principal de la aplicación, responsable de orquestar la lógica de negocio y el estado de la UI.
 *
 * Gestiona la interacción entre la captura de imágenes, el reconocimiento de voz, el modelo generativo de Gemini
 * y el motor de Text-to-Speech (TTS). Expone el estado a la UI a través de `StateFlow` y maneja
 * el ciclo de vida de los componentes que utiliza.
 *
 * @param application La instancia de la aplicación, necesaria para el `AndroidViewModel`.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _isLoading = MutableStateFlow(false)
    /** `StateFlow` que indica si hay una operación de análisis en curso. */
    val isLoading = _isLoading.asStateFlow()

    private val _responseText = MutableStateFlow("Mantén pulsado para hablar.")
    /** `StateFlow` que contiene el texto de respuesta del modelo o los mensajes de estado para mostrar en la UI. */
    val responseText = _responseText.asStateFlow()

    private val _spokenText = MutableStateFlow("")
    /** `StateFlow` que contiene el texto reconocido a partir de la entrada de voz del usuario. */
    val spokenText = _spokenText.asStateFlow()

    private val generativeModel: GenerativeModel
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val speechRecognitionManager: SpeechRecognitionManager

    /** `StateFlow` que mantiene el último fotograma de la cámara como un `Bitmap` para el análisis. */
    val bitmapFlow = MutableStateFlow<Bitmap?>(null)

    init {
        generativeModel = GenerativeModel(
            modelName = "gemini-2.5-flash",
            apiKey = BuildConfig.GEMINI_API_KEY
        )

        setupTextToSpeech(application)

        speechRecognitionManager = SpeechRecognitionManager(application)
        observeSpeechRecognition()
    }

    /**
     * Configura e inicializa el motor de Text-to-Speech (TTS).
     * Establece el idioma a español y la velocidad de habla.
     */
    private fun setupTextToSpeech(context: Application) {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    isTtsInitialized = true
                    textToSpeech?.language = Locale("es", "ES")
                    textToSpeech?.setSpeechRate(1.5f)
                    Log.d("GeminiVision", "TTS initialized successfully.")
                } else {
                    Log.e("GeminiVision", "TTS initialization failed with status: $status")
                }
            }
        } catch (e: Exception) {
            Log.e("GeminiVision", "FATAL: Exception during TextToSpeech instance creation", e)
        }
    }

    /**
     * Observa el `StateFlow` del `SpeechRecognitionManager` para reaccionar a los eventos de voz.
     *
     * Inicia el análisis de la imagen cuando se recibe un resultado de voz válido.
     * Actualiza la UI para reflejar el estado de escucha o los errores.
     */
    private fun observeSpeechRecognition() {
        speechRecognitionManager.recognitionState
            .onEach { state ->
                when (state) {
                    is RecognitionState.Idle -> {
                    }
                    is RecognitionState.Listening -> {
                        _responseText.value = "Escuchando..."
                        _spokenText.value = ""
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

    /** Delega la acción de iniciar la escucha al `SpeechRecognitionManager`. */
    fun startListening() {
        speechRecognitionManager.startListening()
    }

    /** Delega la acción de detener la escucha al `SpeechRecognitionManager`. */
    fun stopListening() {
        speechRecognitionManager.stopListening()
    }

    /**
     * Analiza la imagen actual de la cámara junto con una pregunta de voz.
     *
     * Esta función se lanza en una corrutina de IO. Combina un prompt base con la pregunta del usuario,
     * envía la imagen y el texto al modelo de Gemini, e implementa una lógica de reintentos para manejar
     * errores de sobrecarga del modelo (503). La respuesta generada se emite para la UI y se reproduce
     * a través de TTS.
     * @param voicePrompt El texto reconocido de la pregunta del usuario.
     */
    private fun analyzeImageWithVoicePrompt(voicePrompt: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val currentBitmap = bitmapFlow.value
            if (currentBitmap == null) {
                _responseText.value = "Error: No se ha capturado ninguna imagen."
                return@launch
            }

            _isLoading.value = true
            _responseText.value = "" // Limpiar para el streaming

            var success = false
            var attempts = 0
            val maxAttempts = 3

            while (!success && attempts < maxAttempts) {
                try {
                    val basePrompt = "Eres un asistente de visión en tiempo real, describe la escena en la imagen de forma natural, fluida en una sola frase. Important: La respuesta debe ser únicamente texto plano, no incluya ningún formato adicional. Habla en español."
                    val combinedPrompt = "$basePrompt\n\nPregunta del usuario: $voicePrompt"

                    val inputContent = content {
                        image(currentBitmap)
                        text(combinedPrompt)
                    }

                    val responseStream = generativeModel.generateContentStream(inputContent)
                    val fullResponse = StringBuilder()

                    responseStream.collect { chunk ->
                        chunk.text?.let { textPart ->
                            fullResponse.append(textPart)
                            // Actualiza la UI en tiempo real
                            _responseText.value = fullResponse.toString()
                        }
                    }

                    val finalDescription = fullResponse.toString()
                    if (finalDescription.isNotBlank()) {
                        if (isTtsInitialized && !finalDescription.contains("No se pudo")) {
                            textToSpeech?.speak(finalDescription, TextToSpeech.QUEUE_FLUSH, null, null)
                        }
                        success = true
                    } else {
                        _responseText.value = "No se pudo generar una descripción."
                    }

                } catch (e: Exception) {
                    attempts++
                    Log.e("GeminiVision", "Error during Gemini API stream (attempt $attempts)", e)
                    val errorMessage = e.localizedMessage ?: "Error desconocido"

                    if (errorMessage.contains("503", ignoreCase = true) && attempts < maxAttempts) {
                        _responseText.value = "Modelo sobrecargado, reintentando... ($attempts/$maxAttempts)"
                        delay(1000)
                    } else {
                        _responseText.value = "Error: $errorMessage"
                        break
                    }
                }
            }

            _isLoading.value = false
            viewModelScope.launch {
                _spokenText.value = ""
            }
        }
    }



    /**
     * Se llama cuando el ViewModel está a punto de ser destruido.
     * Libera los recursos del motor TTS y del `SpeechRecognitionManager` para prevenir fugas de memoria.
     */
    override fun onCleared() {
        super.onCleared()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        speechRecognitionManager.destroy()
        Log.d("GeminiVision", "ViewModel cleared and resources released.")
    }
}
