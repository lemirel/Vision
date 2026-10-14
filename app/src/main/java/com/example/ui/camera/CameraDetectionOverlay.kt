package com.example.ui.camera

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.example.data.model.DetectedObject
import com.example.ui.theme.CyberCyan
import com.example.ui.theme.NeonCyanLight
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TargetAmber
import com.example.ui.theme.TargetAmberLight
import kotlin.math.min

enum class OverlayStyle {
    CYBER_HUD,
    CLEAN_BOX,
    TARGET_RETICLE
}

/**
 * Visual overlay composable that renders bounding boxes, class labels,
 * confidence tags, tracking trajectories, and target reticles directly
 * onto the CameraX live preview stream based on MediaPipe object detection.
 */
@Composable
fun CameraDetectionOverlay(
    detectedObjects: List<DetectedObject>,
    prominentObject: DetectedObject?,
    sourceImageWidth: Int = 0,
    sourceImageHeight: Int = 0,
    overlayStyle: OverlayStyle = OverlayStyle.CYBER_HUD,
    showConfidence: Boolean = true,
    showTrackingId: Boolean = true,
    showTrajectory: Boolean = true,
    onObjectTapped: ((DetectedObject) -> Unit)? = null,
    onEmptyAreaTapped: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    // Smooth pulse animation for prominent target reticle
    val infiniteTransition = rememberInfiniteTransition(label = "reticle_pulse")
    val pulseGlow by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(700, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_glow"
    )

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .testTag("camera_detection_overlay")
            .pointerInput(detectedObjects, sourceImageWidth, sourceImageHeight) {
                detectTapGestures { offset ->
                    val containerW = size.width.toFloat()
                    val containerH = size.height.toFloat()

                    // Find object whose mapped bounding box contains touch offset
                    val tapped = detectedObjects.firstOrNull { obj ->
                        val rect = mapToPreviewCoordinates(
                            obj.boundingBox.left,
                            obj.boundingBox.top,
                            obj.boundingBox.right,
                            obj.boundingBox.bottom,
                            containerW,
                            containerH,
                            sourceImageWidth,
                            sourceImageHeight
                        )
                        rect.contains(offset)
                    }

                    if (tapped != null) {
                        onObjectTapped?.invoke(tapped)
                    } else {
                        onEmptyAreaTapped?.invoke()
                    }
                }
            }
    ) {
        val containerW = size.width
        val containerH = size.height

        for (obj in detectedObjects) {
            val rect = mapToPreviewCoordinates(
                obj.boundingBox.left,
                obj.boundingBox.top,
                obj.boundingBox.right,
                obj.boundingBox.bottom,
                containerW,
                containerH,
                sourceImageWidth,
                sourceImageHeight
            )

            if (obj.isProminent) {
                drawProminentObjectOverlay(
                    obj = obj,
                    rect = rect,
                    containerW = containerW,
                    containerH = containerH,
                    sourceImageWidth = sourceImageWidth,
                    sourceImageHeight = sourceImageHeight,
                    pulseGlow = pulseGlow,
                    overlayStyle = overlayStyle,
                    showConfidence = showConfidence,
                    showTrackingId = showTrackingId,
                    showTrajectory = showTrajectory,
                    textMeasurer = textMeasurer
                )
            } else {
                drawStandardObjectOverlay(
                    obj = obj,
                    rect = rect,
                    containerW = containerW,
                    overlayStyle = overlayStyle,
                    showConfidence = showConfidence,
                    showTrackingId = showTrackingId,
                    textMeasurer = textMeasurer
                )
            }
        }
    }
}

