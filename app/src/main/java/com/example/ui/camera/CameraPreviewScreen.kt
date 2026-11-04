package com.example.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.DetectedObject
import com.example.ml.DetectorStatus
import com.example.ml.MediaPipeObjectDetectorHelper
import com.example.ml.ObjectTracker
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonCyanLight
import com.example.ui.theme.RecCrimson
import com.example.ui.theme.SurfaceCardDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import com.example.video.TrackingVideoRecorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

@Composable
fun CameraPreviewScreen(
    videoRecorder: TrackingVideoRecorder,
    onVideoSaved: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    var isRecording by remember { mutableStateOf(false) }
    var recordingDurationSec by remember { mutableIntStateOf(0) }
    var lensFacing by remember { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    var isFlashOn by remember { mutableStateOf(false) }
    var cameraInstance by remember { mutableStateOf<Camera?>(null) }

    // Settings
    var threshold by remember { mutableFloatStateOf(0.40f) }
    var maxResults by remember { mutableIntStateOf(6) }
    var useGpu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Detections & Tracking
    var detectedObjects by remember { mutableStateOf<List<DetectedObject>>(emptyList()) }
    var prominentObject by remember { mutableStateOf<DetectedObject?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var inferenceLatencyMs by remember { mutableLongStateOf(0L) }
    var lockedTargetId by remember { mutableStateOf<Int?>(null) }

    val objectTracker = remember { ObjectTracker() }

    var frameCounter by remember { mutableIntStateOf(0) }
    var lastFpsTimestamp by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var detectorStatus by remember { mutableStateOf<DetectorStatus>(DetectorStatus.Initializing) }

    val latestTrackedRef = remember { AtomicReference<List<DetectedObject>>(emptyList()) }
    val latestProminentRef = remember { AtomicReference<DetectedObject?>(null) }

    val mediaPipeHelper = remember {
        MediaPipeObjectDetectorHelper(
            context = context,
            threshold = threshold,
            maxResults = maxResults,
            useGpu = useGpu,
            onLiveDetectionResult = { detections, latency, timestampMs ->
                inferenceLatencyMs = latency
                frameCounter++
                val now = SystemClock.uptimeMillis()
                if (now - lastFpsTimestamp >= 1000) {
                    fps = frameCounter
                    frameCounter = 0
                    lastFpsTimestamp = now
                }

                val tracked = objectTracker.processFrame(detections, timestampMs)
                latestTrackedRef.set(tracked)
                val prom = tracked.firstOrNull { it.isProminent }
                latestProminentRef.set(prom)

                detectedObjects = tracked
                prominentObject = prom
                lockedTargetId = objectTracker.lockedTargetId
            },
            onError = { error ->
                Log.e("CameraPreviewScreen", "Detector Error: $error")
            },
            onStatusChanged = { status ->
                detectorStatus = status
            }
        )
    }

    // Recording duration timer
    LaunchedEffect(isRecording) {
        if (isRecording) {
            recordingDurationSec = 0
            while (isRecording) {
                delay(1000)
                recordingDurationSec++
            }
        }
    }

    LaunchedEffect(threshold, maxResults, useGpu) {
        mediaPipeHelper.updateSettings(threshold, maxResults, useGpu)
    }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    DisposableEffect(lifecycleOwner) {
        onDispose {
            try {
                val cameraProvider = ProcessCameraProvider.getInstance(context).get()
                cameraProvider.unbindAll()
            } catch (e: Exception) {
                Log.e("CameraPreviewScreen", "Error unbinding camera on dispose: ${e.message}")
            }
            try {
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                // Ignore
            }
            mediaPipeHelper.close()
        }
    }

    LaunchedEffect(lensFacing) {
        try {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val cameraSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        android.util.Size(1280, 720),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resolutionSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                val bitmapBuffer = Bitmap.createBitmap(
                    imageProxy.width,
                    imageProxy.height,
                    Bitmap.Config.ARGB_8888
                )
                imageProxy.use {
                    bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer)
                }

                val matrix = Matrix().apply {
                    postRotate(rotationDegrees.toFloat())
                    if (lensFacing == CameraSelector.LENS_FACING_FRONT) {
                        postScale(-1f, 1f, imageProxy.width / 2f, imageProxy.height / 2f)
                    }
                }

                val rotatedBitmap = Bitmap.createBitmap(
                    bitmapBuffer,
                    0,
                    0,
                    bitmapBuffer.width,
                    bitmapBuffer.height,
                    matrix,
                    true
                )
                bitmapBuffer.recycle()

                val now = SystemClock.uptimeMillis()
                mediaPipeHelper.detectLiveStream(rotatedBitmap, now)

                if (videoRecorder.isCurrentlyRecording()) {
                    videoRecorder.recordFrame(
                        frameBitmap = rotatedBitmap,
                        detectedObjects = latestTrackedRef.get() ?: emptyList(),
                        prominentObject = latestProminentRef.get()
                    )
                }

                rotatedBitmap.recycle()
            }

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageAnalysis
            )
            cameraInstance = camera
        } catch (e: Exception) {
            Log.e("CameraPreviewScreen", "Camera binding failed: ${e.message}", e)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_preview_screen")
    ) {
        // Camera Preview
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        // Interactive Tracking HUD Overlay Canvas
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        // Tap to lock or unlock target
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val tappedObj = detectedObjects.firstOrNull { obj ->
                            val left = obj.boundingBox.left * w
                            val top = obj.boundingBox.top * h
                            val right = obj.boundingBox.right * w
                            val bottom = obj.boundingBox.bottom * h
                            offset.x in left..right && offset.y in top..bottom
                        }
                        if (tappedObj != null) {
                            objectTracker.toggleLockTarget(tappedObj.id)
                            lockedTargetId = objectTracker.lockedTargetId
                        } else {
                            objectTracker.lockedTargetId = null
                            lockedTargetId = null
                        }
                    }
                }
        ) {
            val w = size.width
            val h = size.height

            for (obj in detectedObjects) {
                val left = obj.boundingBox.left * w
                val top = obj.boundingBox.top * h
                val right = obj.boundingBox.right * w
                val bottom = obj.boundingBox.bottom * h
                val boxW = right - left
                val boxH = bottom - top

                val color = if (obj.isProminent) TargetAmber else Color(obj.colorArgb)
                val strokeWidth = if (obj.isProminent) 3.5.dp.toPx() else 2.dp.toPx()

                // Bounding Box
                drawRoundRect(
                    color = color.copy(alpha = 0.9f),
                    topLeft = Offset(left, top),
                    size = Size(boxW, boxH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                    style = Stroke(width = strokeWidth)
                )

                // Tactical Corner Brackets
                val cornerLen = min(boxW, boxH) * 0.22f
                if (cornerLen > 8f) {
                    val cornerStroke = strokeWidth * 1.5f
                    // Top-Left
                    drawLine(color, Offset(left, top), Offset(left + cornerLen, top), cornerStroke)
                    drawLine(color, Offset(left, top), Offset(left, top + cornerLen), cornerStroke)
                    // Top-Right
                    drawLine(color, Offset(right, top), Offset(right - cornerLen, top), cornerStroke)
                    drawLine(color, Offset(right, top), Offset(right, top + cornerLen), cornerStroke)
                    // Bottom-Left
                    drawLine(color, Offset(left, bottom), Offset(left + cornerLen, bottom), cornerStroke)
                    drawLine(color, Offset(left, bottom), Offset(left, bottom - cornerLen), cornerStroke)
                    // Bottom-Right
                    drawLine(color, Offset(right, bottom), Offset(right - cornerLen, bottom), cornerStroke)
                    drawLine(color, Offset(right, bottom), Offset(right, bottom - cornerLen), cornerStroke)
                }

                // Crosshair Reticle for Prominent Object
                if (obj.isProminent) {
                    val cx = (left + right) / 2f
                    val cy = (top + bottom) / 2f
                    drawCircle(color, radius = 16f, style = Stroke(width = 2f), center = Offset(cx, cy))
                    drawLine(color, Offset(cx - 22f, cy), Offset(cx + 22f, cy), 2f)
                    drawLine(color, Offset(cx, cy - 22f), Offset(cx, cy + 22f), 2f)
                }

                // Label Badge
                val isLocked = (lockedTargetId == obj.id)
                val labelText = when {
                    isLocked -> "🔒 ${obj.label.uppercase()} ${(obj.score * 100).toInt()}% #${obj.id}"
                    obj.isProminent -> "🎯 ${obj.label.uppercase()} ${(obj.score * 100).toInt()}% #${obj.id}"
                    else -> "${obj.label} ${(obj.score * 100).toInt()}% #${obj.id}"
                }

                val textLayout = textMeasurer.measure(
                    text = labelText,
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )

                val badgePad = 8f
                val badgeW = textLayout.size.width + badgePad * 2
                val badgeH = textLayout.size.height + badgePad * 2
                val badgeX = left.coerceIn(8f, (w - badgeW - 8f).coerceAtLeast(8f))
                val badgeY = if (top - badgeH - 6f < 60f) bottom + 6f else top - badgeH - 6f

                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.85f),
                    topLeft = Offset(badgeX, badgeY),
                    size = Size(badgeW, badgeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
                )
                drawRoundRect(
                    color = color,
                    topLeft = Offset(badgeX, badgeY),
                    size = Size(badgeW, badgeH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f, 6f),
                    style = Stroke(width = 1.5f)
                )
                drawText(
                    textMeasurer = textMeasurer,
                    text = labelText,
                    topLeft = Offset(badgeX + badgePad, badgeY + badgePad),
                    style = TextStyle(
                        color = Color.White,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }

        // Top Status Bar (Telemetry & Quick Actions)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // FPS & Latency HUD
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (fps > 15) CyberCyan else RecCrimson, CircleShape)
                        )
                        Text(
                            text = "$fps FPS",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "${inferenceLatencyMs}ms",
                            color = NeonCyanLight,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Quick Action Buttons
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(
                        onClick = {
                            val camera = cameraInstance
                            if (camera != null && camera.cameraInfo.hasFlashUnit()) {
                                isFlashOn = !isFlashOn
                                camera.cameraControl.enableTorch(isFlashOn)
                            } else {
                                Toast.makeText(context, "Flash unavailable", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SurfaceDark.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Flash",
                            tint = if (isFlashOn) TargetAmber else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
                                CameraSelector.LENS_FACING_FRONT
                            } else {
                                CameraSelector.LENS_FACING_BACK
                            }
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SurfaceDark.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SurfaceDark.copy(alpha = 0.8f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = CyberCyan,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Prominent Object Telemetry Header
            if (prominentObject != null) {
                val prom = prominentObject!!
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, TargetAmber.copy(alpha = 0.7f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "PROMINENT: ${prom.label.uppercase()}",
                                color = TargetAmber,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "#${prom.id}",
                                color = Color.Gray,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = prom.formattedVelocity(),
                            color = NeonCyanLight,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // Bottom Controls Bar (Recording & Saved Video Trigger)
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (isRecording) {
                Surface(
                    color = RecCrimson.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.padding(bottom = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(Color.White, CircleShape)
                        )
                        val min = recordingDurationSec / 60
                        val sec = recordingDurationSec % 60
                        Text(
                            text = String.format("REC %02d:%02d", min, sec),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Big Record Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = {
                        if (!isRecording) {
                            videoRecorder.startRecording(
                                onStarted = {
                                    isRecording = true
                                    Toast.makeText(context, "Recording tracking video...", Toast.LENGTH_SHORT).show()
                                },
                                onError = { error ->
                                    isRecording = false
                                    Toast.makeText(context, "Record error: $error", Toast.LENGTH_LONG).show()
                                }
                            )
                        } else {
                            isRecording = false
                            videoRecorder.stopRecording { file ->
                                Toast.makeText(context, "Video saved with burned-in HUD overlay!", Toast.LENGTH_SHORT).show()
                                onVideoSaved()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) RecCrimson else CyberCyan
                    ),
                    shape = RoundedCornerShape(32.dp),
                    modifier = Modifier
                        .height(56.dp)
                        .testTag("record_video_button")
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop Recording" else "Record Tracking Video",
                        tint = if (isRecording) Color.White else Color.Black,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isRecording) "STOP RECORDING" else "RECORD TRACKING VIDEO",
                        color = if (isRecording) Color.White else Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Settings Dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = SurfaceCardDark,
                title = {
                    Text("Detector Settings", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Confidence Threshold", color = Color.LightGray, fontSize = 13.sp)
                                Text("${(threshold * 100).toInt()}%", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = threshold,
                                onValueChange = { threshold = it },
                                valueRange = 0.2f..0.85f,
                                colors = SliderDefaults.colors(
                                    thumbColor = CyberCyan,
                                    activeTrackColor = CyberCyan
                                )
                            )
                        }

                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Max Detected Objects", color = Color.LightGray, fontSize = 13.sp)
                                Text("$maxResults", color = CyberCyan, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                            Slider(
                                value = maxResults.toFloat(),
                                onValueChange = { maxResults = it.toInt() },
                                valueRange = 1f..10f,
                                steps = 8,
                                colors = SliderDefaults.colors(
                                    thumbColor = CyberCyan,
                                    activeTrackColor = CyberCyan
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("GPU Hardware Acceleration", color = Color.LightGray, fontSize = 13.sp)
                            Switch(
                                checked = useGpu,
                                onCheckedChange = { useGpu = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberCyan,
                                    checkedTrackColor = CyberCyan.copy(alpha = 0.5f)
                                )
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showSettingsDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan)
                    ) {
                        Text("Apply & Close", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}
