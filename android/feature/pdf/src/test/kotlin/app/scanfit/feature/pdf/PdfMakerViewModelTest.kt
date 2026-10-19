package app.scanfit.feature.pdf

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.DetectedFormat
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.model.DocType
import app.scanfit.core.pdf.PdfError
import app.scanfit.core.pdf.PdfJob
import app.scanfit.core.pdf.PdfMade
import app.scanfit.core.pdf.PdfOutcome
import app.scanfit.core.pdf.PdfProgress
import app.scanfit.core.pdf.PdfSource
import app.scanfit.core.pdf.PdfStrategy
import app.scanfit.core.pdf.PdfTool
import app.scanfit.core.pdf.PixelSize
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.testing.TestPdf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Test double for [PdfMakerTools]: files by URI, PDFs recognised by their bytes, a configurable engine. */
class FakePdfMakerTools : PdfMakerTools {
    val files = mutableMapOf<String, ByteArray>()
    val jobs = mutableListOf<PdfJob>()
    var discarded = 0
    var captures = 0

    /** When set, [make] waits for it (to observe the making state). */
    var gate: CompletableDeferred<Unit>? = null
    var outcome: (PdfJob) -> PdfOutcome = { job ->
        val pages = if (job.tool == PdfTool.ID_CARD) 1 else job.sources.size
        PdfOutcome.Made(PdfMade(TestPdf.make(pages, 90 * 1024), pages, 150, 75, PdfStrategy.LOWER_DPI, Raster.white(4, 6)))
    }

    override suspend fun read(uri: String): ByteArray? = files[uri]

    override fun newCaptureUri(): String = "content://capture/${++captures}"

    override fun discardCaptures() {
        discarded++
    }

    override fun describe(bytes: ByteArray): PdfFileInfo? {
        val file = Inspector.inspect(bytes)
        return when (file.format) {
            DetectedFormat.PDF -> PdfFileInfo(true, file.pdfPages ?: 0, null, file.pdfEncrypted)
            DetectedFormat.JPEG -> PdfFileInfo(false, 1, Raster.white(8, 12))
            else -> null
        }
    }

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = Raster.white(400, 600)

    override suspend fun make(
        job: PdfJob,
        progress: (PdfProgress) -> Unit,
    ): PdfOutcome {
        jobs += job
        progress(PdfProgress.Fitting(0, 1))
        gate?.await()
        return outcome(job)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PdfMakerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakePdfMakerTools()
    private val destinations = FakeExportDestinations()
    private val share = FakeShareStager()
    private val prefs = FakeUserPreferences()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        tools.files[PHOTO] = TestJpeg.make(1200, 1600, 300 * 1024)
        tools.files[PHOTO_2] = TestJpeg.make(1600, 1200, 300 * 1024)
        tools.files[PDF] = TestPdf.make(2, 600 * 1024)
        tools.files[PDF_2] = TestPdf.make(3, 400 * 1024)
        tools.files[LOCKED] = TestPdf.make(1, 50 * 1024, encrypted = true)
        tools.files[BIG] = TestPdf.make(19, 600 * 1024)
        tools.files[JUNK] = ByteArray(16)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(vararg args: Pair<String, String>) = PdfMakerViewModel(
        SavedStateHandle(args.toMap()),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        DocumentExporter(destinations, prefs, FakeKitStore()),
        share,
        dispatcher,
    )

    private fun tool(tool: PdfTool) = model(PdfMakerViewModel.TOOL to tool.wire)

    private val PdfMakerViewModel.state get() = uiState.value

    @Test
    fun aToolStartsEmptyWithA200KbLimitAndWaitsForFiles() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        assertEquals(200.0, m.state.target?.spec?.sizeKb?.max)
        assertEquals("pdf", m.state.target?.examId)
        assertEquals(DocType.DOCUMENT, m.state.target?.spec?.type)
        assertEquals(PdfMakerUiState.Missing.FILES, m.state.missing)
        assertFalse(m.state.canMake)
        m.onAdd(listOf(PHOTO, PHOTO_2))
        advanceUntilIdle()
        assertEquals(2, m.state.items.size)
        assertTrue(m.state.canMake)
    }

