package com.unap.vision

import android.Manifest
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen() {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)
    var responseText by remember { mutableStateOf("Mantén pulsado para iniciar el análisis.") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val generativeModel = remember {
        GenerativeModel(
            modelName = "gemini-2.5-flash", 
            apiKey = BuildConfig.GEMINI_API_KEY
        )
    }

    val bitmapFlow = remember { MutableStateFlow<Bitmap?>(null) }

    if (cameraPermissionState.status.isGranted) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.7f)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                if (isLoading) return@detectTapGestures
                                
                                // Inicia el proceso de análisis
                                coroutineScope.launch {
                                    withContext(Dispatchers.Main) {
                                        isLoading = true
                                        responseText = "Analizando..."
                                    }

                                    var isPressed = true
                                    try {
                                        // Bucle para análisis continuo mientras se mantiene pulsado
                                        launch(Dispatchers.IO) {
                                            while(isPressed) {
                                                // 1. Obtener la imagen más reciente
                                                val bitmap = bitmapFlow.filterNotNull().first()
                                                
                                                try {
                                                    // 2. Usar el prompt optimizado
                                                    val inputContent = content {
                                                        image(bitmap)
                                                        // Prompt optimizado para concisión y rol, lo que ayuda a reducir la carga
                                                        text("Eres un asistente de visión en tiempo real. Describe concisa y brevemente la escena que se presenta en la imagen. La respuesta debe ser una única frase simple y clara, formulada estrictamente en español.")
                                                    }
                                                    
                                                    // ⭐️ CAMBIO CRUCIAL: Usar generateContentStream para streaming
                                                    val stream = generativeModel.generateContentStream(inputContent)
                                                    
                                                    // Limpiamos el texto para la nueva respuesta
                                                    var currentResponse = ""

                                                    // ⭐️ PROCESAR EL STREAM: Recolectar y mostrar los fragmentos
                                                    stream.collect { chunk ->
                                                        // Muestra el texto de forma fluida (token por token)
                                                        val newText = chunk.text
                                                        if (!newText.isNullOrEmpty()) {
                                                            currentResponse += newText
                                                            withContext(Dispatchers.Main) {
                                                                responseText = currentResponse
                                                            }
                                                        }
                                                    }
                                                    
                                                } catch (e: Exception) {
                                                    // ⚠️ MANEJO DE ERRORES ROBUSTO: Captura 503, MissingFieldException, etc.
                                                    Log.e("GeminiVision", "API Error: ${e.message}", e)
                                                    withContext(Dispatchers.Main) {
                                                        val errorMsg = e.localizedMessage ?: "Error desconocido de API."
                                                        // Informa del error de conexión/servidor
                                                        responseText = "Error de Servidor o Conexión (503). Reintentando... Detalle: ${errorMsg.substringBefore(":")}" 
                                                    }
                                                    // Implementación de Backoff simple: espera 1 segundo antes de reintentar
                                                    delay(1000) 
                                                }
                                                // Retardo entre peticiones de análisis
                                                delay(1500) 
                                            }
                                        }
                                        awaitRelease() // Espera a que el usuario levante el dedo
                                    } finally {
                                        isPressed = false
                                        withContext(Dispatchers.Main) {
                                            isLoading = false
                                            responseText = "Mantén pulsado para iniciar el análisis."
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                CameraWithImageAnalysis(
                    onFrame = { bitmap ->
                        bitmapFlow.value = bitmap
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Response Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.3f)
                    .background(Color.Black)
                    .padding(16.dp),
                contentAlignment = if (isLoading && responseText == "Analizando...") Alignment.Center else Alignment.TopStart
            ) {
                if (isLoading && responseText == "Analizando...") {
                    CircularProgressIndicator()
                } else {
                    Text(
                        text = responseText,
                        color = Color.White,
                        fontSize = 18.sp,
                        textAlign = TextAlign.Start,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        }
    } else {
        LaunchedEffect(Unit) {
            cameraPermissionState.launchPermissionRequest()
        }
    }
}

@Composable
fun CameraWithImageAnalysis(
    modifier: Modifier = Modifier,
    onFrame: (Bitmap?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val imageAnalysis = remember {
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val executor = ContextCompat.getMainExecutor(ctx)

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                onFrame(imageProxy.toBitmap())
                imageProxy.close()
            }

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .build()
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            }, executor)
            previewView
        },
        modifier = modifier,
    )
}

fun ImageProxy.toBitmap(): Bitmap? {
    if (format != ImageFormat.YUV_420_888) {
        Log.e("ImageProxy", "Unsupported image format: $format")
        return null
    }

    val yBuffer = planes[0].buffer
    val uBuffer = planes[1].buffer
    val vBuffer = planes[2].buffer

    val ySize = yBuffer.remaining()
    val uSize = uBuffer.remaining()
    val vSize = vBuffer.remaining()

    val nv21 = ByteArray(ySize + uSize + vSize)

    //U and V are swapped
    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}