package app.scanfit.core.vision

import app.scanfit.core.imaging.PersonMask
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.toBitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit face detection with the bundled model (ALGORITHMS 2.1): runs on the device, nothing is uploaded, and it works
 * without Play services. Boxes come back in raster pixels.
 */
class MlKitFaceDetector : FaceDetector {
    private val client =
        FaceDetection.getClient(
            FaceDetectorOptions
                .Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .build(),
        )

    override suspend fun detect(raster: Raster): List<FaceBox> {
        val bitmap = raster.toBitmap()
        return try {
            client.process(InputImage.fromBitmap(bitmap, 0)).await().map {
                val b = it.boundingBox
                FaceBox(b.left.toDouble(), b.top.toDouble(), b.width().toDouble(), b.height().toDouble())
            }
        } finally {
            bitmap.recycle()
        }
    }
}

/**
 * ML Kit selfie segmentation (ALGORITHMS 2.3) on the cropped photo. Returns `null` when the model cannot run, so the
 * "White background" toggle can say it is unavailable instead of failing the flow.
 */
class MlKitPersonSegmenter : PersonSegmenter {
    private val client =
        Segmentation.getClient(
            SelfieSegmenterOptions
                .Builder()
                .setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE)
                .build(),
        )

    override suspend fun mask(raster: Raster): ByteArray? {
        val bitmap = raster.toBitmap()
        return try {
            val mask = client.process(InputImage.fromBitmap(bitmap, 0)).await()
            val buffer = mask.buffer.asFloatBuffer()
            val confidence = FloatArray(mask.width * mask.height).also { buffer.get(it) }
            PersonMask.fromConfidence(confidence, mask.width, mask.height, raster.width, raster.height)
        } catch (_: MlKitException) {
            null
        } finally {
            bitmap.recycle()
        }
    }
}

/** A Play services [Task] as a suspending call (avoids a dependency for three lines). */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
