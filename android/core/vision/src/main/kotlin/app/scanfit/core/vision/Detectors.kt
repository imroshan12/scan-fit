package app.scanfit.core.vision

import app.scanfit.core.imaging.Raster

/** A detected face box in raster pixels (the same type the pure framing math uses). */
typealias FaceBox = app.scanfit.core.imaging.Face

/** Face detection behind an interface so the pipeline is testable with fakes ([MlKitFaceDetector] is the real one). */
fun interface FaceDetector {
    suspend fun detect(raster: Raster): List<FaceBox>
}

/**
 * Person segmentation: one alpha value per pixel (0 = background, 255 = person), row-major and
 * `raster.width * raster.height` long, or null when the model is unavailable ([MlKitPersonSegmenter]).
 */
fun interface PersonSegmenter {
    suspend fun mask(raster: Raster): ByteArray?
}

/** The outcome of the face-count rule (ALGORITHMS 9.6): never silently picks one of several faces. */
sealed interface FaceCheck {
    data class Single(val face: FaceBox) : FaceCheck

    data object NoFace : FaceCheck

    data class Several(val count: Int) : FaceCheck

    companion object {
        fun of(faces: List<FaceBox>): FaceCheck = when (faces.size) {
            0 -> NoFace
            1 -> Single(faces.single())
            else -> Several(faces.size)
        }
    }
}
