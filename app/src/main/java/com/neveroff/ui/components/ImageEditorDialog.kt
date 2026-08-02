package com.neveroff.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min

enum class ImageEditFilter(val label: String) {
    NONE("Original"),
    BW("Black & White"),
    RETRO("Retro");

    fun toColorFilter(): android.graphics.ColorFilter? = when (this) {
        NONE -> null
        BW -> ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        RETRO -> ColorMatrixColorFilter(
            ColorMatrix(
                floatArrayOf(
                    1.15f, 0f, 0f, 0f, 12f,
                    0f, 0.92f, 0f, 0f, 8f,
                    0f, 0f, 0.72f, 0f, -6f,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
    }
}

/** Interactive editing (pan/zoom/rotate redraws every drag frame) never needs more resolution
 * than this — a 12MP camera photo decoded at full size makes every frame redraw ~10x more
 * pixels than the screen can even show, which is where drag/pinch jank was coming from. The
 * final exported bitmap is rendered at its own target size independently (see renderFinalBitmap). */
private const val MaxEditDimension = 1600

private fun computeSampleSize(width: Int, height: Int, maxDimension: Int): Int {
    var sampleSize = 1
    while (maxOf(width, height) / sampleSize > maxDimension) sampleSize *= 2
    return sampleSize
}

private fun loadBitmap(context: android.content.Context, uri: Uri): Bitmap {
    val bitmap = if (Build.VERSION.SDK_INT >= 28) {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.isMutableRequired = true
            val sampleSize = computeSampleSize(info.size.width, info.size.height, MaxEditDimension)
            if (sampleSize > 1) decoder.setTargetSampleSize(sampleSize)
        }
    } else {
        @Suppress("DEPRECATION")
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        }
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, MaxEditDimension)
        }
        @Suppress("DEPRECATION")
        context.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
    }
    return bitmap.copy(Bitmap.Config.ARGB_8888, false)
}

/**
 * Draws [source] into whatever canvas/size is given, applying the same pan/zoom/rotate/flip —
 * used for both the live preview and the final exported bitmap, so what you see is exactly what
 * you get. [paint] is passed in (and reused across frames by the caller) rather than allocated
 * here, since this runs on every drag/pinch frame during editing.
 */
private fun drawEditedImage(
    canvas: Canvas,
    source: Bitmap,
    outputSizePx: Float,
    scale: Float,
    offsetFraction: Offset,
    rotationDegrees: Float,
    flipHorizontal: Boolean,
    paint: Paint
) {
    val center = outputSizePx / 2f
    canvas.save()
    canvas.translate(center + offsetFraction.x * outputSizePx, center + offsetFraction.y * outputSizePx)
    canvas.rotate(rotationDegrees)
    canvas.scale(if (flipHorizontal) -1f else 1f, 1f)
    val baseFillScale = outputSizePx / min(source.width, source.height).toFloat()
    val totalScale = baseFillScale * scale
    canvas.scale(totalScale, totalScale)
    canvas.translate(-source.width / 2f, -source.height / 2f)
    canvas.drawBitmap(source, 0f, 0f, paint)
    canvas.restore()
}

private fun renderFinalBitmap(
    source: Bitmap,
    outputSize: Int,
    scale: Float,
    offsetFraction: Offset,
    rotationDegrees: Float,
    flipHorizontal: Boolean,
    filter: ImageEditFilter
): Bitmap {
    val output = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
        colorFilter = filter.toColorFilter()
    }
    drawEditedImage(Canvas(output), source, outputSize.toFloat(), scale, offsetFraction, rotationDegrees, flipHorizontal, paint)
    return output
}

/** Crops to exactly the given fractional rectangle, then applies rotation/flip/filter — used
 * for the freeform (non-avatar) crop path. Scaled down afterward if still larger than needed. */