private fun DrawScope.drawProminentObjectOverlay(
    obj: DetectedObject,
    rect: Rect,
    containerW: Float,
    containerH: Float,
    sourceImageWidth: Int,
    sourceImageHeight: Int,
    pulseGlow: Float,
    overlayStyle: OverlayStyle,
    showConfidence: Boolean,
    showTrackingId: Boolean,
    showTrajectory: Boolean,
    textMeasurer: TextMeasurer
) {
    val left = rect.left
    val top = rect.top
    val right = rect.right
    val bottom = rect.bottom
    val boxW = rect.width
    val boxH = rect.height

    // 1. Trajectory breadcrumb trail
    if (showTrajectory && obj.trajectory.size > 1) {
        val path = Path()
        obj.trajectory.forEachIndexed { i, pt ->
            val mappedPt = mapPointToPreview(
                pt.x, pt.y, containerW, containerH, sourceImageWidth, sourceImageHeight
            )
            if (i == 0) path.moveTo(mappedPt.x, mappedPt.y) else path.lineTo(mappedPt.x, mappedPt.y)

            val pointRadius = 2.5f + (i * 0.2f)
            drawCircle(
                color = NeonCyanLight,
                radius = pointRadius,
                center = mappedPt
            )
        }
        drawPath(
            path = path,
            color = CyberCyan.copy(alpha = 0.85f),
            style = Stroke(width = 4f)
        )
    }

    // 2. Glowing bounding box halo
    drawRoundRect(
        color = TargetAmber.copy(alpha = pulseGlow),
        topLeft = Offset(left - 4, top - 4),
        size = Size(boxW + 8, boxH + 8),
        cornerRadius = CornerRadius(12f, 12f),
        style = Stroke(width = 8f)
    )

    // 3. High-Tech Corner Target Brackets
    val bracketLen = min(boxW, boxH) * 0.28f
    val bracketColor = TargetAmber
    val bracketStroke = 6f

    // Top-Left corner
    drawLine(bracketColor, Offset(left, top), Offset(left + bracketLen, top), bracketStroke)
    drawLine(bracketColor, Offset(left, top), Offset(left, top + bracketLen), bracketStroke)
    // Top-Right corner
    drawLine(bracketColor, Offset(right, top), Offset(right - bracketLen, top), bracketStroke)
    drawLine(bracketColor, Offset(right, top), Offset(right, top + bracketLen), bracketStroke)
    // Bottom-Left corner
    drawLine(bracketColor, Offset(left, bottom), Offset(left + bracketLen, bottom), bracketStroke)
    drawLine(bracketColor, Offset(left, bottom), Offset(left, bottom - bracketLen), bracketStroke)
    // Bottom-Right corner
    drawLine(bracketColor, Offset(right, bottom), Offset(right - bracketLen, bottom), bracketStroke)
    drawLine(bracketColor, Offset(right, bottom), Offset(right, bottom - bracketLen), bracketStroke)

    // 4. Center Target Reticle & Crosshair
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    drawCircle(
        color = TargetAmber,
        radius = 16f,
        center = Offset(cx, cy),
        style = Stroke(width = 3f)
    )
    drawLine(TargetAmber, Offset(cx - 24f, cy), Offset(cx + 24f, cy), 3f)
    drawLine(TargetAmber, Offset(cx, cy - 24f), Offset(cx, cy + 24f), 3f)

    // 5. Prominent Object Class Label Badge
    val confStr = if (showConfidence) " ${obj.formattedScore()}" else ""
    val idStr = if (showTrackingId) "ID #${obj.id} • " else ""
    val labelText = "🎯 ${obj.label.uppercase()}$confStr"
    val subText = "$idStr${obj.formattedVelocity()}"

    val badgeW = 260f
    val badgeH = 68f
    val badgeX = left.coerceIn(12f, containerW - badgeW - 12f)
    val badgeY = (top - badgeH - 12f).let { if (it < 110f) bottom + 12f else it }

    drawRoundRect(
        color = SurfaceDark.copy(alpha = 0.92f),
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f)
    )
    drawRoundRect(
        color = TargetAmber,
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 2.5f)
    )

    drawText(
        textMeasurer = textMeasurer,
        text = labelText,
        topLeft = Offset(badgeX + 12f, badgeY + 8f),
        style = TextStyle(
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    )
    drawText(
        textMeasurer = textMeasurer,
        text = subText,
        topLeft = Offset(badgeX + 12f, badgeY + 36f),
        style = TextStyle(
            color = TargetAmberLight,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace
        )
    )
}

