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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class)
/**
 * Composable principal que construye la pantalla de la cámara.
 *
 * Esta función se encarga de:
 * - Solicitar los permisos de cámara y micrófono.
 * - Mostrar la vista previa de la cámara en la mitad superior de la pantalla.
 * - Mostrar los controles de interacción (área táctil para hablar) y los textos de estado en la mitad inferior.
 * - Conectar la UI con el [MainViewModel] para manejar el estado y los eventos.
 *
 * @param viewModel La instancia del [MainViewModel] que gestiona la lógica de la aplicación.
 */
@Composable
fun CameraScreen(viewModel: MainViewModel = viewModel()) {
    val permissionsState = rememberMultiplePermissionsState(
        permissions = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    )

    val isLoading by viewModel.isLoading.collectAsState()
    val responseText by viewModel.responseText.collectAsState()
    val spokenText by viewModel.spokenText.collectAsState()

    if (!permissionsState.allPermissionsGranted) {
        LaunchedEffect(Unit) {
            permissionsState.launchMultiplePermissionRequest()
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Text("Se necesitan permisos de cámara y micrófono.", color = Color.White)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            CameraWithImageAnalysis(
                onFrame = { bitmap ->
                    viewModel.bitmapFlow.value = bitmap
                },
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(32.dp))
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            viewModel.startListening()
                            try {
                                awaitRelease()
                            } finally {
                                viewModel.stopListening()
                            }
                        }
                    )
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (spokenText.isNotBlank()) {
                Text(
                    text = "\"$spokenText\"",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isListening = responseText == "Escuchando..."
            MicButton(
                isLoading = isLoading,
                isListening = isListening
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = responseText,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

/**
 * Un Composable que muestra un botón de micrófono animado.
 *
 * Actúa como un indicador visual del estado de la grabación de voz.
 * La animación (escala y color) cambia según si la app está escuchando, procesando o inactiva.
 * Este Composable no maneja la interacción del usuario directamente.
 *
 * @param isLoading Indica si la aplicación está procesando una solicitud.
 * @param isListening Indica si la aplicación está escuchando activamente la voz del usuario.
 */
@Composable
private fun MicButton(
    isLoading: Boolean,
    isListening: Boolean
) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening || isLoading) 1.2f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "mic_scale"
    )

    val color by animateColorAsState(
        targetValue = if (isListening) Color.Red else if (isLoading) Color.DarkGray else Color(0xFF4CAF50),
        animationSpec = tween(300),
        label = "mic_color"
    )

    Box(
        modifier = Modifier
            .size(128.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Mic,
            contentDescription = "Grabar voz",
            tint = Color.White,
            modifier = Modifier.size(64.dp)
        )
    }
}


/**
 * Un Composable que integra la vista previa de la cámara de CameraX y configura un analizador de imágenes.
 *
 * Utiliza `AndroidView` para alojar una `PreviewView` de CameraX. Configura un `ImageAnalysis`
 * para procesar los fotogramas de la cámara en un hilo separado, convirtiéndolos a `Bitmap` y
 * emitiéndolos a través del callback `onFrame`.
 *
 * @param modifier Modificador de Compose para aplicar a la vista de la cámara.
 * @param onFrame Un callback que se invoca con cada nuevo `Bitmap` procesado desde la cámara.
 */
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
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val mainExecutor = ContextCompat.getMainExecutor(ctx)

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
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
            }, mainExecutor)
            previewView
        },
        modifier = modifier,
    )
}

/**
 * Función de extensión para convertir un `ImageProxy` a un `Bitmap`.
 *
 * Este método está optimizado para el formato `YUV_420_888`, que es común en CameraX.
 * Convierte los planos Y, U y V a un array de bytes NV21, y luego comprime este
 * a un `Bitmap` en formato JPEG.
 *
 * @return Un `Bitmap` si la conversión es exitosa, o `null` si el formato no es compatible.
 */
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

    yBuffer.get(nv21, 0, ySize)
    vBuffer.get(nv21, ySize, vSize)
    uBuffer.get(nv21, ySize + vSize, uSize)

    val yuvImage = YuvImage(nv21, ImageFormat.NV21, this.width, this.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, yuvImage.width, yuvImage.height), 90, out)
    val imageBytes = out.toByteArray()
    return android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
}