    @Test
    fun theMaxKbMustBeAWholeNumberFrom1To5120() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        m.onAdd(listOf(PHOTO))
        advanceUntilIdle()
        m.onMaxKb("0")
        assertEquals(PdfMakerUiState.Missing.MAX_KB, m.state.missing)
        m.onMaxKb("9999")
        assertEquals(PdfMakerUiState.Missing.MAX_KB, m.state.missing)
        m.onMaxKb("3 00 KB")
        assertEquals(300, m.state.maxKb)
        assertEquals(300.0, m.state.target?.spec?.sizeKb?.max)
        assertTrue(m.state.canMake)
    }

    @Test
    fun anExamSlotUsesItsOwnWindowAndAnUnknownSlotSaysSo() = runTest(dispatcher) {
        val jee = model(PdfMakerViewModel.EXAM_ID to "jee_main", PdfMakerViewModel.DOC_TYPE to "class10_certificate")
        advanceUntilIdle()
        val target = checkNotNull(jee.state.target)
        assertEquals("JEE Main", target.examName)
        assertEquals(50.0, target.spec.sizeKb.min)
        assertTrue(target.exam)
        assertEquals(PdfTool.EXAM, jee.state.tool)
        val photoSlot = model(PdfMakerViewModel.EXAM_ID to "jee_main", PdfMakerViewModel.DOC_TYPE to "photo")
        advanceUntilIdle()
        assertTrue("a slot that takes no PDF", photoSlot.state.notFound)
    }

    @Test
    fun unreadableLockedAndTooManyPagesAreNotAdded() = runTest(dispatcher) {
        val m = model(PdfMakerViewModel.EXAM_ID to "jee_main", PdfMakerViewModel.DOC_TYPE to "class10_certificate")
        advanceUntilIdle()
        m.onAdd(listOf(JUNK))
        advanceUntilIdle()
        assertEquals(AddFailure.UNREADABLE, m.state.addFailure)
        m.onAdd(listOf(LOCKED))
        advanceUntilIdle()
        assertEquals(AddFailure.ENCRYPTED, m.state.addFailure)
        m.onAdd(listOf(BIG, PDF, PHOTO))
        advanceUntilIdle()
        assertEquals("19 + 2 pages is over 20: adding stops", AddFailure.TOO_MANY_PAGES, m.state.addFailure)
        assertEquals(listOf(19), m.state.items.map { it.pages })
        m.onDismissAddFailure()
        assertNull(m.state.addFailure)
    }

    @Test
    fun mergeNeedsTwoPdfsAndCompressKeepsOne() = runTest(dispatcher) {
        val merge = tool(PdfTool.MERGE)
        merge.onAdd(listOf(PDF))
        advanceUntilIdle()
        assertEquals(PdfMakerUiState.Missing.TWO_PDFS, merge.state.missing)
        merge.onAdd(listOf(PDF_2))
        advanceUntilIdle()
        assertNull(merge.state.missing)
        assertEquals(5, merge.state.pages)
        val compress = tool(PdfTool.COMPRESS)
        compress.onAdd(listOf(PDF))
        compress.onAdd(listOf(PDF_2))
        advanceUntilIdle()
        assertEquals("the second choice replaces the first", listOf(3), compress.state.items.map { it.pages })
    }

    @Test
    fun anIdCardTakesAFrontThenABackAndReplacesASide() = runTest(dispatcher) {
        val m = tool(PdfTool.ID_CARD)
        assertEquals(DocType.ID_CARD, m.state.target?.spec?.type)
        m.onAdd(listOf(PHOTO), side = 0)
        advanceUntilIdle()
        assertEquals(PdfMakerUiState.Missing.BOTH_SIDES, m.state.missing)
        m.onAdd(listOf(PHOTO_2), side = 1)
        advanceUntilIdle()
        val back = m.state.items[1].id
        m.onAdd(listOf(PHOTO), side = 0)
        advanceUntilIdle()
        assertEquals(2, m.state.items.size)
        assertEquals(back, m.state.items[1].id)
        assertEquals(1, m.state.pages)
        assertTrue(m.state.canMake)
    }

    @Test
    fun reorderTurnAndRemoveEditTheList() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        m.onAdd(listOf(PHOTO, PHOTO_2))
        advanceUntilIdle()
        val (first, second) = m.state.items.map { it.id }
        m.onMove(second, -1)
        assertEquals(listOf(second, first), m.state.items.map { it.id })
        m.onMove(second, -1)
        assertEquals("cannot move past the top", listOf(second, first), m.state.items.map { it.id })
        m.onTurn(first)
        assertEquals(1, m.state.items.first { it.id == first }.quarterTurns)
        m.onRemove(second)
        assertEquals(listOf(first), m.state.items.map { it.id })
    }

    @Test
    fun cropIsKeptInThePicturesPixelsAndATurnStartsItAgain() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        m.onAdd(listOf(PHOTO))
        advanceUntilIdle()
        val id = m.state.items.single().id
        m.onCrop(id)
        advanceUntilIdle()
        assertEquals(CropRect(0, 0, 400, 600), m.state.crop?.rect)
        m.onCropResize(CropCorner.BOTTOM_RIGHT, -100.0, -200.0)
        m.onCropMove(50.0, 0.0)
        m.onCropDone()
        advanceUntilIdle()
        assertNull(m.state.crop)
        val item = m.state.items.single()
        assertEquals(CropRect(50, 0, 300, 400), item.crop)
        assertEquals(PixelSize(400, 600), item.cropBase)
        m.onCrop(id)
        advanceUntilIdle()
        m.onCropCancel()
        assertEquals("cancel keeps the crop", CropRect(50, 0, 300, 400), m.state.items.single().crop)
        m.onTurn(id)
        assertNull(m.state.items.single().crop)
    }

    @Test
    fun makingShowsProgressThenAResultThatAnyEditDrops() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        m.onAdd(listOf(PHOTO))
        advanceUntilIdle()
        m.onTurn(m.state.items.single().id)
        m.onGrayscale(false)
        tools.gate = CompletableDeferred()
        m.onMake()
        assertNotNull(m.state.making)
        assertFalse(m.state.canMake)
        m.onRemove(m.state.items.single().id)
        assertEquals("no edits while making", 1, m.state.items.size)
        tools.gate?.complete(Unit)
        advanceUntilIdle()
        assertNull(m.state.making)
        val ready = m.state.result as PdfResult.Ready
        assertEquals(1, ready.pages)
        assertTrue(ready.meetsRules)
        val job = tools.jobs.single()
        assertFalse(job.options.grayscale)
        assertEquals(1, (job.sources.single() as PdfSource.Image).quarterTurns)
        m.onCleanup(false)
        assertNull("an edit drops the result", m.state.result)
    }

    @Test
    fun cancellingKeepsTheFilesAndAFailureSaysWhy() = runTest(dispatcher) {
        val m = tool(PdfTool.COMPRESS)
        m.onAdd(listOf(PDF))
        advanceUntilIdle()
        tools.gate = CompletableDeferred()
        m.onMake()
        m.onCancelMaking()
        assertNull(m.state.making)
        assertNull(m.state.result)
        assertEquals(1, m.state.items.size)
        tools.gate = null
        tools.outcome = { PdfOutcome.Failed(PdfError.CANNOT_REACH) }
        m.onMake()
        advanceUntilIdle()
        assertEquals(PdfResult.Failed(PdfError.CANNOT_REACH), m.state.result)
        assertFalse(m.state.canSave)
    }

    @Test
    fun anExamPdfIsSavedVerifiedAndMarkedSaved() = runTest(dispatcher) {
        val m = model(PdfMakerViewModel.EXAM_ID to "jee_main", PdfMakerViewModel.DOC_TYPE to "class10_certificate")
        advanceUntilIdle()
        m.onAdd(listOf(PHOTO))
        advanceUntilIdle()
        m.onMake()
        advanceUntilIdle()
        assertTrue(m.state.canSave)
        assertNull(m.onSave())
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.state.save.state)
        val request = checkNotNull(destinations.lastRequest)
        assertEquals("application/pdf", request.mimeType)
        assertEquals("class10_certificate_JEE-Main_90kb.pdf", request.fileName)
        assertEquals(setOf(SavedDocument("jee_main", "CLASS10_CERTIFICATE")), prefs.savedDocuments.first())
    }

    @Test
    fun aToolPdfIsSavedWithoutAChecklistAndCanBeShared() = runTest(dispatcher) {
        val m = tool(PdfTool.ID_CARD)
        m.onAdd(listOf(PHOTO), side = 0)
        m.onAdd(listOf(PHOTO_2), side = 1)
        advanceUntilIdle()
        m.onMake()
        advanceUntilIdle()
        m.onShare()
        advanceUntilIdle()
        assertEquals("id_card_PDF_90kb.pdf", share.staged?.first)
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.state.save.state)
        assertTrue("tools have no checklist", prefs.savedDocuments.first().isEmpty())
    }

    @Test
    fun aPickerSaveOnOldAndroidWaitsForTheDestination() = runTest(dispatcher) {
        val picker = FakeExportDestinations(requiresPicker = true)
        val m =
            PdfMakerViewModel(
                SavedStateHandle(mapOf(PdfMakerViewModel.TOOL to "images")),
                FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
                tools,
                DocumentExporter(picker, prefs, FakeKitStore()),
                share,
                dispatcher,
            )
        m.onAdd(listOf(PHOTO))
        advanceUntilIdle()
        m.onMake()
        advanceUntilIdle()
        assertEquals("document_PDF_90kb.pdf", m.onSave())
        assertEquals(SaveState.SAVING, m.state.save.state)
        m.onSaveDestination(null)
        assertEquals("cancelled", SaveState.IDLE, m.state.save.state)
    }

    @Test
    fun aCameraCaptureIsAddedThenDeleted() = runTest(dispatcher) {
        val m = tool(PdfTool.IMAGES)
        val uri = checkNotNull(m.onCaptureRequested())
        tools.files[uri] = TestJpeg.make(800, 1000, 100 * 1024)
        m.onCaptured(true)
        advanceUntilIdle()
        assertEquals(1, m.state.items.size)
        assertEquals(1, tools.discarded)
        m.onCaptureRequested()
        m.onCaptured(false)
        advanceUntilIdle()
        assertEquals(2, tools.discarded)
        assertEquals(1, m.state.items.size)
    }

    private companion object {
        const val PHOTO = "content://photo"
        const val PHOTO_2 = "content://photo2"
        const val PDF = "content://pdf"
        const val PDF_2 = "content://pdf2"
        const val LOCKED = "content://locked"
        const val BIG = "content://big"
        const val JUNK = "content://junk"
    }
}
