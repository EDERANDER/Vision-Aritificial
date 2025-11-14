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
    var responseText by remember { mutableStateOf("Press and hold the screen to start analysis.") }
    var isLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val generativeModel = remember {
        GenerativeModel(
            modelName = "gemini-2.5-flash", // Changed from gemini-1.5-flash-latest
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
                                
                                coroutineScope.launch {
                                    withContext(Dispatchers.Main) {
                                        isLoading = true
                                        responseText = "Analyzing..."
                                    }

                                    var isPressed = true
                                    try {
                                        // Continuous analysis while pressed
                                        launch(Dispatchers.IO) {
                                            while(isPressed) {
                                                val bitmap = bitmapFlow.filterNotNull().first()
                                                try {
                                                    val inputContent = content {
                                                        image(bitmap)
                                                        text("Describe lo que ves en una frase corta y sencilla en español.")
                                                    }
                                                    val response = generativeModel.generateContent(inputContent)
                                                    withContext(Dispatchers.Main) {
                                                        responseText = response.text ?: "No description available."
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("GeminiVision", "API Error: ${e.message}", e)
                                                    withContext(Dispatchers.Main) {
                                                        responseText = "Error: ${e.localizedMessage}"
                                                    }
                                                }
                                                delay(500) // Delay between requests
                                            }
                                        }
                                        awaitRelease()
                                    } finally {
                                        isPressed = false
                                        withContext(Dispatchers.Main) {
                                            isLoading = false
                                            responseText = "Press and hold the screen to start analysis."
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
                contentAlignment = Alignment.Center
            ) {
                if (isLoading && responseText == "Analyzing...") {
                    CircularProgressIndicator()
                }
                Text(
                    text = responseText,
                    color = Color.White,
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                )
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