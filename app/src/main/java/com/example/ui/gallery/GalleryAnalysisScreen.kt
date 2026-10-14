package com.example.ui.gallery

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
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
import androidx.core.content.FileProvider
import com.example.data.model.DetectedObject
import com.example.ml.MediaPipeObjectDetectorHelper
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonCyanLight
import com.example.ui.theme.SurfaceCardDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import com.example.ui.theme.TargetAmberLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

@Composable
fun GalleryAnalysisScreen(
    mediaPipeHelper: MediaPipeObjectDetectorHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()

    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedObjects by remember { mutableStateOf<List<DetectedObject>>(emptyList()) }
    var selectedObjectId by remember { mutableStateOf<Int?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var analysisDurationMs by remember { mutableStateOf(0L) }

    // Photo picker launcher
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                isAnalyzing = true
                val bitmap = loadBitmapFromUri(context, uri)
                selectedBitmap = bitmap
                if (bitmap != null) {
                    val start = System.currentTimeMillis()
                    val results = withContext(Dispatchers.Default) {
                        mediaPipeHelper.detectImage(bitmap)
                    }
                    analysisDurationMs = System.currentTimeMillis() - start
                    detectedObjects = results
                    selectedObjectId = results.firstOrNull()?.id
                }
                isAnalyzing = false
            }
        }
    }

    // Helper to generate a built-in test scene with objects if user has no photos yet
    fun loadSampleImage(sampleType: Int) {
        scope.launch {
            isAnalyzing = true
            val bitmap = createSyntheticTestScene(sampleType)
            selectedBitmap = bitmap
            val start = System.currentTimeMillis()
            val results = withContext(Dispatchers.Default) {
                mediaPipeHelper.detectImage(bitmap)
            }
            analysisDurationMs = System.currentTimeMillis() - start
            detectedObjects = results
            selectedObjectId = results.firstOrNull()?.id
            isAnalyzing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SurfaceDark)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
            .testTag("gallery_analysis_screen")
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
                    text = "Gallery Static Detection",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Detect & inspect objects in high resolution",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            // Pick Image Button
            Button(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("pick_image_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = "Pick Image",
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Pick Image", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        if (selectedBitmap == null) {
            // Empty State with Sample Test Options
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
                        imageVector = Icons.Default.Image,
                        contentDescription = "Select Image Placeholder",
                        tint = CyberCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Image Loaded",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Choose an image from your device gallery to analyze all objects with MediaPipe local inference.",
                        color = Color.Gray,
                        fontSize = 13.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = null,
                            tint = Color.Black
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Browse Device Gallery", color = Color.Black, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "— OR TRY SYNTHETIC TEST SCENES —",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = { loadSampleImage(1) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TargetAmber)
                        ) {
                            Text("Desk Scene", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { loadSampleImage(2) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = CyberCyan)
                        ) {
                            Text("Living Room", fontSize = 12.sp)
                        }
                    }
                }
            }
        } else {
            // Image Analysis View
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // Image Canvas with bounding boxes
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.Black)
                        .border(1.dp, CyberCyan.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                ) {
                    val bitmap = selectedBitmap!!
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Analyzed Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )

                    // Bounding Box Overlay
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(detectedObjects) {
                                detectTapGestures { offset ->
                                    val normX = offset.x / size.width
                                    val normY = offset.y / size.height
                                    val clicked = detectedObjects.firstOrNull { it.boundingBox.contains(normX, normY) }
                                    selectedObjectId = clicked?.id
                                }
                            }
                    ) {
                        val canvasW = size.width
                        val canvasH = size.height

                        detectedObjects.forEach { obj ->
                            val isSelected = (obj.id == selectedObjectId)
                            val left = obj.boundingBox.left * canvasW
                            val top = obj.boundingBox.top * canvasH
                            val right = obj.boundingBox.right * canvasW
                            val bottom = obj.boundingBox.bottom * canvasH
                            val w = right - left
                            val h = bottom - top

                            val color = if (isSelected) TargetAmber else CyberCyan
                            val strokeWidth = if (isSelected) 5f else 2.5f

                            // Box
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(left, top),
                                size = Size(w, h),
                                cornerRadius = CornerRadius(6f, 6f),
                                style = Stroke(width = strokeWidth)
                            )

                            // Label
                            val labelText = "${obj.label} ${obj.formattedScore()}"
                            val badgeW = 140f
                            val badgeH = 34f
                            val badgeX = left.coerceIn(4f, canvasW - badgeW - 4f)
                            val badgeY = (top - badgeH - 4f).coerceAtLeast(4f)

                            drawRoundRect(
                                color = SurfaceDark.copy(alpha = 0.85f),
                                topLeft = Offset(badgeX, badgeY),
                                size = Size(badgeW, badgeH),
                                cornerRadius = CornerRadius(4f, 4f)
                            )
                            drawRoundRect(
                                color = color,
                                topLeft = Offset(badgeX, badgeY),
                                size = Size(badgeW, badgeH),
                                cornerRadius = CornerRadius(4f, 4f),
                                style = Stroke(width = 1f)
                            )

                            drawText(
                                textMeasurer = textMeasurer,
                                text = labelText,
                                topLeft = Offset(badgeX + 6f, badgeY + 6f),
                                style = TextStyle(
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    if (isAnalyzing) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = CyberCyan)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Stats & Action Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Found ${detectedObjects.size} objects in ${analysisDurationMs}ms",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Export / Share Annotated Image
                    Button(
                        onClick = {
                            scope.launch {
                                exportAndShareAnnotatedImage(context, selectedBitmap!!, detectedObjects)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SurfaceCardDark),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TargetAmber),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.testTag("export_annotated_image_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "Share",
                            tint = TargetAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export & Share", color = TargetAmber, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // List of detected objects
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(bottom = 80.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(detectedObjects) { obj ->
                        val isSelected = (obj.id == selectedObjectId)
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) SurfaceCardDark else SurfaceDark
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) TargetAmber else CyberCyan.copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedObjectId = obj.id }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(10.dp)
                                                .clip(CircleShape)
                                                .background(if (isSelected) TargetAmber else CyberCyan)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = obj.label,
                                            color = Color.White,
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Text(
                                        text = "${(obj.score * 100).toInt()}% Confidence",
                                        color = if (isSelected) TargetAmber else CyberCyan,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { obj.score },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = if (isSelected) TargetAmber else CyberCyan,
                                    trackColor = Color.DarkGray
                                )

                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Box: [${(obj.boundingBox.left * 100).toInt()}%, ${(obj.boundingBox.top * 100).toInt()}%] → [${(obj.boundingBox.right * 100).toInt()}%, ${(obj.boundingBox.bottom * 100).toInt()}%]",
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    Text(
                                        text = "Area: ${String.format(java.util.Locale.US, "%.1f", obj.area * 100)}%",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// Helper: load Bitmap from Uri
private suspend fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeStream(stream, null, options)
        }
    } catch (e: Exception) {
        null
    }
}

// Helper: Create synthetic scenes for static testing if user has no photos
private fun createSyntheticTestScene(sampleType: Int): Bitmap {
    val w = 800
    val h = 600
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val bgPaint = Paint().apply { color = if (sampleType == 1) 0xFFE0E0E0.toInt() else 0xFF2B3A42.toInt() }
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

    val itemPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    val textPaint = Paint().apply {
        color = AndroidColor.BLACK
        textSize = 28f
        isFakeBoldText = true
    }

    if (sampleType == 1) {
        // Desk: Laptop, Cup, Cell Phone, Book
        // Laptop base
        itemPaint.color = 0xFF505050.toInt()
        canvas.drawRoundRect(RectF(150f, 250f, 500f, 480f), 20f, 20f, itemPaint)
        itemPaint.color = 0xFF888888.toInt()
        canvas.drawRoundRect(RectF(170f, 270f, 480f, 450f), 12f, 12f, itemPaint)

        // Cup
        itemPaint.color = 0xFFD32F2F.toInt()
        canvas.drawCircle(600f, 320f, 60f, itemPaint)
        itemPaint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(600f, 320f, 45f, itemPaint)

        // Cell phone
        itemPaint.color = 0xFF212121.toInt()
        canvas.drawRoundRect(RectF(560f, 420f, 650f, 550f), 10f, 10f, itemPaint)
        itemPaint.color = 0xFF00E5FF.toInt()
        canvas.drawRoundRect(RectF(568f, 430f, 642f, 540f), 6f, 6f, itemPaint)
    } else {
        // Living room: Couch, Chair, TV screen
        // TV
        itemPaint.color = 0xFF121212.toInt()
        canvas.drawRoundRect(RectF(120f, 100f, 680f, 350f), 16f, 16f, itemPaint)
        itemPaint.color = 0xFF37474F.toInt()
        canvas.drawRect(RectF(140f, 120f, 660f, 330f), itemPaint)

        // Couch / chair
        itemPaint.color = 0xFF3E2723.toInt()
        canvas.drawRoundRect(RectF(150f, 400f, 650f, 560f), 24f, 24f, itemPaint)
    }

    return bitmap
}

// Helper: Export annotated image and trigger system share sheet
private suspend fun exportAndShareAnnotatedImage(
    context: Context,
    baseBitmap: Bitmap,
    detections: List<DetectedObject>
) = withContext(Dispatchers.IO) {
    try {
        val annotated = baseBitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(annotated)
        val w = annotated.width.toFloat()
        val h = annotated.height.toFloat()

        val boxPaint = Paint().apply {
            color = 0xFF00F5D4.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        val labelBgPaint = Paint().apply {
            color = 0xDD080D1A.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        val textPaint = Paint().apply {
            color = AndroidColor.WHITE
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
        }

        detections.forEach { obj ->
            val left = obj.boundingBox.left * w
            val top = obj.boundingBox.top * h
            val right = obj.boundingBox.right * w
            val bottom = obj.boundingBox.bottom * h

            canvas.drawRoundRect(RectF(left, top, right, bottom), 12f, 12f, boxPaint)

            val label = "${obj.label} ${(obj.score * 100).toInt()}%"
            val textW = textPaint.measureText(label)
            val badgeRect = RectF(left, top - 44f, left + textW + 24f, top)
            canvas.drawRoundRect(badgeRect, 8f, 8f, labelBgPaint)
            canvas.drawText(label, left + 12f, top - 12f, textPaint)
        }

        // Save to cache/annotated
        val dir = File(context.filesDir, "annotated").apply { if (!exists()) mkdirs() }
        val file = File(dir, "VISION_DETECTION_${System.currentTimeMillis()}.jpg")
        FileOutputStream(file).use { out ->
            annotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        // Add to MediaStore
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, file.nameWithoutExtension)
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATA, file.absolutePath)
        }
        context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)

        // Share Intent
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Annotated Detection Image"))
    } catch (e: Exception) {
        withContext(Dispatchers.Main) {
            Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
