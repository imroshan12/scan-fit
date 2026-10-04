package app.scanfit.feature.flowink

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.platform.LocalDensity
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
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/** UI_UX §3 Capture/crop for ink documents: a free rectangle (§9.5), Rotate and Reset. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InkCropContent(
    state: InkUiState.Crop,
    actions: InkActions,
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
        FreeCropEditor(
            image = state.image,
            rect = state.rect,
            onMove = actions.onMove,
            onResize = actions.onResize,
            description = stringResource(R.string.ink_crop_hint),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        Text(
            stringResource(R.string.ink_crop_hint),
            style = ScanFitType.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
            TextButton(onClick = actions.onRotate) { Text(stringResource(R.string.photo_rotate)) }
            TextButton(onClick = actions.onResetCrop) { Text(stringResource(R.string.photo_reset)) }
        }
    }
}

/** What a drag does, decided where it starts: a corner resizes from that corner, inside the frame moves it. */
private sealed interface DragMode {
    data class Resize(
        val corner: CropCorner,
    ) : DragMode

    data object Move : DragMode

    data object None : DragMode
}

/**
 * The image with a free crop frame and corner handles. Movement is sent in raster pixels; fractions are carried over
 * between events so slow drags on a small image still move.
 */
@Composable
private fun FreeCropEditor(
    image: Raster,
    rect: CropRect,
    onMove: (Double, Double) -> Unit,
    onResize: (CropCorner, Double, Double) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(null, image) {
        value = withContext(Dispatchers.Default) { image.toBitmap().asImageBitmap() }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val currentRect by rememberUpdatedState(rect)
    val move by rememberUpdatedState(onMove)
    val resize by rememberUpdatedState(onResize)
    val handleRadius = with(LocalDensity.current) { HANDLE_TOUCH.toPx() }
    val scrim = Color.Black.copy(alpha = SCRIM_ALPHA)
    val frameColor = MaterialTheme.colorScheme.primary
    Canvas(
        modifier =
        modifier
            .semantics { contentDescription = description }
            .onSizeChanged { size = it }
            .pointerInput(image) {
                var mode: DragMode = DragMode.None
                var pending = Offset.Zero
                detectDragGestures(
                    onDragStart = { start ->
                        pending = Offset.Zero
                        mode = hitTest(start, currentRect, image, size, handleRadius)
                    },
                ) { change, amount ->
                    change.consume()
                    val scale = fitScale(image, size)
                    if (scale <= 0f) return@detectDragGestures
                    pending += amount / scale
                    val dx = pending.x.toInt()
                    val dy = pending.y.toInt()
                    if (dx == 0 && dy == 0) return@detectDragGestures
                    pending -= Offset(dx.toFloat(), dy.toFloat())
                    when (val m = mode) {
                        is DragMode.Resize -> resize(m.corner, dx.toDouble(), dy.toDouble())
                        DragMode.Move -> move(dx.toDouble(), dy.toDouble())
                        DragMode.None -> Unit
                    }
                }
            },
    ) {
        val s = min(this.size.width / image.width, this.size.height / image.height)
        val w = image.width * s
        val h = image.height * s
        val ox = (this.size.width - w) / 2
        val oy = (this.size.height - h) / 2
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
        // Brand colour on a white outline: visible on white paper and on dark backgrounds alike.
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(FRAME_OUTLINE.toPx()))
        drawRect(frameColor, Offset(l, t), Size(r - l, b - t), style = Stroke(FRAME_LINE.toPx()))
        val handle = HANDLE_DRAWN.toPx()
        for (corner in listOf(Offset(l, t), Offset(r, t), Offset(l, b), Offset(r, b))) {
            drawCircle(Color.White, radius = handle + FRAME_LINE.toPx(), center = corner)
            drawCircle(frameColor, radius = handle, center = corner)
        }
    }
}

/** View pixels per raster pixel: how the image is scaled to fit the editor (0 before layout). */
private fun fitScale(
    image: Raster,
    size: IntSize,
): Float = if (size.width == 0 || size.height == 0) {
    0f
} else {
    min(size.width.toFloat() / image.width, size.height.toFloat() / image.height)
}

private fun hitTest(
    point: Offset,
    rect: CropRect,
    image: Raster,
    size: IntSize,
    radius: Float,
): DragMode {
    val s = fitScale(image, size)
    if (s <= 0f) return DragMode.None
    val ox = (size.width - image.width * s) / 2
    val oy = (size.height - image.height * s) / 2
    val l = ox + rect.x * s
    val t = oy + rect.y * s
    val r = l + rect.w * s
    val b = t + rect.h * s
    val corners =
        mapOf(
            CropCorner.TOP_LEFT to Offset(l, t),
            CropCorner.TOP_RIGHT to Offset(r, t),
            CropCorner.BOTTOM_LEFT to Offset(l, b),
            CropCorner.BOTTOM_RIGHT to Offset(r, b),
        )
    val nearest = corners.minByOrNull { (_, c) -> c.distanceTo(point) }
    return when {
        nearest != null && nearest.value.distanceTo(point) <= radius -> DragMode.Resize(nearest.key)
        point.x in l..r && point.y in t..b -> DragMode.Move
        else -> DragMode.None
    }
}

private fun Offset.distanceTo(other: Offset): Float = hypot(x - other.x, y - other.y)

private const val SCRIM_ALPHA = 0.55f
private val HANDLE_TOUCH = 32.dp
private val HANDLE_DRAWN = 7.dp
private val FRAME_LINE = 2.dp
private val FRAME_OUTLINE = 4.dp
