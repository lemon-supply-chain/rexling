package dev.shulhi.rex

import android.Manifest
import android.content.pm.PackageManager
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.net.URI
import java.util.concurrent.Executors

internal fun parseQrServer(raw: String): Pair<String, String>? = runCatching {
    var text = raw.trim()
    if (text.isEmpty()) return null
    if (!text.contains("://")) text = "https://$text"
    else if (text.startsWith("rex://", ignoreCase = true)) text = "https://" + text.substring(6)
    val normalized = ServerProfile.normalizeEndpoint(text)
    val host = requireNotNull(URI(normalized).host)
    val label = if (host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))) host else host.substringBefore('.')
    label to normalized
}.getOrNull()

@Composable internal fun QrScannerDialog(onDismiss: () -> Unit, onScanned: (label: String, endpoint: String) -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (hasPermission) {
                CameraQrScanner(onDismiss, onScanned)
            } else {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Camera permission required", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    Text("Grant camera access to scan your Rex pairing QR code.", color = Color.White.copy(alpha = .7f), fontSize = 14.sp)
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                        Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Grant permission") }
                    }
                }
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.statusBarsPadding().padding(16.dp).align(Alignment.TopEnd).clip(CircleShape).background(Color.Black.copy(alpha = .5f))
            ) {
                ChromeSymbol(ChromeIcon.CLOSE, color = Color.White)
            }
        }
    }
}

@Composable private fun CameraQrScanner(onDismiss: () -> Unit, onScanned: (label: String, endpoint: String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val view = LocalView.current
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var scanned by remember { mutableStateOf(false) }

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val reader = MultiFormatReader().apply {
                        setHints(mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.CHARACTER_SET to "UTF-8"
                        ))
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!scanned) {
                            val text = decodeQr(imageProxy, reader)
                            if (!text.isNullOrBlank() && !scanned) {
                                val parsed = parseQrServer(text)
                                if (parsed != null) {
                                    scanned = true
                                    view.post {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onScanned(parsed.first, parsed.second)
                                        onDismiss()
                                    }
                                } else {
                                    view.post { errorMessage = "Invalid server URL: $text" }
                                }
                            }
                        }
                        imageProxy.close()
                    }

                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        Canvas(Modifier.fillMaxSize()) {
            val boxSize = size.minDimension * .68f
            val left = (size.width - boxSize) / 2f
            val top = (size.height - boxSize) / 2f
            val cornerLength = 28.dp.toPx()
            val strokeWidth = 3.5.dp.toPx()
            val cornerRadius = 16.dp.toPx()
            val tint = Color(0xff7293ff)

            drawRect(Color.Black.copy(alpha = .55f))
            drawRoundRect(
                Color.Transparent,
                Offset(left, top),
                Size(boxSize, boxSize),
                CornerRadius(cornerRadius),
                blendMode = BlendMode.Clear
            )

            fun corner(x1: Float, y1: Float, dx: Float, dy: Float) {
                drawLine(tint, Offset(x1, y1), Offset(x1 + dx * cornerLength, y1), strokeWidth, StrokeCap.Round)
                drawLine(tint, Offset(x1, y1), Offset(x1, y1 + dy * cornerLength), strokeWidth, StrokeCap.Round)
            }
            corner(left, top, 1f, 1f)
            corner(left + boxSize, top, -1f, 1f)
            corner(left, top + boxSize, 1f, -1f)
            corner(left + boxSize, top + boxSize, -1f, -1f)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 64.dp).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Scan pairing QR code in Rex",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium
            )
            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
            }
        }
    }
}

private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val width = image.width
    val height = image.height
    val yBytes = ByteArray(width * height)
    val position = buffer.position()

    if (rowStride == width) {
        buffer.get(yBytes, 0, width * height)
    } else {
        for (row in 0 until height) {
            buffer.position(position + row * rowStride)
            buffer.get(yBytes, row * width, width)
        }
    }

    val rotation = image.imageInfo.rotationDegrees
    val (rotatedBytes, rotW, rotH) = if (rotation == 90) {
        val rotated = ByteArray(yBytes.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                rotated[i++] = yBytes[y * width + x]
            }
        }
        Triple(rotated, height, width)
    } else if (rotation == 270) {
        val rotated = ByteArray(yBytes.size)
        var i = 0
        for (x in width - 1 downTo 0) {
            for (y in 0 until height) {
                rotated[i++] = yBytes[y * width + x]
            }
        }
        Triple(rotated, height, width)
    } else {
        Triple(yBytes, width, height)
    }

    val source = PlanarYUVLuminanceSource(rotatedBytes, rotW, rotH, 0, 0, rotW, rotH, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decodeWithState(bitmap).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