private fun DrawScope.drawStandardObjectOverlay(
    obj: DetectedObject,
    rect: Rect,
    containerW: Float,
    overlayStyle: OverlayStyle,
    showConfidence: Boolean,
    showTrackingId: Boolean,
    textMeasurer: TextMeasurer
) {
    val left = rect.left
    val top = rect.top
    val right = rect.right
    val bottom = rect.bottom
    val boxW = rect.width
    val boxH = rect.height

    val objColor = Color(obj.colorArgb)

    // Rounded bounding box
    drawRoundRect(
        color = objColor.copy(alpha = 0.85f),
        topLeft = Offset(left, top),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(8f, 8f),
        style = Stroke(width = 3f)
    )

    // Corner highlights
    val cornerLen = min(boxW, boxH) * 0.18f
    if (cornerLen > 8f) {
        val thickStroke = 5f
        drawLine(objColor, Offset(left, top), Offset(left + cornerLen, top), thickStroke)
        drawLine(objColor, Offset(left, top), Offset(left, top + cornerLen), thickStroke)
        drawLine(objColor, Offset(right, top), Offset(right - cornerLen, top), thickStroke)
        drawLine(objColor, Offset(right, top), Offset(right, top + cornerLen), thickStroke)
    }

    // Class label tag
    val confPart = if (showConfidence) " ${obj.formattedScore()}" else ""
    val idPart = if (showTrackingId) "#${obj.id} " else ""
    val label = "$idPart${obj.label}$confPart"

    val badgeW = 160f
    val badgeH = 36f
    val badgeX = left.coerceIn(8f, containerW - badgeW - 8f)
    val badgeY = (top - badgeH - 6f).coerceAtLeast(80f)

    drawRoundRect(
        color = SurfaceDark.copy(alpha = 0.85f),
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(6f, 6f)
    )
    drawRoundRect(
        color = objColor.copy(alpha = 0.6f),
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1f)
    )

    drawText(
        textMeasurer = textMeasurer,
        text = label,
        topLeft = Offset(badgeX + 8f, badgeY + 8f),
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
    )
}

/**
 * Maps normalized coordinates from MediaPipe ImageAnalysis [0..1]
 * into the visible Viewport / PreviewView coordinates.
 * Handles FILL_CENTER (aspect ratio cropping and centering).
 */
fun mapToPreviewCoordinates(
    normLeft: Float,
    normTop: Float,
    normRight: Float,
    normBottom: Float,
    containerW: Float,
    containerH: Float,
    sourceW: Int,
    sourceH: Int
): Rect {
    if (sourceW <= 0 || sourceH <= 0) {
        return Rect(
            left = normLeft * containerW,
            top = normTop * containerH,
            right = normRight * containerW,
            bottom = normBottom * containerH
        )
    }

    val scale = maxOf(containerW / sourceW.toFloat(), containerH / sourceH.toFloat())
    val scaledW = sourceW * scale
    val scaledH = sourceH * scale
    val offsetX = (containerW - scaledW) / 2f
    val offsetY = (containerH - scaledH) / 2f

    val left = offsetX + normLeft * scaledW
    val top = offsetY + normTop * scaledH
    val right = offsetX + normRight * scaledW
    val bottom = offsetY + normBottom * scaledH

    return Rect(left, top, right, bottom)
}

fun mapPointToPreview(
    normX: Float,
    normY: Float,
    containerW: Float,
    containerH: Float,
    sourceW: Int,
    sourceH: Int
): Offset {
    if (sourceW <= 0 || sourceH <= 0) {
        return Offset(normX * containerW, normY * containerH)
    }

    val scale = maxOf(containerW / sourceW.toFloat(), containerH / sourceH.toFloat())
    val scaledW = sourceW * scale
    val scaledH = sourceH * scale
    val offsetX = (containerW - scaledW) / 2f
    val offsetY = (containerH - scaledH) / 2f

    return Offset(offsetX + normX * scaledW, offsetY + normY * scaledH)
}
