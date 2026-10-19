package app.scanfit.feature.checker

import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.FitResult
import app.scanfit.core.imaging.FitStrategy
import app.scanfit.core.imaging.PipelineOutcome
import app.scanfit.core.imaging.PipelineResult
import app.scanfit.core.imaging.Raster
import app.scanfit.core.inspect.Issue
import app.scanfit.core.match.CheckerStatus
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocSpec
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeShareStager
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Test double for [CheckerTools]: files by URI, a fixed decode and a configurable fit; remembers what it fitted. */
class FakeCheckerTools : CheckerTools {
    val files = mutableMapOf<String, Pair<String, ByteArray>>()
    var decoded: Raster? = Raster.of(600, 300) { _, _ -> 0xF0F0F0 }
    var fitWith: (Raster, DocSpec) -> PipelineOutcome = { raster, _ -> success(raster, TestJpeg.make(140, 60, 16 * 1024)) }
    val fitted = mutableListOf<DocSpec>()

    override suspend fun read(uri: String): ByteArray? = files[uri]?.second

    override fun displayName(uri: String): String = files[uri]?.first.orEmpty()

    override fun decode(
        bytes: ByteArray,
        maxLongSide: Int,
    ): Raster? = decoded

    override fun fit(
        image: Raster,
        spec: DocSpec,
    ): PipelineOutcome {
        fitted += spec
        return fitWith(image, spec)
    }

    companion object {
        fun success(
            prepared: Raster,
            bytes: ByteArray,
        ): PipelineOutcome = PipelineOutcome.Success(
            PipelineResult(FitResult(bytes, 0, 0, 90, FitStrategy.QUALITY_SEARCH, 3), prepared, null),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class CheckerViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakeCheckerTools()
    private val destinations = FakeExportDestinations()
    private val share = FakeShareStager()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(prefs: FakeUserPreferences = FakeUserPreferences(handwritingConfirmed = true)) = CheckerViewModel(
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        prefs,
        DocumentExporter(destinations, prefs, FakeKitStore()),
        share,
        dispatcher,
    )

    private val CheckerViewModel.state get() = uiState.value

    private fun ibps(m: CheckerViewModel) = m.state.targets.first { it.exam.id == "ibps_po" }

    @Test
    fun aPickedFileShowsItsOwnProblemsWithItsName() = runTest(dispatcher) {
        tools.files[BIG] = "my-sign.png" to TestJpeg.make(140, 60, 30 * 1024)
        val m = model()
        m.onFileSelected(BIG)
        advanceUntilIdle()
        val file = checkNotNull(m.state.file)
        assertEquals(30, file.kb)
        assertTrue("a JPEG named .png", Issue.EXTENSION_MISMATCH in file.inspected.issues)
        assertNull("no kind until the user says", m.state.kind)
    }

    @Test
    fun anUnreadableFileSaysSo() = runTest(dispatcher) {
        val m = model()
        m.onFileSelected("content://gone")
        advanceUntilIdle()
        assertTrue(m.state.openFailed)
        assertNull(m.state.file)
    }

    @Test
    fun aKindListsExamsAndTheMatchNoteForTheFileAsItIs() = runTest(dispatcher) {
        tools.files[GOOD] = "sign.jpg" to TestJpeg.make(140, 60, 16 * 1024)
        val m = model()
        m.onFileSelected(GOOD)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        assertTrue(m.state.note.accepted > 0)
        assertTrue(m.state.targets.all { DocKind.of(it.slot.type) == DocKind.SIGNATURE })
        m.onQuery("sbi po")
        advanceUntilIdle()
        assertEquals("sbi_po", m.state.targets.first().exam.id)
    }

    @Test
    fun aMatchingFileIsOkAndOffersNoFix() = runTest(dispatcher) {
        tools.files[GOOD] = "sign.jpg" to TestJpeg.make(140, 60, 16 * 1024)
        val m = model()
        m.onFileSelected(GOOD)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        m.onTarget(ibps(m))
        assertEquals(CheckerStatus.OK, m.state.result?.status)
        m.onFix()
        assertTrue("nothing to fix", tools.fitted.isEmpty())
    }

    @Test
    fun aTooBigFileIsFittedSavedForThatSlotAndTheOriginalIsUntouched() = runTest(dispatcher) {
        val original = TestJpeg.make(140, 60, 30 * 1024)
        tools.files[BIG] = "sign.jpg" to original
        val prefs = FakeUserPreferences(handwritingConfirmed = true)
        val m = model(prefs)
        m.onFileSelected(BIG)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        m.onTarget(ibps(m))
        val result = checkNotNull(m.state.result)
        assertEquals(CheckerStatus.FIX, result.status)
        assertTrue(Issue.TOO_LARGE_KB in result.evaluation.issues)
        m.onFix()
        advanceUntilIdle()
        val fix = m.state.fix as FixState.Ready
        assertTrue(fix.meetsRules && m.state.canSave)
        assertEquals("fitted to the IBPS signature slot", DocType.SIGNATURE, tools.fitted.single().type)
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.state.save.state)
        assertEquals(setOf(SavedDocument("ibps_po", "SIGNATURE")), prefs.savedDocuments.first())
        assertEquals("signature_IBPS-PO_140x60_16kb.jpg", destinations.lastRequest?.fileName)
        assertTrue("the original is not changed", original.contentEquals(tools.files.getValue(BIG).second))
    }

    @Test
    fun aSignatureWaitsForTheHandwritingTickBeforeSaveOrShare() = runTest(dispatcher) {
        tools.files[BIG] = "sign.jpg" to TestJpeg.make(140, 60, 30 * 1024)
        val prefs = FakeUserPreferences(handwritingConfirmed = false)
        val m = model(prefs)
        m.onFileSelected(BIG)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        m.onTarget(ibps(m))
        m.onFix()
        advanceUntilIdle()
        assertFalse(m.state.canSave)
        assertNull(m.onSave())
        m.onShare()
        advanceUntilIdle()
        assertNull(share.staged)
        m.onConfirmHandwriting()
        advanceUntilIdle()
        assertTrue(m.state.canSave)
        m.onShare()
        advanceUntilIdle()
        assertEquals("signature_IBPS-PO_140x60_16kb.jpg", share.staged?.first)
        assertTrue("sharing is not saving", prefs.savedDocuments.first().isEmpty())
    }

    @Test
    fun aPdfCannotBeFixedAndAFailedFitIsShown() = runTest(dispatcher) {
        tools.files[PDF] = "doc.pdf" to "%PDF-1.4\n%%EOF".toByteArray()
        val m = model()
        m.onFileSelected(PDF)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        m.onTarget(ibps(m))
        assertEquals(CheckerStatus.NO_FIX, m.state.result?.status)

        tools.files[BIG] = "sign.jpg" to TestJpeg.make(140, 60, 30 * 1024)
        tools.fitWith = { _, _ -> PipelineOutcome.Failure(FitError.TOO_DETAILED) }
        m.onFileSelected(BIG)
        m.onKind(DocKind.SIGNATURE)
        advanceUntilIdle()
        m.onTarget(ibps(m))
        m.onFix()
        advanceUntilIdle()
        assertEquals(FixState.Failed(FitError.TOO_DETAILED), m.state.fix)
        tools.decoded = null
        m.onChangeExam()
        m.onTarget(ibps(m))
        m.onFix()
        advanceUntilIdle()
        assertEquals("an undecodable image", FixState.Failed(null), m.state.fix)
    }

    private companion object {
        const val GOOD = "content://good"
        const val BIG = "content://big"
        const val PDF = "content://pdf"
    }
}
