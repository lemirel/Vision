package com.example.ui.gallery

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
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
import androidx.core.content.FileProvider
import com.example.data.model.DetectedObject
import com.example.ml.MediaPipeObjectDetectorHelper
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.RecCrimson
import com.example.ui.theme.SurfaceCardDark
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlin.math.min

@Composable
fun GalleryAnalysisScreen(
    mediaPipeHelper: MediaPipeObjectDetectorHelper
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var loadedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var annotatedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedObjects by remember { mutableStateOf<List<DetectedObject>>(emptyList()) }
    var isAnalyzing by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
            scope.launch {
                isAnalyzing = true
                val bitmap = loadDownsampledBitmap(context, uri)
                loadedBitmap = bitmap
                if (bitmap != null) {
                    val detections = withContext(Dispatchers.Default) {
                        mediaPipeHelper.detectImage(bitmap)
                    }
                    detectedObjects = detections
                    annotatedBitmap = withContext(Dispatchers.Default) {
                        renderStaticAnnotatedBitmap(bitmap, detections)
                    }
                }
                isAnalyzing = false
            }
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
                    text = "Static Image Analysis",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Detect and inspect objects in existing photos",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }

            Button(
                onClick = { photoPickerLauncher.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = CyberCyan),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.testTag("pick_image_button")
            ) {
                Icon(
                    imageVector = Icons.Default.AddPhotoAlternate,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Select", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }

        // Image Viewport
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1.2f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
                .border(1.dp, CyberCyan.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (annotatedBitmap != null) {
                Image(
                    bitmap = annotatedBitmap!!.asImageBitmap(),
                    contentDescription = "Annotated Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else if (loadedBitmap != null) {
                Image(
                    bitmap = loadedBitmap!!.asImageBitmap(),
                    contentDescription = "Original Image",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .clickable { photoPickerLauncher.launch("image/*") }
                        .padding(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Image,
                        contentDescription = "Pick Image",
                        tint = CyberCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Tap to choose a photo from gallery",
                        color = Color.LightGray,
                        fontSize = 14.sp
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = CyberCyan)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Analyzing objects with MediaPipe...", color = Color.White, fontSize = 13.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Detections Summary & Export Actions
        if (annotatedBitmap != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        shareAnnotatedImage(context, annotatedBitmap!!)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = TargetAmber),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.Black,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Share Result", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Detected Objects List
        Text(
            text = "Detections (${detectedObjects.size})",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (detectedObjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f)
                    .padding(bottom = 80.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (loadedBitmap != null) "No objects detected above confidence threshold" else "Select an image above to see detection results",
                    color = Color.Gray,
                    fontSize = 13.sp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f)
                    .padding(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(detectedObjects) { obj ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = SurfaceCardDark),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, TargetAmber.copy(alpha = 0.3f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(TargetAmber, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = obj.label.uppercase(),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = "Object #${obj.id}",
                                        color = Color.Gray,
                                        fontSize = 11.sp
                                    )
                                }
                            }

                            Surface(
                                color = TargetAmber.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "${(obj.score * 100).toInt()}% Match",
                                    color = TargetAmber,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Loads image safely with downsampling to avoid out-of-memory errors on large gallery photos.
 */
private fun loadDownsampledBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        val maxDim = 1920
        var sampleSize = 1
        while (options.outWidth / sampleSize > maxDim || options.outHeight / sampleSize > maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
        }
    } catch (e: Exception) {
        null
    }
}

private fun renderStaticAnnotatedBitmap(
    source: Bitmap,
    detections: List<DetectedObject>
): Bitmap {
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(result)
    val w = result.width.toFloat()
    val h = result.height.toFloat()

    for (obj in detections) {
        val left = obj.boundingBox.left * w
        val top = obj.boundingBox.top * h
        val right = obj.boundingBox.right * w
        val bottom = obj.boundingBox.bottom * h

        val boxPaint = Paint().apply {
            color = 0xFFFFB703.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, boxPaint)

        val labelText = "🎯 ${obj.label.uppercase()} ${(obj.score * 100).toInt()}%"
        val textPaint = Paint().apply {
            color = AndroidColor.WHITE
            textSize = 28f
            isFakeBoldText = true
            isAntiAlias = true
            typeface = Typeface.MONOSPACE
        }
        val textWidth = textPaint.measureText(labelText)
        val badgeW = textWidth + 24f
        val badgeH = 44f
        val badgeY = if (top - badgeH - 8f < 10f) bottom + 8f else top - badgeH - 8f

        val bgPaint = Paint().apply {
            color = 0xDD0A0E1A.toInt()
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawRoundRect(left, badgeY, left + badgeW, badgeY + badgeH, 8f, 8f, bgPaint)
        canvas.drawRoundRect(left, badgeY, left + badgeW, badgeY + badgeH, 8f, 8f, boxPaint)
        canvas.drawText(labelText, left + 12f, badgeY + badgeH - 12f, textPaint)
    }

    return result
}

private fun shareAnnotatedImage(context: Context, bitmap: Bitmap) {
    try {
        val cacheDir = File(context.cacheDir, "shared_images").apply { if (!exists()) mkdirs() }
        val imageFile = File(cacheDir, "vision_track_${System.currentTimeMillis()}.jpg")
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Object Detection Analysis"))
    } catch (e: Exception) {
        Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
