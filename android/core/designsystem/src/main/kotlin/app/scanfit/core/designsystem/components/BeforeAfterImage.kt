package app.scanfit.core.designsystem.components

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.scanfit.core.designsystem.R
import app.scanfit.core.designsystem.theme.ScanFitRadius
import app.scanfit.core.designsystem.theme.ScanFitSpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun BeforeAfterImage(
    before: ImageBitmap?,
    after: ByteArray?,
    modifier: Modifier = Modifier,
) {
    var afterSelected by remember(before) { mutableStateOf(true) }
    var held by remember(before) { mutableStateOf(false) }
    val decoded by key(after) {
        produceState<ImageBitmap?>(null, after) {
            value = after?.let { bytes ->
                withContext(Dispatchers.Default) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                }
            }
        }
    }
    val canCompare = before != null && after != null
    val showBefore = canCompare && (!afterSelected || held)
    val bitmap = if (showBefore) before else decoded
    val beforeLabel = stringResource(R.string.review_before)
    val afterLabel = stringResource(R.string.review_after)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ScanFitSpacing.sm)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(ScanFitRadius.card))
                .pointerInput(canCompare, afterSelected) {
                    if (canCompare && afterSelected) {
                        detectTapGestures(onPress = {
                            held = true
                            try {
                                tryAwaitRelease()
                            } finally {
                                held = false
                            }
                        })
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            bitmap?.let {
                Image(
                    it,
                    contentDescription = if (showBefore) beforeLabel else afterLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (canCompare) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = !afterSelected,
                    onClick = { afterSelected = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                ) { Text(beforeLabel) }
                SegmentedButton(
                    selected = afterSelected,
                    onClick = { afterSelected = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                ) { Text(afterLabel) }
            }
        }
    }
}

private val PREVIEW_HEIGHT = 280.dp
