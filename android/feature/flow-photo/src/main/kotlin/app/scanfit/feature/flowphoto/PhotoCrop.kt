package app.scanfit.feature.flowphoto

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import app.scanfit.core.designsystem.theme.ScanFitType
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** UI_UX §3 Capture/crop: the aspect-locked crop, its tools, and the face-check messages. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CropContent(
    state: PhotoUiState.Crop,
    actions: PhotoActions,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ScanFitSpacing.screenMargin),
        verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.md),
    ) {
        Text(
            stringResource(R.string.photo_crop_heading),
            style = ScanFitType.headline,
            modifier = Modifier.semantics { heading() },
        )
        CropEditor(
            image = state.image,
            rect = state.rect,
            onMove = actions.onMove,
            onZoom = actions.onZoom,
            description = stringResource(R.string.photo_crop_hint),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            stringResource(R.string.photo_crop_hint),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Buttons as well as gestures: pinch is not available to everyone (UI_UX §6).
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            TextButton(onClick = { actions.onZoom(1 / ZOOM_STEP) }) { Text(stringResource(R.string.photo_zoom_out)) }
            TextButton(onClick = { actions.onZoom(ZOOM_STEP) }) { Text(stringResource(R.string.photo_zoom_in)) }
            TextButton(onClick = actions.onRotate) { Text(stringResource(R.string.photo_rotate)) }
            TextButton(onClick = actions.onResetCrop) { Text(stringResource(R.string.photo_reset)) }
        }
        state.problem?.let {
            val text =
                when (it) {
                    CropProblem.NO_FACE -> R.string.error_face_none
                    CropProblem.SEVERAL_FACES -> R.string.error_face_multiple
                    CropProblem.FAILED -> R.string.error_generic
                }
            Notice(stringResource(text), NoticeKind.ERROR)
        }
        if (state.tight && state.problem == null) {
            Notice(stringResource(R.string.photo_crop_tight), NoticeKind.WARNING)
        }
    }
}

/**
 * The image with an aspect-locked crop frame: drag moves the frame, pinch zooms it. Movement is sent in raster pixels;
 * fractions are carried over between events so slow drags on a small image still move.
 */
@Composable
private fun CropEditor(
    image: Raster,
    rect: CropRect,
    onMove: (Double, Double) -> Unit,
    onZoom: (Double) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap = rasterBitmap(image)
    var scale by remember { mutableFloatStateOf(1f) }
    val currentScale by rememberUpdatedState(scale)
    val move by rememberUpdatedState(onMove)
    val zoom by rememberUpdatedState(onZoom)
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    Canvas(
        modifier =
        modifier
            .semantics { contentDescription = description }
            .onSizeChanged { scale = min(it.width.toFloat() / image.width, it.height.toFloat() / image.height) }
            .pointerInput(image) {
                var pending = Offset.Zero
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    if (gestureZoom != 1f) zoom(gestureZoom.toDouble())
                    pending += pan / currentScale
                    val dx = pending.x.toInt()
                    val dy = pending.y.toInt()
                    if (dx != 0 || dy != 0) {
                        move(dx.toDouble(), dy.toDouble())
                        pending -= Offset(dx.toFloat(), dy.toFloat())
                    }
                }
            },
    ) {
        val s = min(size.width / image.width, size.height / image.height)
        val w = image.width * s
        val h = image.height * s
        val ox = (size.width - w) / 2
        val oy = (size.height - h) / 2
        bitmap?.let {
            drawImage(
                it,
                dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            )
        }
        val l = ox + rect.x * s
        val t = oy + rect.y * s
        val r = l + rect.w * s
        val b = t + rect.h * s
        drawRect(scrim, Offset(ox, oy), Size(w, t - oy))
        drawRect(scrim, Offset(ox, b), Size(w, oy + h - b))
        drawRect(scrim, Offset(ox, t), Size(l - ox, b - t))
        drawRect(scrim, Offset(r, t), Size(ox + w - r, b - t))
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(2.dp.toPx()))
    }
}

/** The raster as an on-screen bitmap, converted off the main thread. */
@Composable
private fun rasterBitmap(image: Raster): ImageBitmap? {
    val bitmap by produceState<ImageBitmap?>(null, image) {
        value = withContext(Dispatchers.Default) { image.toBitmap().asImageBitmap() }
    }
    return bitmap
}

private const val ZOOM_STEP = 1.25
private const val SCRIM_ALPHA = 0.55f
