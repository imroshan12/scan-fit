package app.scanfit.feature.kit

import app.scanfit.core.imaging.AndroidImageDecoder
import app.scanfit.core.imaging.Raster
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject

/** Decodes Kit originals for thumbnails and previews, behind an interface so the view models run on the JVM. */
interface KitImages {
    /** ALGORITHMS 1.1: downsampled on decode to at most [maxLongSide]; `null` when the bytes are not an image. */
    fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster?
}

class AndroidKitImages
@Inject
constructor() : KitImages {
    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = AndroidImageDecoder.decode(bytes, maxLongSide)
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class KitModule {
    @Binds
    abstract fun kitImages(images: AndroidKitImages): KitImages
}