private fun renderFreeformCrop(
    source: Bitmap,
    cropRect: Rect,
    rotationDegrees: Float,
    flipHorizontal: Boolean,
    filter: ImageEditFilter,
    maxOutputDimension: Int = 1280
): Bitmap {
    val left = (cropRect.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (cropRect.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (cropRect.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (cropRect.bottom * source.height).toInt().coerceIn(top + 1, source.height)
    val cropped = Bitmap.createBitmap(source, left, top, right - left, bottom - top)

    val downscale = maxOutputDimension.toFloat() / maxOf(cropped.width, cropped.height)
    val working = if (downscale < 1f) {
        Bitmap.createScaledBitmap(cropped, (cropped.width * downscale).toInt().coerceAtLeast(1), (cropped.height * downscale).toInt().coerceAtLeast(1), true)
    } else {
        cropped
    }

    val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
    val swapDimensions = normalizedRotation == 90f || normalizedRotation == 270f
    val outputWidth = if (swapDimensions) working.height else working.width
    val outputHeight = if (swapDimensions) working.width else working.height

    val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        isDither = true
        colorFilter = filter.toColorFilter()
    }
    val canvas = Canvas(output)
    canvas.save()
    canvas.translate(outputWidth / 2f, outputHeight / 2f)
    canvas.rotate(normalizedRotation)
    canvas.scale(if (flipHorizontal) -1f else 1f, 1f)
    canvas.translate(-working.width / 2f, -working.height / 2f)
    canvas.drawBitmap(working, 0f, 0f, paint)
    canvas.restore()
    return output
}

/**
 * Full-screen crop/rotate/flip/filter editor. Pass [circularCrop] = true for avatars (round mask),
 * false for outgoing media (square mask). Rotation is fine-tuned via a draggable degree strip
 * ("sector scroller") in addition to a quick 90°-turn button, plus a horizontal-flip button.
 */
@Composable
fun ImageEditorDialog(
    imageUri: Uri,
    circularCrop: Boolean = true,
    onCancel: () -> Unit,
    onConfirm: (Bitmap) -> Unit
) {
    val context = LocalContext.current
    var sourceBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    LaunchedEffect(imageUri) {
        sourceBitmap = withContext(Dispatchers.IO) {
            try { loadBitmap(context, imageUri) } catch (e: Exception) { e.printStackTrace(); null }
        }
    }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetFraction by remember { mutableStateOf(Offset.Zero) }
    var quarterTurns by remember { mutableStateOf(0) }
    var fineRotation by remember { mutableFloatStateOf(0f) }
    var flipHorizontal by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ImageEditFilter.NONE) }
    var cropRect by remember { mutableStateOf(Rect(0.05f, 0.05f, 0.95f, 0.95f)) }
    val totalRotation = quarterTurns * 90f + fineRotation

    // Reused across every drag/pinch frame instead of allocating a fresh Paint (and a fresh
    // ColorMatrixColorFilter) each redraw — the filter object itself is only recreated when the
    // user actually changes the filter selection, not on every touch-move event.
    val previewPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true; isDither = true } }
    val previewColorFilter = remember(filter) { filter.toColorFilter() }
    previewPaint.colorFilter = previewColorFilter

    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
                    }
                    Text("Edit photo", color = Color.White, fontWeight = FontWeight.Bold)
                    IconButton(
                        enabled = sourceBitmap != null && !isSaving,
                        onClick = { isSaving = true }
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Check, contentDescription = "Done", tint = Color.White)
                        }
                    }
                }

                // Crop viewport
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    val bmp = sourceBitmap
                    if (bmp == null) {
                        CircularProgressIndicator(color = Color.White)
                    } else if (circularCrop) {
                        ComposeCanvas(
                            modifier = Modifier
                                .padding(24.dp)
                                // CircleShape clips whatever box it's given — it only reads as a
                                // circle if that box is actually square. fillMaxWidth() paired with
                                // a fixed height gave a wide rectangle, so the "circle" rendered as
                                // an oval.
                                .size(280.dp)
                                .clip(CircleShape)
                                .background(Color.DarkGray.copy(alpha = 0.3f))
                                .border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, pan, zoom, _ ->
                                        scale = (scale * zoom).coerceIn(1f, 6f)
                                        offsetFraction += Offset(pan.x / size.width, pan.y / size.height)
                                    }
                                }
                        ) {
                            drawIntoCanvas { drawScopeCanvas ->
                                drawEditedImage(
                                    canvas = drawScopeCanvas.nativeCanvas,
                                    source = bmp,
                                    outputSizePx = size.width,
                                    scale = scale,
                                    offsetFraction = offsetFraction,
                                    rotationDegrees = totalRotation,
                                    flipHorizontal = flipHorizontal,
                                    paint = previewPaint
                                )
                            }
                        }
                    } else {
                        // Real crop: a resizable rectangle over the whole image, not a fixed
                        // pan/zoom mask — drag any corner to reshape to any aspect ratio.
                        FreeformCropView(
                            bitmap = bmp,
                            cropRect = cropRect,
                            onCropRectChange = { cropRect = it },
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                // Rotation "sector scroller": drag left/right to fine-rotate ±45°. Only offered
                // for the fixed circular viewport — combining an arbitrary-angle "straighten"
                // with a freeform rectangle crop (which would then need re-expanding to avoid
                // exposing empty corners) is a separate, larger feature.
                if (circularCrop) {
                    RotationStrip(
                        fineRotation = fineRotation,
                        onFineRotationChange = { fineRotation = it.coerceIn(-45f, 45f) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Rotate / flip controls
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    IconButton(onClick = { quarterTurns = (quarterTurns + 1) % 4 }) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Rotate 90°", tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(24.dp))
                    IconButton(onClick = { flipHorizontal = !flipHorizontal }) {
                        Icon(Icons.Default.Flip, contentDescription = "Flip", tint = Color.White)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Style filters
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ImageEditFilter.values().forEach { f ->
                        val selected = f == filter
                        Surface(
                            onClick = { filter = f },
                            shape = RoundedCornerShape(14.dp),
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.12f),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = f.label,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    LaunchedEffect(isSaving) {
        if (!isSaving) return@LaunchedEffect
        val bmp = sourceBitmap ?: return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            if (circularCrop) {
                renderFinalBitmap(bmp, 640, scale, offsetFraction, totalRotation, flipHorizontal, filter)
            } else {
                renderFreeformCrop(bmp, cropRect, quarterTurns * 90f, flipHorizontal, filter)
            }
        }
        onConfirm(result)
    }
}

/** Horizontal draggable degree strip — a "sector" of tick marks the user scrolls to fine-rotate the image. */
@Composable
private fun RotationStrip(fineRotation: Float, onFineRotationChange: (Float) -> Unit) {
    val pxPerDegree = 6f
    // pointerInput(Unit) launches its gesture-detection coroutine exactly once and never
    // restarts, so a plain captured parameter would stay frozen at whatever it was on the
    // first composition. rememberUpdatedState keeps a live reference the drag handler can
    // read fresh on every callback instead.
    val latestRotation = rememberUpdatedState(fineRotation)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .pointerInput(Unit) {
                detectHorizontalDragGestures { _, dragAmount ->
                    onFineRotationChange(latestRotation.value - dragAmount / pxPerDegree)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxWidth().fillMaxSize()) {
            val centerX = size.width / 2f
            val step = 4 // degrees between minor ticks
            val range = 60
            var deg = -range
            while (deg <= range) {
                val x = centerX + (deg - fineRotation) * pxPerDegree
                if (x in 0f..size.width) {
                    val isMajor = deg % 15 == 0
                    val tickHeight = if (isMajor) 22f else 12f
                    val alpha = (1f - kotlin.math.abs(x - centerX) / (size.width / 2f)).coerceIn(0.15f, 1f)
                    drawLine(
                        color = Color.White.copy(alpha = alpha),
                        start = Offset(x, size.height / 2f - tickHeight / 2f),
                        end = Offset(x, size.height / 2f + tickHeight / 2f),
                        strokeWidth = if (isMajor) 2.5f else 1.5f
                    )
                }
                deg += step
            }
            // Center indicator
            drawLine(
                color = Color(0xFFFFC107),
                start = Offset(centerX, size.height / 2f - 16f),
                end = Offset(centerX, size.height / 2f + 16f),
                strokeWidth = 3f
            )
        }
        Text(
            text = "${fineRotation.toInt()}°",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}

private data class ImageDisplayBounds(val originX: Float, val originY: Float, val width: Float, val height: Float)

/** Where the bitmap actually lands when contain-fit into a [displayWidth] x [displayHeight] box. */
private fun computeImageDisplayBounds(displayWidth: Float, displayHeight: Float, bitmapAspect: Float): ImageDisplayBounds {
    if (displayWidth <= 0f || displayHeight <= 0f) return ImageDisplayBounds(0f, 0f, 0f, 0f)
    val displayAspect = displayWidth / displayHeight
    return if (bitmapAspect > displayAspect) {
        val w = displayWidth
        val h = w / bitmapAspect
        ImageDisplayBounds(0f, (displayHeight - h) / 2f, w, h)
    } else {
        val h = displayHeight
        val w = h * bitmapAspect
        ImageDisplayBounds((displayWidth - w) / 2f, 0f, w, h)
    }
}

private enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, BODY, NONE }

/**
 * The real crop tool: the whole image is shown contain-fit, with a resizable rectangle overlaid
 * on top. Drag a corner to reshape it to any aspect ratio; drag inside it to reposition the
 * whole thing. [cropRect] is stored as fractions (0..1) of the *bitmap's own* dimensions, so the
 * mapping back to source pixels at export time is a direct multiply — no display-size bookkeeping
 * needed there.
 */
@Composable
private fun FreeformCropView(
    bitmap: Bitmap,
    cropRect: Rect,
    onCropRectChange: (Rect) -> Unit,
    modifier: Modifier = Modifier
) {
    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.toFloat()
    var displaySize by remember { mutableStateOf(Size.Zero) }
    // The drag-handler coroutine below is launched once per (displaySize, bitmapAspect) and
    // never restarts on every recomposition, so it would otherwise keep reading whatever
    // cropRect was current the moment it launched — rememberUpdatedState keeps that read live.
    val latestCropRect = rememberUpdatedState(cropRect)
    val minFraction = 0.12f
    val imagePaint = remember { Paint().apply { isFilterBitmap = true } }
    val handleAccentColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(360.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .onSizeChanged { displaySize = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(displaySize, bitmapAspect) {
                var activeHandle = CropHandle.NONE
                detectDragGestures(
                    onDragStart = { pos ->
                        val bounds = computeImageDisplayBounds(displaySize.width, displaySize.height, bitmapAspect)
                        val r = latestCropRect.value
                        val rectPx = Rect(
                            bounds.originX + r.left * bounds.width,
                            bounds.originY + r.top * bounds.height,
                            bounds.originX + r.right * bounds.width,
                            bounds.originY + r.bottom * bounds.height
                        )
                        val handleRadius = 44f
                        activeHandle = when {
                            (pos - rectPx.topLeft).getDistance() < handleRadius -> CropHandle.TOP_LEFT
                            (pos - rectPx.topRight).getDistance() < handleRadius -> CropHandle.TOP_RIGHT
                            (pos - rectPx.bottomLeft).getDistance() < handleRadius -> CropHandle.BOTTOM_LEFT
                            (pos - rectPx.bottomRight).getDistance() < handleRadius -> CropHandle.BOTTOM_RIGHT
                            rectPx.contains(pos) -> CropHandle.BODY
                            else -> CropHandle.NONE
                        }
                    },
                    onDragEnd = { activeHandle = CropHandle.NONE },
                    onDragCancel = { activeHandle = CropHandle.NONE }
                ) { change, dragAmount ->
                    if (activeHandle == CropHandle.NONE) return@detectDragGestures
                    change.consume()
                    val bounds = computeImageDisplayBounds(displaySize.width, displaySize.height, bitmapAspect)
                    if (bounds.width <= 0f || bounds.height <= 0f) return@detectDragGestures
                    val dxFrac = dragAmount.x / bounds.width
                    val dyFrac = dragAmount.y / bounds.height
                    val r = latestCropRect.value
                    var l = r.left
                    var t = r.top
                    var right = r.right
                    var b = r.bottom
                    when (activeHandle) {
                        CropHandle.TOP_LEFT -> {
                            l = (l + dxFrac).coerceIn(0f, right - minFraction)
                            t = (t + dyFrac).coerceIn(0f, b - minFraction)
                        }
                        CropHandle.TOP_RIGHT -> {
                            right = (right + dxFrac).coerceIn(l + minFraction, 1f)
                            t = (t + dyFrac).coerceIn(0f, b - minFraction)
                        }
                        CropHandle.BOTTOM_LEFT -> {
                            l = (l + dxFrac).coerceIn(0f, right - minFraction)
                            b = (b + dyFrac).coerceIn(t + minFraction, 1f)
                        }
                        CropHandle.BOTTOM_RIGHT -> {
                            right = (right + dxFrac).coerceIn(l + minFraction, 1f)
                            b = (b + dyFrac).coerceIn(t + minFraction, 1f)
                        }
                        CropHandle.BODY -> {
                            val w = right - l
                            val h = b - t
                            l = (l + dxFrac).coerceIn(0f, 1f - w); right = l + w
                            t = (t + dyFrac).coerceIn(0f, 1f - h); b = t + h
                        }
                        CropHandle.NONE -> {}
                    }
                    onCropRectChange(Rect(l, t, right, b))
                }
            }
    ) {
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val bounds = computeImageDisplayBounds(size.width, size.height, bitmapAspect)
            drawIntoCanvas { c ->
                val destRect = android.graphics.RectF(
                    bounds.originX, bounds.originY,
                    bounds.originX + bounds.width, bounds.originY + bounds.height
                )
                c.nativeCanvas.drawBitmap(bitmap, null, destRect, imagePaint)
            }
            val rectPx = Rect(
                bounds.originX + cropRect.left * bounds.width,
                bounds.originY + cropRect.top * bounds.height,
                bounds.originX + cropRect.right * bounds.width,
                bounds.originY + cropRect.bottom * bounds.height
            )
            val scrim = Color.Black.copy(alpha = 0.6f)
            drawRect(scrim, topLeft = Offset(0f, 0f), size = Size(size.width, rectPx.top))
            drawRect(scrim, topLeft = Offset(0f, rectPx.bottom), size = Size(size.width, size.height - rectPx.bottom))
            drawRect(scrim, topLeft = Offset(0f, rectPx.top), size = Size(rectPx.left, rectPx.height))
            drawRect(scrim, topLeft = Offset(rectPx.right, rectPx.top), size = Size(size.width - rectPx.right, rectPx.height))
            drawRect(Color.White, topLeft = rectPx.topLeft, size = Size(rectPx.width, rectPx.height), style = Stroke(width = 3f))
            listOf(rectPx.topLeft, rectPx.topRight, rectPx.bottomLeft, rectPx.bottomRight).forEach { corner ->
                drawCircle(Color.White, radius = 10f, center = corner)
                drawCircle(handleAccentColor, radius = 6f, center = corner)
            }
        }
    }
}
