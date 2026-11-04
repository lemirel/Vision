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
 * Custom overlay component that draws bounding boxes, classification labels,
 * confidence scores, tracking identifiers, and trajectories in real-time based on
 * MediaPipe object detection results.
 *
 * Features:
 * - Real-time continuous rendering mapped to camera viewport dimensions.
 * - Aspect-ratio aware coordinate scaling with fill-center crop support.
 * - Front camera horizontal mirroring support.
 * - Dynamic text measurement avoiding badge clipping or text overflow.
 * - Multiple visual styles: CYBER_HUD, CLEAN_BOX, TARGET_RETICLE.
 * - Interactive tap-to-lock gesture recognition.
 */
@Composable
fun CameraDetectionOverlay(
    detectedObjects: List<DetectedObject>,
    prominentObject: DetectedObject?,
    sourceImageWidth: Int = 0,
    sourceImageHeight: Int = 0,
    isFrontCamera: Boolean = false,
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
        initialValue = 0.20f,
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
            .pointerInput(detectedObjects, sourceImageWidth, sourceImageHeight, isFrontCamera) {
                detectTapGestures { offset ->
                    val containerW = size.width.toFloat()
                    val containerH = size.height.toFloat()

                    // Find object whose mapped bounding box contains touch offset (with 24dp touch pad)
                    val tapped = detectedObjects.firstOrNull { obj ->
                        val rect = mapToPreviewCoordinates(
                            normLeft = obj.boundingBox.left,
                            normTop = obj.boundingBox.top,
                            normRight = obj.boundingBox.right,
                            normBottom = obj.boundingBox.bottom,
                            containerW = containerW,
                            containerH = containerH,
                            sourceW = sourceImageWidth,
                            sourceH = sourceImageHeight,
                            isFrontCamera = isFrontCamera
                        )
                        // Expand hit rect slightly for comfortable touch targeting
                        val hitRect = Rect(
                            rect.left - 24f,
                            rect.top - 24f,
                            rect.right + 24f,
                            rect.bottom + 24f
                        )
                        hitRect.contains(offset)
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
                normLeft = obj.boundingBox.left,
                normTop = obj.boundingBox.top,
                normRight = obj.boundingBox.right,
                normBottom = obj.boundingBox.bottom,
                containerW = containerW,
                containerH = containerH,
                sourceW = sourceImageWidth,
                sourceH = sourceImageHeight,
                isFrontCamera = isFrontCamera
            )

            if (obj.isProminent) {
                drawProminentObjectOverlay(
                    obj = obj,
                    rect = rect,
                    containerW = containerW,
                    containerH = containerH,
                    sourceImageWidth = sourceImageWidth,
                    sourceImageHeight = sourceImageHeight,
                    isFrontCamera = isFrontCamera,
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
    isFrontCamera: Boolean,
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
                normX = pt.centerX(),
                normY = pt.centerY(),
                containerW = containerW,
                containerH = containerH,
                sourceW = sourceImageWidth,
                sourceH = sourceImageHeight,
                isFrontCamera = isFrontCamera
            )
            if (i == 0) path.moveTo(mappedPt.x, mappedPt.y) else path.lineTo(mappedPt.x, mappedPt.y)

            val pointRadius = 2.5f + (i * 0.25f)
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

    when (overlayStyle) {
        OverlayStyle.CYBER_HUD -> {
            // Glowing bounding box halo
            drawRoundRect(
                color = TargetAmber.copy(alpha = pulseGlow),
                topLeft = Offset(left - 4, top - 4),
                size = Size(boxW + 8, boxH + 8),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = 8f)
            )

            // High-Tech Corner Target Brackets
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

            // Center Target Reticle & Crosshair
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
        }

        OverlayStyle.CLEAN_BOX -> {
            // Minimalist clean pill bounding box
            drawRoundRect(
                color = TargetAmber.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(12f, 12f)
            )
            drawRoundRect(
                color = TargetAmber,
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(12f, 12f),
                style = Stroke(width = 3.5f)
            )
        }

        OverlayStyle.TARGET_RETICLE -> {
            // Precision tactical reticle
            val cx = (left + right) / 2f
            val cy = (top + bottom) / 2f
            val radius = min(boxW, boxH) * 0.5f

            drawCircle(
                color = TargetAmber.copy(alpha = pulseGlow),
                radius = radius + 6f,
                center = Offset(cx, cy),
                style = Stroke(width = 6f)
            )
            drawCircle(
                color = TargetAmber,
                radius = radius,
                center = Offset(cx, cy),
                style = Stroke(width = 2.5f)
            )
            drawLine(TargetAmber, Offset(cx - radius - 16f, cy), Offset(cx + radius + 16f, cy), 2.5f)
            drawLine(TargetAmber, Offset(cx, cy - radius - 16f), Offset(cx, cy + radius + 16f), 2.5f)
        }
    }

    // Prominent Object Class Label Badge (Dynamically measured)
    val confStr = if (showConfidence) " ${obj.formattedScore()}" else ""
    val trackTag = if (obj.isTrackedAcrossFrames) "TRACKED ID #${obj.id}" else "ID #${obj.id}"
    val idStr = if (showTrackingId) "$trackTag • " else ""
    val labelText = "🎯 ${obj.label.uppercase()}$confStr"
    val durationStr = if (obj.isTrackedAcrossFrames && obj.trackingDurationMs > 500) " [${obj.formattedTrackingDuration()}]" else ""
    val subText = "$idStr${obj.formattedVelocity()}$durationStr"

    val labelLayout = textMeasurer.measure(
        text = labelText,
        style = TextStyle(
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    )
    val subLayout = textMeasurer.measure(
        text = subText,
        style = TextStyle(
            color = TargetAmberLight,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    )

    val padH = 12f
    val padV = 8f
    val badgeW = maxOf(labelLayout.size.width, subLayout.size.width) + padH * 2
    val badgeH = labelLayout.size.height + subLayout.size.height + padV * 2 + 4f
    val badgeX = left.coerceIn(8f, (containerW - badgeW - 8f).coerceAtLeast(8f))
    val badgeY = if (top - badgeH - 10f < 80f) bottom + 10f else top - badgeH - 10f

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
        topLeft = Offset(badgeX + padH, badgeY + padV),
        style = TextStyle(
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    )
    drawText(
        textMeasurer = textMeasurer,
        text = subText,
        topLeft = Offset(badgeX + padH, badgeY + padV + labelLayout.size.height + 4f),
        style = TextStyle(
            color = TargetAmberLight,
            fontSize = 10.sp,
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

    when (overlayStyle) {
        OverlayStyle.CYBER_HUD, OverlayStyle.TARGET_RETICLE -> {
            // Cyber bounding box with corner accents
            drawRoundRect(
                color = objColor.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 3f)
            )

            val cornerLen = min(boxW, boxH) * 0.20f
            if (cornerLen > 8f) {
                val thickStroke = 5f
                drawLine(objColor, Offset(left, top), Offset(left + cornerLen, top), thickStroke)
                drawLine(objColor, Offset(left, top), Offset(left, top + cornerLen), thickStroke)
                drawLine(objColor, Offset(right, top), Offset(right - cornerLen, top), thickStroke)
                drawLine(objColor, Offset(right, top), Offset(right, top + cornerLen), thickStroke)
            }
        }

        OverlayStyle.CLEAN_BOX -> {
            // Minimalist clean pill box with subtle translucent tint
            drawRoundRect(
                color = objColor.copy(alpha = 0.08f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(8f, 8f)
            )
            drawRoundRect(
                color = objColor.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(boxW, boxH),
                cornerRadius = CornerRadius(8f, 8f),
                style = Stroke(width = 2.5f)
            )
        }
    }

    // Class label tag (Dynamically measured to avoid clipping)
    val confPart = if (showConfidence) " ${obj.formattedScore()}" else ""
    val idPart = if (showTrackingId) "#${obj.id} " else ""
    val label = "$idPart${obj.label}$confPart"

    val textLayout = textMeasurer.measure(
        text = label,
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    )

    val badgePadH = 8f
    val badgePadV = 4f
    val badgeW = textLayout.size.width + badgePadH * 2
    val badgeH = textLayout.size.height + badgePadV * 2
    val badgeX = left.coerceIn(8f, (containerW - badgeW - 8f).coerceAtLeast(8f))
    val badgeY = if (top - badgeH - 6f < 70f) bottom + 6f else top - badgeH - 6f

    drawRoundRect(
        color = SurfaceDark.copy(alpha = 0.88f),
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(6f, 6f)
    )
    drawRoundRect(
        color = objColor.copy(alpha = 0.75f),
        topLeft = Offset(badgeX, badgeY),
        size = Size(badgeW, badgeH),
        cornerRadius = CornerRadius(6f, 6f),
        style = Stroke(width = 1.5f)
    )

    drawText(
        textMeasurer = textMeasurer,
        text = label,
        topLeft = Offset(badgeX + badgePadH, badgeY + badgePadV),
        style = TextStyle(
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace
        )
    )
}

/**
 * Maps normalized coordinates from MediaPipe ImageAnalysis [0..1]
 * into the visible Viewport / PreviewView coordinates.
 * Handles FILL_CENTER (aspect ratio cropping and centering) and
 * front camera mirror flipping.
 */
fun mapToPreviewCoordinates(
    normLeft: Float,
    normTop: Float,
    normRight: Float,
    normBottom: Float,
    containerW: Float,
    containerH: Float,
    sourceW: Int,
    sourceH: Int,
    isFrontCamera: Boolean = false
): Rect {
    if (sourceW <= 0 || sourceH <= 0) {
        val l = if (isFrontCamera) (1f - normRight) * containerW else normLeft * containerW
        val r = if (isFrontCamera) (1f - normLeft) * containerW else normRight * containerW
        return Rect(
            left = minOf(l, r),
            top = normTop * containerH,
            right = maxOf(l, r),
            bottom = normBottom * containerH
        )
    }

    val scale = maxOf(containerW / sourceW.toFloat(), containerH / sourceH.toFloat())
    val scaledW = sourceW * scale
    val scaledH = sourceH * scale
    val offsetX = (containerW - scaledW) / 2f
    val offsetY = (containerH - scaledH) / 2f

    val rawLeft = if (isFrontCamera) (1f - normRight) else normLeft
    val rawRight = if (isFrontCamera) (1f - normLeft) else normRight

    val left = offsetX + minOf(rawLeft, rawRight) * scaledW
    val top = offsetY + normTop * scaledH
    val right = offsetX + maxOf(rawLeft, rawRight) * scaledW
    val bottom = offsetY + normBottom * scaledH

    return Rect(left, top, right, bottom)
}

fun mapPointToPreview(
    normX: Float,
    normY: Float,
    containerW: Float,
    containerH: Float,
    sourceW: Int,
    sourceH: Int,
    isFrontCamera: Boolean = false
): Offset {
    val rawX = if (isFrontCamera) (1f - normX) else normX
    if (sourceW <= 0 || sourceH <= 0) {
        return Offset(rawX * containerW, normY * containerH)
    }

    val scale = maxOf(containerW / sourceW.toFloat(), containerH / sourceH.toFloat())
    val scaledW = sourceW * scale
    val scaledH = sourceH * scale
    val offsetX = (containerW - scaledW) / 2f
    val offsetY = (containerH - scaledH) / 2f

    return Offset(offsetX + rawX * scaledW, offsetY + normY * scaledH)
}
