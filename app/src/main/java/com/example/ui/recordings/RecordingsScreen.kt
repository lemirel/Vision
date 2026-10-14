package com.example.ui.recordings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.example.data.VideoRepository
import com.example.data.model.RecordedVideo
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.RecCrimson
import com.example.ui.theme.SurfaceCardDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import com.example.data.model.FrameTelemetry
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun RecordingsScreen(
    videoRepository: VideoRepository,
    onNavigateToCamera: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var recordings by remember { mutableStateOf<List<RecordedVideo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var activePlaybackVideo by remember { mutableStateOf<RecordedVideo?>(null) }
    var videoToDelete by remember { mutableStateOf<RecordedVideo?>(null) }

    fun refreshList() {
        scope.launch {
            isLoading = true
            recordings = videoRepository.getRecordedVideos()
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        refreshList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("recordings_screen")
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Saved Tracking Videos",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${recordings.size} Prominent Object Recording${if (recordings.size == 1) "" else "s"}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            IconButton(
                onClick = { refreshList() },
                modifier = Modifier
                    .size(40.dp)
                    .background(SurfaceCardDark, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Refresh",
                    tint = CyberCyan,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = CyberCyan)
            }
        } else if (recordings.isEmpty()) {
            // Empty State
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceCardDark)
                    .border(1.dp, CyberCyan.copy(alpha = 0.2f), RoundedCornerShape(16.dp))
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "No Videos",
                        tint = RecCrimson.copy(alpha = 0.7f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Tracking Videos Yet",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Go to the Live Camera tab and press 'RECORD TRACKING VIDEO' to capture and save video of the prominent object with HUD overlay.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = onNavigateToCamera,
                        colors = ButtonDefaults.buttonColors(containerColor = RecCrimson),
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier.testTag("go_to_camera_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open Camera & Record", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // List of Recordings
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(recordings) { video ->
                    VideoItemCard(
                        video = video,
                        videoRepository = videoRepository,
                        onPlayClick = { activePlaybackVideo = video },
                        onShareClick = { shareVideo(context, video) },
                        onDeleteClick = { videoToDelete = video }
                    )
                }
            }
        }

        // Playback Dialog
        if (activePlaybackVideo != null) {
            VideoPlaybackDialog(
                video = activePlaybackVideo!!,
                videoRepository = videoRepository,
                onDismiss = { activePlaybackVideo = null }
            )
        }

        // Delete Confirmation Dialog
        if (videoToDelete != null) {
            AlertDialog(
                onDismissRequest = { videoToDelete = null },
                containerColor = SurfaceCardDark,
                title = {
                    Text("Delete Recording?", color = Color.White, fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(
                        "Are you sure you want to delete ${videoToDelete?.fileName}? This cannot be undone.",
                        color = Color.LightGray
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val vid = videoToDelete
                            if (vid != null) {
                                scope.launch {
                                    videoRepository.deleteVideo(vid.filePath)
                                    videoToDelete = null
                                    refreshList()
                                    Toast.makeText(context, "Recording deleted", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = RecCrimson)
                    ) {
                        Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { videoToDelete = null },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray)
                    ) {
                        Text("Cancel", color = Color.White)
                    }
                }
            )
        }
    }
}

@Composable
private fun VideoItemCard(
    video: RecordedVideo,
    videoRepository: VideoRepository,
    onPlayClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    var thumbnail by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(video.filePath) {
        thumbnail = videoRepository.getVideoThumbnail(video.filePath)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyberCyan.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlayClick() }
            .testTag("video_card_${video.fileName}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with Play button overlay
            Box(
                modifier = Modifier
                    .size(width = 100.dp, height = 75.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail!!.asImageBitmap(),
                        contentDescription = "Video Thumbnail",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(SurfaceDark.copy(alpha = 0.75f), CircleShape)
                        .border(1.dp, TargetAmber, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        tint = TargetAmber,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Duration badge
                Surface(
                    color = Color.Black.copy(alpha = 0.8f),
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                ) {
                    Text(
                        text = video.formattedDuration(),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Details
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.fileName,
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = video.formattedDate(),
                    color = Color.Gray,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = TargetAmber.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TargetAmber.copy(alpha = 0.5f))
                    ) {
                        Text(
                            text = "Prominent Object Track",
                            color = TargetAmber,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    Text(
                        text = video.formattedSize(),
                        color = CyberCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            // Action Buttons
            Row {
                IconButton(onClick = onShareClick) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share Video",
                        tint = CyberCyan,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete Video",
                        tint = Color.Gray,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPlaybackDialog(
    video: RecordedVideo,
    videoRepository: VideoRepository,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var telemetryList by remember { mutableStateOf<List<FrameTelemetry>>(emptyList()) }
    var currentTelemetry by remember { mutableStateOf<FrameTelemetry?>(null) }
    var showOverlay by remember { mutableStateOf(true) }
    var playbackError by remember { mutableStateOf<String?>(null) }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }
    val textMeasurer = rememberTextMeasurer()

    LaunchedEffect(video.filePath) {
        telemetryList = videoRepository.loadVideoTelemetry(video.filePath)
    }

    LaunchedEffect(videoViewRef, telemetryList) {
        val vv = videoViewRef ?: return@LaunchedEffect
        while (isActive) {
            try {
                if (vv.isPlaying && telemetryList.isNotEmpty()) {
                    val pos = vv.currentPosition.toLong()
                    currentTelemetry = telemetryList.minByOrNull { kotlin.math.abs(it.timeMs - pos) }
                        ?.takeIf { kotlin.math.abs(it.timeMs - pos) < 800 }
                }
            } catch (e: Exception) {
                // Ignore transient player state exceptions
            }
            delay(50)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Header with Controls and Close Button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = video.fileName,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            text = if (telemetryList.isNotEmpty()) {
                                "Synchronized Prominent Object Tracking HUD (${telemetryList.size} frames)"
                            } else {
                                "Live camera recording playback"
                            },
                            color = TargetAmber,
                            fontSize = 11.sp
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (telemetryList.isNotEmpty()) {
                            IconButton(
                                onClick = { showOverlay = !showOverlay },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(SurfaceDark, CircleShape)
                            ) {
                                Icon(
                                    imageVector = if (showOverlay) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                    contentDescription = "Toggle HUD Overlay",
                                    tint = if (showOverlay) TargetAmber else Color.Gray,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(36.dp)
                                .background(SurfaceDark, CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // Video Player with Tracking Overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            VideoView(ctx).apply {
                                setVideoPath(video.filePath)
                                val mediaController = MediaController(ctx)
                                mediaController.setAnchorView(this)
                                setMediaController(mediaController)
                                setOnErrorListener { _, what, extra ->
                                    playbackError = "Playback issue (code $what:$extra). Please record a fresh video."
                                    true
                                }
                                setOnPreparedListener { mp ->
                                    mp.isLooping = true
                                    start()
                                }
                                videoViewRef = this
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Synchronized Prominent Object Tracking HUD Overlay
                    if (showOverlay && currentTelemetry != null && playbackError == null) {
                        val frame = currentTelemetry!!
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val w = size.width
                            val h = size.height

                            val left = (frame.left * w).coerceIn(0f, w)
                            val top = (frame.top * h).coerceIn(0f, h)
                            val right = (frame.right * w).coerceIn(left + 20f, w)
                            val bottom = (frame.bottom * h).coerceIn(top + 20f, h)
                            val boxW = right - left
                            val boxH = bottom - top

                            // Cyber Target Amber Box
                            drawRect(
                                color = TargetAmber.copy(alpha = 0.85f),
                                topLeft = Offset(left, top),
                                size = Size(boxW, boxH),
                                style = Stroke(width = 2.5f)
                            )

                            // Corner Target Brackets
                            val cornerLen = (boxW.coerceAtMost(boxH) * 0.25f).coerceIn(12f, 36f)
                            val bracketStroke = 4f
                            // Top-left
                            drawLine(TargetAmber, Offset(left, top), Offset(left + cornerLen, top), bracketStroke)
                            drawLine(TargetAmber, Offset(left, top), Offset(left, top + cornerLen), bracketStroke)
                            // Top-right
                            drawLine(TargetAmber, Offset(right, top), Offset(right - cornerLen, top), bracketStroke)
                            drawLine(TargetAmber, Offset(right, top), Offset(right, top + cornerLen), bracketStroke)
                            // Bottom-left
                            drawLine(TargetAmber, Offset(left, bottom), Offset(left + cornerLen, bottom), bracketStroke)
                            drawLine(TargetAmber, Offset(left, bottom), Offset(left, bottom - cornerLen), bracketStroke)
                            // Bottom-right
                            drawLine(TargetAmber, Offset(right, bottom), Offset(right - cornerLen, bottom), bracketStroke)
                            drawLine(TargetAmber, Offset(right, bottom), Offset(right, bottom - cornerLen), bracketStroke)

                            // Center Reticle
                            val cx = (left + right) / 2f
                            val cy = (top + bottom) / 2f
                            drawLine(TargetAmber.copy(alpha = 0.6f), Offset(cx - 10f, cy), Offset(cx + 10f, cy), 2f)
                            drawLine(TargetAmber.copy(alpha = 0.6f), Offset(cx, cy - 10f), Offset(cx, cy + 10f), 2f)

                            // Telemetry Badge Text
                            val infoText = "${frame.label} #${frame.trackId} • ${(frame.score * 100).toInt()}% • ${frame.velocity}"
                            val textLayout = textMeasurer.measure(
                                text = infoText,
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            val badgePad = 8f
                            val badgeW = textLayout.size.width + badgePad * 2
                            val badgeH = textLayout.size.height + badgePad * 2
                            val badgeY = (top - badgeH - 4f).coerceAtLeast(8f)

                            drawRect(
                                color = Color.Black.copy(alpha = 0.85f),
                                topLeft = Offset(left, badgeY),
                                size = Size(badgeW, badgeH)
                            )
                            drawRect(
                                color = TargetAmber,
                                topLeft = Offset(left, badgeY),
                                size = Size(badgeW, badgeH),
                                style = Stroke(width = 1.5f)
                            )
                            drawText(
                                textMeasurer = textMeasurer,
                                text = infoText,
                                topLeft = Offset(left + badgePad, badgeY + badgePad),
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    if (playbackError != null) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.85f),
                            shape = RoundedCornerShape(12.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, RecCrimson),
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = playbackError!!,
                                color = Color.White,
                                fontSize = 13.sp,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Share Button inside Player
                Button(
                    onClick = {
                        shareVideo(context, video)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.Black
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Recorded Video", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private fun shareVideo(context: Context, video: RecordedVideo) {
    val file = File(video.filePath)
    if (!file.exists()) return

    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Tracking Video"))
    } catch (e: Exception) {
        Toast.makeText(context, "Share error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
