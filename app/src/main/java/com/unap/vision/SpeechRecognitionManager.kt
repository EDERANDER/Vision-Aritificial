package com.unap.vision

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Define los diferentes estados del proceso de reconocimiento de voz.
 * Permite a la UI reaccionar a cambios como el inicio de la escucha, la recepción de resultados o errores.
 */
sealed class RecognitionState {
    /** El reconocedor está inactivo y no está escuchando. */
    object Idle : RecognitionState()
    /** El reconocedor está activamente escuchando la entrada de voz. */
    object Listening : RecognitionState()
    /** Se ha recibido un resultado de texto del reconocimiento. */
    data class Result(val text: String) : RecognitionState()
    /** Ha ocurrido un error durante el reconocimiento. */
    data class Error(val error: String) : RecognitionState()
}

/**
 * Gestiona todas las operaciones de reconocimiento de voz utilizando el `SpeechRecognizer` de Android.
 *
 * Esta clase encapsula la lógica para iniciar y detener la escucha, manejar los eventos del ciclo de vida
 * del reconocimiento y exponer el estado actual y los resultados a través de un `StateFlow`.
 *
 * @param context El contexto de la aplicación, necesario para crear una instancia de `SpeechRecognizer`.
 */
class SpeechRecognitionManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _recognitionState = MutableStateFlow<RecognitionState>(RecognitionState.Idle)
    /**
     * Un `StateFlow` que emite el estado actual del reconocimiento de voz.
     *
     * Los observadores pueden recolectar este flujo para actualizar la UI en función de si el reconocedor
     * está inactivo, escuchando, ha producido un resultado o ha encontrado un error.
     */
    val recognitionState = _recognitionState.asStateFlow()

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _recognitionState.value = RecognitionState.Listening
        }

        override fun onResults(results: Bundle?) {
            val spokenText = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.get(0) ?: ""
            _recognitionState.value = RecognitionState.Result(spokenText)
            _recognitionState.value = RecognitionState.Idle
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
                SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Tiempo de espera de red"
                SpeechRecognizer.ERROR_NO_MATCH -> "No se encontró ninguna coincidencia"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "El reconocedor está ocupado"
                SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No se detectó ninguna entrada de voz"
                else -> "Error desconocido"
            }
            _recognitionState.value = RecognitionState.Error(errorMessage)
            _recognitionState.value = RecognitionState.Idle
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /**
     * Inicia el proceso de escucha de voz.
     *
     * Comprueba si el reconocimiento de voz está disponible, crea una instancia de `SpeechRecognizer` si es necesario
     * y comienza a escuchar la entrada de voz del usuario. El estado se actualizará a `Listening`.
     */
    fun startListening() {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            }
            speechRecognizer?.startListening(intent)
        } else {
            _recognitionState.value = RecognitionState.Error("Reconocimiento no disponible")
        }
    }

    /**
     * Detiene el proceso de escucha de voz.
     *
     * El `SpeechRecognizer` dejará de escuchar y procesará el audio capturado hasta ese momento.
     */
    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    /**
     * Libera los recursos utilizados por el `SpeechRecognizer`.
     *
     * Este método debe ser llamado cuando el gestor de reconocimiento ya no es necesario (por ejemplo, en `onCleared`
     * de un ViewModel) para evitar fugas de memoria.
     */
    fun destroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
}
