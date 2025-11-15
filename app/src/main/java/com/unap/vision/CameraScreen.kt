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
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
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
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@OptIn(ExperimentalPermissionsApi::class, ExperimentalAnimationApi::class)
@Composable
fun CameraScreen(viewModel: MainViewModel = viewModel()) {
    val cameraPermissionState = rememberPermissionState(Manifest.permission.CAMERA)

    val isLoading by viewModel.isLoading.collectAsState()
    val responseText by viewModel.responseText.collectAsState()

    if (cameraPermissionState.status.isGranted) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            viewModel.startAnalysis()
                            try {
                                awaitRelease()
                            } finally {
                                viewModel.stopAnalysis()
                            }
                        }
                    )
                }
        ) {
            // Camera Preview Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f)
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

            // Microphone Area
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.5f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = if (isLoading) 1.2f else 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ), label = "mic_scale"
                    )

                    Icon(
                        imageVector = Icons.Default.Mic,
                        contentDescription = responseText,
                        tint = Color.White,
                        modifier = Modifier
                            .scale(scale)
                            .size(128.dp)
                    )

                    Text(
                        text = responseText,
                        color = Color.White,
                        modifier = Modifier.padding(top = 16.dp)
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
