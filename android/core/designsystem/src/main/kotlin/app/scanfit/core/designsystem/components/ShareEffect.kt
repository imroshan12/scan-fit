package app.scanfit.core.designsystem.components

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri

/**
 * Opens the system share sheet once for a staged JPEG (ALGORITHMS 1.6.2), granting read access to the chosen app only
 * for this intent, then reports it handled. The user picks where the file goes; the app sends nothing itself.
 */
@Composable
fun ShareEffect(
    uri: String?,
    onHandled: () -> Unit,
) {
    val context = LocalContext.current
    val handled by rememberUpdatedState(onHandled)
    LaunchedEffect(uri) {
        val stream = uri?.toUri() ?: return@LaunchedEffect
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("image/jpeg")
                .putExtra(Intent.EXTRA_STREAM, stream)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(null, stream)
        try {
            context.startActivity(Intent.createChooser(send, null))
        } catch (_: ActivityNotFoundException) {
            // No app can receive a JPEG: nothing to do, the file stays in the review.
        }
        handled()
    }
}
