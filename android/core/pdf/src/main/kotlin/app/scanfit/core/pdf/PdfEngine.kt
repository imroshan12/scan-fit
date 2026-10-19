package app.scanfit.core.pdf

import app.scanfit.core.imaging.CropAdjust
import app.scanfit.core.imaging.JpegEncoder
import app.scanfit.core.imaging.JpegPatcher
import app.scanfit.core.imaging.PageCleanup
import app.scanfit.core.imaging.PatchResult
import app.scanfit.core.imaging.Raster
import app.scanfit.core.imaging.Resampler
import app.scanfit.core.imaging.scaledTo
import app.scanfit.core.inspect.Inspector
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Makes every PDF the app writes (ALGORITHMS 6): prepares pictures once, measures the PDF at each DPI of the ladder and
 * each grid quality with one page raster in memory at a time, then writes the chosen DPI and quality (padded up to a
 * minimum when needed). Platform pieces are injected, so the JVM tests run it with real encoders and a fixture
 * renderer.
 */
class PdfEngine(
    private val opener: PdfOpener,
    private val decoder: PictureDecoder,
    private val encoder: JpegEncoder,
) {
    private class Prepared(
        val jpeg: ByteArray,
        val width: Int,
        val height: Int,
    )

    private sealed interface Page {
        val size: PdfSize

        class Pictures(
            override val size: PdfSize,
            val placements: List<Placement>,
            val pictures: List<Prepared>,
        ) : Page

        class Rendered(
            override val size: PdfSize,
            val document: PdfPages,
            val index: Int,
        ) : Page
    }

    private class Failure(
        val error: PdfError,
    ) : Exception()

    suspend fun make(
        job: PdfJob,
        progress: (PdfProgress) -> Unit = {},
    ): PdfOutcome {
        val idCardSides = job.sources.size == 2 && job.sources.all { it is PdfSource.Image }
        if (job.sources.isEmpty() || (job.tool == PdfTool.ID_CARD && !idCardSides)) {
            return PdfOutcome.Failed(PdfError.NO_PAGES)
        }
        val documents = job.sources.filterIsInstance<PdfSource.Document>()
        if (documents.any { Inspector.inspect(it.bytes).pdfEncrypted }) return PdfOutcome.Failed(PdfError.ENCRYPTED)
        val opened = mutableMapOf<PdfSource.Document, PdfPages>()
        return try {
            for (document in documents) {
                val pages = opener.open(document.bytes)?.also { opened[document] = it }
                if (pages == null || pages.pageCount < 1) return PdfOutcome.Failed(PdfError.UNREADABLE)
            }
            make(job, opened, progress)
        } catch (failure: Failure) {
            PdfOutcome.Failed(failure.error)
        } finally {
            opened.values.forEach { it.close() }
        }
    }

    private suspend fun make(
        job: PdfJob,
        opened: Map<PdfSource.Document, PdfPages>,
        progress: (PdfProgress) -> Unit,
    ): PdfOutcome {
        val pictures = job.sources.filterIsInstance<PdfSource.Image>()
        val pictureCount = if (job.tool == PdfTool.ID_CARD) 1 else pictures.size
        val window = PdfWindow.of(job.sizeKb)
        val error =
            when {
                pictureCount + opened.values.sumOf { it.pageCount } > PdfFit.MAX_PAGES -> PdfError.TOO_MANY_PAGES
                window == null -> PdfError.UNKNOWN_LIMIT
                else -> null
            }
        if (error != null || window == null) return PdfOutcome.Failed(error ?: PdfError.UNKNOWN_LIMIT)
        unchanged(job, opened, window)?.let { return PdfOutcome.Made(it) }

        val prepared = mutableMapOf<PdfSource.Image, Prepared>()
        pictures.forEachIndexed { i, picture ->
            progress(PdfProgress.Preparing(i, pictures.size))
            prepared[picture] = prepare(picture, job.options)
        }
        val pages = pages(job, opened, prepared)
        for (dpi in PdfLayout.DPI_LADDER) {
            val sizes = measure(pages, dpi, window, job.options, progress) ?: continue
            val choice = PdfFit.choose(window, dpi, sizes) ?: continue
            return PdfOutcome.Made(write(pages, choice, window, job.options, progress))
        }
        throw Failure(PdfError.CANNOT_REACH)
    }

    /** ALGORITHMS 6.1: one PDF already inside the window is returned byte for byte. */
    private fun unchanged(
        job: PdfJob,
        opened: Map<PdfSource.Document, PdfPages>,
        window: PdfWindow,
    ): PdfMade? {
        val only = job.sources.singleOrNull() as? PdfSource.Document ?: return null
        val kb = only.bytes.size / KB
        if (kb < window.min || kb > window.max) return null
        val pages = opened.getValue(only)
        val size = pages.pageSize(0)
        val scale = PREVIEW_LONG_SIDE / maxOf(size.width, size.height)
        val preview =
            pages.render(0, maxOf(1, roundHalfUp(size.width * scale)), maxOf(1, roundHalfUp(size.height * scale)))
        return PdfMade(only.bytes, pages.pageCount, null, null, PdfStrategy.UNCHANGED, preview)
    }

    /** ALGORITHMS 6.2: decode, turn, crop, then cleanup or grayscale; kept as a high-quality JPEG for every DPI. */
    private suspend fun prepare(
        picture: PdfSource.Image,
        options: PdfOptions,
    ): Prepared {
        currentCoroutineContext().ensureActive()
        val cap = if (options.cleanup) CLEANUP_CAP else PICTURE_CAP
        var raster = decoder.decode(picture.bytes, cap) ?: throw Failure(PdfError.UNREADABLE)
        repeat(Math.floorMod(picture.quarterTurns, QUARTER_TURNS)) { raster = CropAdjust.rotateClockwise(raster) }
        val crop = picture.crop
        if (crop != null) {
            val base = picture.cropBase ?: PixelSize(raster.width, raster.height)
            val scaled = crop.scaledTo(base.width, base.height, raster)
            val x = scaled.x.coerceIn(0, raster.width - 1)
            val y = scaled.y.coerceIn(0, raster.height - 1)
            raster = raster.crop(x, y, minOf(scaled.w, raster.width - x), minOf(scaled.h, raster.height - y))
        }
        raster =
            when {
                options.cleanup -> PageCleanup.clean(raster, options.grayscale)
                options.grayscale -> PageCleanup.grayscale(raster)
                else -> raster
            }
        return Prepared(encoder.encode(raster, CACHE_QUALITY), raster.width, raster.height)
    }

    private fun pages(
        job: PdfJob,
        opened: Map<PdfSource.Document, PdfPages>,
        prepared: Map<PdfSource.Image, Prepared>,
    ): List<Page> {
        if (job.tool == PdfTool.ID_CARD) {
            val (front, back) = job.sources.map { prepared.getValue(it as PdfSource.Image) }
            val (size, placements) =
                PdfLayout.idCardPage(PixelSize(front.width, front.height), PixelSize(back.width, back.height))
            return listOf(Page.Pictures(size, placements, listOf(front, back)))
        }
        return job.sources.flatMap { source ->
            when (source) {
                is PdfSource.Image -> {
                    val picture = prepared.getValue(source)
                    val (size, placement) = PdfLayout.imagePage(picture.width, picture.height)
                    listOf(Page.Pictures(size, listOf(placement), listOf(picture)))
                }

                is PdfSource.Document -> {
                    val document = opened.getValue(source)
                    (0 until document.pageCount).map { Page.Rendered(document.pageSize(it), document, it) }
                }
            }
        }
    }

    /** The page's images at [dpi], with where each goes. */
    private fun rasters(
        page: Page,
        dpi: Int,
        options: PdfOptions,
    ): List<Pair<Raster, PlacedPixels>> = when (page) {
        is Page.Pictures ->
            page.pictures.zip(page.placements).map { (picture, placement) ->
                val px = PdfLayout.pixels(picture.width, picture.height, placement.scale, dpi)
                val decoded =
                    decoder.decode(picture.jpeg, maxOf(picture.width, picture.height))
                        ?: throw Failure(PdfError.FAILED)
                Resampler.resize(decoded, px.width, px.height) to PlacedPixels(px.width, px.height, placement.rect)
            }

        is Page.Rendered -> {
            val px = PdfLayout.pdfPagePixels(page.size, dpi)
            val rendered = page.document.render(page.index, px.width, px.height)
            val raster = if (options.grayscale) PageCleanup.grayscale(rendered) else rendered
            listOf(raster to PlacedPixels(px.width, px.height, PdfRect(0.0, 0.0, page.size.width, page.size.height)))
        }
    }

    private fun jpeg(
        raster: Raster,
        quality: Int,
        dpi: Int,
    ): ByteArray = (JpegPatcher.patch(encoder.encode(raster, quality), dpi) as? PatchResult.Ok)?.bytes
        ?: throw Failure(PdfError.FAILED)

    /**
     * The whole PDF's size at each grid quality, or `null` as soon as the lowest-quality images alone exceed `goal.hi`
     * (ALGORITHMS 9.10 allows stopping early).
     */
    private suspend fun measure(
        pages: List<Page>,
        dpi: Int,
        window: PdfWindow,
        options: PdfOptions,
        progress: (PdfProgress) -> Unit,
    ): List<Long>? {
        val grid = PdfFit.grid(dpi)
        val lengths = List(grid.size) { mutableListOf<List<Int>>() }
        val geometry = mutableListOf<PageGeometry>()
        var lowest = 0L
        pages.forEachIndexed { p, page ->
            currentCoroutineContext().ensureActive()
            progress(PdfProgress.Fitting(p, pages.size))
            val images = rasters(page, dpi, options)
            val perQuality = List(grid.size) { IntArray(images.size) }
            images.forEachIndexed { i, (raster, _) ->
                perQuality[grid.lastIndex][i] = jpeg(raster, grid.last(), dpi).size
            }
            lowest += perQuality[grid.lastIndex].sum()
            if (lowest > window.hiBytes) return null
            images.forEachIndexed { i, (raster, _) ->
                for (q in 0 until grid.lastIndex) perQuality[q][i] = jpeg(raster, grid[q], dpi).size
            }
            geometry += PageGeometry(page.size, images.map { it.second })
            perQuality.forEachIndexed { q, sizes -> lengths[q] += sizes.toList() }
        }
        return lengths.map { PdfWriter.size(geometry, it) }
    }

    private suspend fun write(
        pages: List<Page>,
        choice: PdfChoice,
        window: PdfWindow,
        options: PdfOptions,
        progress: (PdfProgress) -> Unit,
    ): PdfMade {
        var preview: Raster? = null
        val written =
            pages.mapIndexed { p, page ->
                currentCoroutineContext().ensureActive()
                progress(PdfProgress.Fitting(p, pages.size))
                val images = rasters(page, choice.dpi, options)
                if (p == 0) preview = PdfPreview.compose(page.size, images, PREVIEW_LONG_SIDE)
                WriterPage(
                    page.size,
                    images.map { (raster, at) ->
                        WriterImage(jpeg(raster, choice.quality, choice.dpi), at.width, at.height, at.rect)
                    },
                )
            }
        val bytes = choice.padTo?.let { PdfWriter.writePadded(written, it) } ?: PdfWriter.write(written)
        val kb = bytes.size / KB
        if (kb > window.max || kb < window.min) throw Failure(PdfError.FAILED)
        return PdfMade(bytes, pages.size, choice.dpi, choice.quality, choice.strategy, preview)
    }

    companion object {
        const val PREVIEW_LONG_SIDE = 800.0
        private const val KB = 1024.0
        private const val PICTURE_CAP = 2400
        private const val CLEANUP_CAP = 1600
        private const val CACHE_QUALITY = 95
        private const val QUARTER_TURNS = 4
    }
}
