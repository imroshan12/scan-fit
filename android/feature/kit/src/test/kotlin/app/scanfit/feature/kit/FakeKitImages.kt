package app.scanfit.feature.kit

import app.scanfit.core.imaging.Raster

/** Decodes nothing: answers a raster of the asked size, and remembers the cap. */
class FakeKitImages : KitImages {
    val caps = mutableListOf<Int>()

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster {
        caps += maxLongSide
        return Raster.white(maxLongSide, maxLongSide)
    }
}
