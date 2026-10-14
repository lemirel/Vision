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
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.TrackChanges
import com.example.ml.TrackingMode
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
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
import com.example.ml.MediaPipeObjectDetectorHelper
import com.example.ml.ObjectTracker
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonCyanLight
import com.example.ui.theme.RecCrimson
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import com.example.ui.theme.TargetAmberLight
import com.example.video.TrackingVideoRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.Executors
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

    // Settings state
    var threshold by remember { mutableFloatStateOf(0.40f) }
    var maxResults by remember { mutableIntStateOf(6) }
    var useGpu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Detection & Tracking state
    var detectedObjects by remember { mutableStateOf<List<DetectedObject>>(emptyList()) }
    var prominentObject by remember { mutableStateOf<DetectedObject?>(null) }
    var fps by remember { mutableIntStateOf(0) }
    var inferenceLatencyMs by remember { mutableLongStateOf(0L) }
    var lockedTargetId by remember { mutableStateOf<Int?>(null) }

    // Analyzer frame dimensions for precise coordinate mapping
    var sourceWidth by remember { mutableIntStateOf(0) }
    var sourceHeight by remember { mutableIntStateOf(0) }

    // Overlay visual display toggles
    var showConfidence by remember { mutableStateOf(true) }
    var showTrackingId by remember { mutableStateOf(true) }
    var showTrajectory by remember { mutableStateOf(true) }
    var overlayStyle by remember { mutableStateOf(OverlayStyle.CYBER_HUD) }
    var isTrackingModeEnabled by remember { mutableStateOf(true) }

    val objectTracker = remember { ObjectTracker() }

    // FPS calculation state
    var frameCounter by remember { mutableIntStateOf(0) }
    var lastFpsTimestamp by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }

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
                detectedObjects = tracked
                prominentObject = tracked.firstOrNull { it.isProminent }
                lockedTargetId = objectTracker.lockedTargetId
            },
            onError = { error ->
                Log.e("CameraPreviewScreen", "Detector Error: $error")
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

    // Update settings when sliders change
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
                Log.e("CameraPreviewScreen", "Error unbinding camera on dispose", e)
            }
            try {
                cameraExecutor.shutdown()
            } catch (e: Exception) {
                Log.e("CameraPreviewScreen", "Error shutting down camera executor", e)
            }
            mediaPipeHelper.close()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .testTag("camera_preview_screen")
    ) {
        // 1. Live Camera Preview View with COMPATIBLE texture-backed mode
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize()
        )

        LaunchedEffect(lensFacing) {
            val cameraProvider = ProcessCameraProvider.getInstance(context).get()
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(lensFacing)
                .build()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                try {
                    if (cameraExecutor.isShutdown) {
                        return@setAnalyzer
                    }
                    val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                    val bitmap = imageProxy.toBitmap()

                    val rotatedBitmap = if (rotationDegrees != 0) {
                        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    } else {
                        bitmap
                    }

                    sourceWidth = rotatedBitmap.width
                    sourceHeight = rotatedBitmap.height

                    val timestamp = SystemClock.uptimeMillis()
                    mediaPipeHelper.detectLiveStream(rotatedBitmap, timestamp)

                    if (videoRecorder.isCurrentlyRecording()) {
                        videoRecorder.submitFrame(rotatedBitmap, prominentObject, detectedObjects.size)
                    }
                } catch (e: Exception) {
                    Log.e("CameraPreviewScreen", "Analyzer error", e)
                } finally {
                    try {
                        imageProxy.close()
                    } catch (e: Exception) {
                        // Ignore if already closed
                    }
                }
            }

            try {
                cameraProvider.unbindAll()
                cameraInstance = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
            } catch (e: Exception) {
                Log.e("CameraPreviewScreen", "Use case binding failed", e)
            }
        }

        // 2. Visual Detection & Tracking Overlay directly on CameraX preview stream
        CameraDetectionOverlay(
            detectedObjects = detectedObjects,
            prominentObject = prominentObject,
            sourceImageWidth = sourceWidth,
            sourceImageHeight = sourceHeight,
            overlayStyle = overlayStyle,
            showConfidence = showConfidence,
            showTrackingId = showTrackingId,
            showTrajectory = showTrajectory,
            onObjectTapped = { tapped ->
                if (objectTracker.lockedTargetId == tapped.id) {
                    objectTracker.lockTarget(null)
                    Toast.makeText(context, "Target unlocked (Auto mode)", Toast.LENGTH_SHORT).show()
                } else {
                    objectTracker.lockTarget(tapped.id)
                    Toast.makeText(context, "Locked: ${tapped.label} (#${tapped.id})", Toast.LENGTH_SHORT).show()
                }
            },
            onEmptyAreaTapped = {
                objectTracker.lockTarget(null)
            }
        )

        // 3. Top HUD Status & Quick Actions Bar
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .align(Alignment.TopCenter)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Left: FPS & Latency badge
                Surface(
                    color = SurfaceDark.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderDefaultsBorder(CyberCyan.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(CyberCyan)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "${fps} FPS • ${inferenceLatencyMs}ms",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Center: Tracking Mode Toggle Chip
                Surface(
                    color = if (isTrackingModeEnabled) TargetAmber.copy(alpha = 0.18f) else SurfaceDark.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(20.dp),
                    border = BorderDefaultsBorder(if (isTrackingModeEnabled) TargetAmber else Color.Gray.copy(alpha = 0.5f)),
                    modifier = Modifier
                        .clickable {
                            isTrackingModeEnabled = !isTrackingModeEnabled
                            objectTracker.setMode(
                                if (isTrackingModeEnabled) TrackingMode.CONTINUOUS_TRACKING else TrackingMode.DETECTION_ONLY
                            )
                            val msg = if (isTrackingModeEnabled) {
                                "Tracking Mode Enabled: IDs preserved across frames"
                            } else {
                                "Detection-Only Mode Enabled"
                            }
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        }
                        .testTag("tracking_mode_toggle_chip")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.TrackChanges,
                            contentDescription = "Tracking Mode",
                            tint = if (isTrackingModeEnabled) TargetAmber else Color.Gray,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isTrackingModeEnabled) "TRACKING MODE" else "DETECT ONLY",
                            color = if (isTrackingModeEnabled) TargetAmber else Color.Gray,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Right: Controls row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Flashlight toggle
                    IconButton(
                        onClick = {
                            isFlashOn = !isFlashOn
                            cameraInstance?.cameraControl?.enableTorch(isFlashOn)
                        },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SurfaceDark.copy(alpha = 0.85f), CircleShape)
                            .testTag("torch_toggle_button")
                    ) {
                        Icon(
                            imageVector = if (isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = "Toggle Torch",
                            tint = if (isFlashOn) TargetAmber else Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Flip camera
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
                            .background(SurfaceDark.copy(alpha = 0.85f), CircleShape)
                            .testTag("switch_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Cameraswitch,
                            contentDescription = "Switch Camera",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Settings dialog button
                    IconButton(
                        onClick = { showSettingsDialog = true },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SurfaceDark.copy(alpha = 0.85f), CircleShape)
                            .testTag("settings_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Detection Settings",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Prominent target indicator banner with consistent ID and duration
            Surface(
                color = SurfaceDark.copy(alpha = 0.88f),
                shape = RoundedCornerShape(12.dp),
                border = BorderDefaultsBorder(
                    if (prominentObject != null) TargetAmber else CyberCyan.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = if (lockedTargetId != null) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = "Target Lock Status",
                            tint = if (prominentObject != null) TargetAmber else Color.Gray,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                text = if (prominentObject != null) {
                                    val lockPrefix = if (lockedTargetId != null) "[MANUAL LOCK] " else "[PROMINENT TARGET] "
                                    "$lockPrefix${prominentObject?.label} (${prominentObject?.formattedScore()})"
                                } else {
                                    if (isTrackingModeEnabled) "Tracking Mode Active • Consistent IDs enabled" else "Scanning scene for objects..."
                                },
                                color = if (prominentObject != null) Color.White else Color.LightGray,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                            if (prominentObject != null && isTrackingModeEnabled) {
                                Text(
                                    text = "Consistent Track ID #${prominentObject?.id} • Tracked: ${prominentObject?.formattedTrackingDuration()}",
                                    color = TargetAmberLight,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    Text(
                        text = "${detectedObjects.size} objs",
                        color = CyberCyan,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // 4. Bottom Controls: Recording & Info
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 80.dp, start = 16.dp, end = 16.dp)
                .align(Alignment.BottomCenter),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Recording status pill when active
            AnimatedVisibility(
                visible = isRecording,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Surface(
                    color = RecCrimson.copy(alpha = 0.90f),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .padding(bottom = 12.dp)
                        .scale(pulseScale)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.White)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val min = recordingDurationSec / 60
                        val sec = recordingDurationSec % 60
                        Text(
                            text = String.format(Locale.US, "RECORDING TRACKING  %02d:%02d", min, sec),
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // Big Recording Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Record/Stop Button
                Button(
                    onClick = {
                        scope.launch {
                            if (!isRecording) {
                                val savedFile = videoRecorder.startRecording()
                                if (savedFile != null) {
                                    isRecording = true
                                    Toast.makeText(context, "Recording prominent object tracking...", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isRecording = false
                                Toast.makeText(context, "Finalizing & saving video...", Toast.LENGTH_SHORT).show()
                                val file = videoRecorder.stopRecording()
                                if (file != null) {
                                    Toast.makeText(context, "Tracking video saved to gallery!", Toast.LENGTH_LONG).show()
                                    onVideoSaved()
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isRecording) RecCrimson else SurfaceDark.copy(alpha = 0.9f)
                    ),
                    shape = RoundedCornerShape(32.dp),
                    border = BorderDefaultsBorder(if (isRecording) Color.White else RecCrimson),
                    modifier = Modifier
                        .height(58.dp)
                        .padding(horizontal = 8.dp)
                        .testTag("record_tracking_button")
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = if (isRecording) "Stop Recording" else "Record Tracking",
                        tint = if (isRecording) Color.White else RecCrimson,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (isRecording) "STOP & SAVE TRACKING" else "RECORD TRACKING VIDEO",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tap on any object to lock • Videos saved with prominent object HUD",
                color = Color.LightGray.copy(alpha = 0.8f),
                fontSize = 11.sp
            )
        }

        // 5. Detection Settings Dialog
        if (showSettingsDialog) {
            AlertDialog(
                onDismissRequest = { showSettingsDialog = false },
                containerColor = SurfaceDark,
                title = {
                    Text(
                        text = "Detection & Tracking Settings",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Column {
                        Text(
                            text = "Confidence Threshold: ${(threshold * 100).toInt()}%",
                            color = CyberCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Slider(
                            value = threshold,
                            onValueChange = { threshold = it },
                            valueRange = 0.20f..0.85f,
                            colors = SliderDefaults.colors(
                                thumbColor = CyberCyan,
                                activeTrackColor = CyberCyan
                            )
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Max Results: $maxResults",
                            color = CyberCyan,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
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

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Hardware Acceleration (GPU)",
                                    color = Color.White,
                                    fontSize = 14.sp
                                )
                                Text(
                                    text = if (useGpu) "Using GPU delegate" else "Using CPU delegate",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = useGpu,
                                onCheckedChange = { useGpu = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberCyan,
                                    checkedTrackColor = CyberCyan.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Object Tracking Mode",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "Maintains consistent IDs across video frames",
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                            Switch(
                                checked = isTrackingModeEnabled,
                                onCheckedChange = {
                                    isTrackingModeEnabled = it
                                    objectTracker.setMode(
                                        if (it) TrackingMode.CONTINUOUS_TRACKING else TrackingMode.DETECTION_ONLY
                                    )
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = TargetAmber,
                                    checkedTrackColor = TargetAmber.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "VISUAL OVERLAY OPTIONS",
                            color = TargetAmber,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Confidence Scores", color = Color.White, fontSize = 13.sp)
                            Switch(
                                checked = showConfidence,
                                onCheckedChange = { showConfidence = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberCyan,
                                    checkedTrackColor = CyberCyan.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Tracking IDs (#ID)", color = Color.White, fontSize = 13.sp)
                            Switch(
                                checked = showTrackingId,
                                onCheckedChange = { showTrackingId = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = CyberCyan,
                                    checkedTrackColor = CyberCyan.copy(alpha = 0.5f)
                                )
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Show Motion Trajectory Trail", color = Color.White, fontSize = 13.sp)
                            Switch(
                                checked = showTrajectory,
                                onCheckedChange = { showTrajectory = it },
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
                        Text("Done", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

@Composable
private fun BorderDefaultsBorder(color: Color) = androidx.compose.foundation.BorderStroke(1.dp, color)
