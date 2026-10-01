package app.scanfit.core.testing

import app.scanfit.core.imaging.Raster
import app.scanfit.core.vision.FaceBox
import app.scanfit.core.vision.FaceDetector
import app.scanfit.core.vision.PersonSegmenter

/** Test double for [FaceDetector]: returns the configured faces and records how often it was called. */
class FakeFaceDetector(var faces: List<FaceBox> = emptyList()) : FaceDetector {
    var calls = 0
        private set

    override suspend fun detect(raster: Raster): List<FaceBox> {
        calls++
        return faces
    }
}

/** Test double for [PersonSegmenter]: a fixed mask, or `null` to simulate the model being unavailable. */
class FakePersonSegmenter(var mask: ByteArray? = null) : PersonSegmenter {
    override suspend fun mask(raster: Raster): ByteArray? = mask
}
