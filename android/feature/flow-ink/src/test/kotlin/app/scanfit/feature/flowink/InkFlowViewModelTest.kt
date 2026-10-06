package app.scanfit.feature.flowink

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.SavedDocument
import app.scanfit.core.data.export.DocumentExporter
import app.scanfit.core.data.export.SaveState
import app.scanfit.core.imaging.CropCorner
import app.scanfit.core.imaging.CropRect
import app.scanfit.core.imaging.FitError
import app.scanfit.core.imaging.InkQuality
import app.scanfit.core.imaging.Pipeline
import app.scanfit.core.imaging.Raster
import app.scanfit.core.match.DocKind
import app.scanfit.core.match.ReviewChecks
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakeExportDestinations
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InkFlowViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val tools = FakeInkTools().apply { files[PHOTO] = byteArrayOf(1, 2, 3) }
    private val destinations = FakeExportDestinations()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun model(
        exam: String = "ibps_po",
        doc: String = "signature",
        prefs: FakeUserPreferences = FakeUserPreferences(),
        destination: FakeExportDestinations = destinations,
    ) = InkFlowViewModel(
        SavedStateHandle(mapOf(InkFlowViewModel.EXAM_ID to exam, InkFlowViewModel.DOC_TYPE to doc)),
        FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle),
        tools,
        prefs,
        DocumentExporter(destination, prefs),
        dispatcher,
        FakeDraftStore(),
    )

    private val InkFlowViewModel.state get() = uiState.value
    private val InkFlowViewModel.review get() = state as InkUiState.Review

    /** Picks the photo and continues past the crop, landing on Review. */
    private fun TestScope.reviewed(m: InkFlowViewModel = model()): InkUiState.Review {
        m.onImageSelected(PHOTO)
        m.onCropDone()
        advanceUntilIdle()
        return m.review
    }

    @Test
    fun startsAtThePickerForTheExamsInkSlot() {
        val s = model().state as InkUiState.PickSource
        assertEquals(DocKind.SIGNATURE, s.slot.kind)
        assertEquals(16, s.slot.targetKb)
        assertFalse(s.openFailed)
    }

    @Test
    fun aPhotoSlotOrAnUnknownExamIsNotAnInkFlow() {
        assertEquals(InkUiState.NotFound, model(doc = "photo").state)
        assertEquals(InkUiState.NotFound, model(exam = "no_such_exam").state)
        assertEquals(InkUiState.NotFound, model(doc = "triple_signature").state)
    }

    @Test
    fun eachSlotTypeGetsItsCleanupVariantAndKind() {
        val cases =
            listOf(
                Triple("ibps_po", "signature", Pipeline.SIGNATURE_CLEANUP to DocKind.SIGNATURE),
                Triple("ibps_po", "left_thumb", Pipeline.THUMB_CLEANUP to DocKind.THUMB),
                Triple("ibps_po", "handwritten_declaration", Pipeline.DOCUMENT_CLEANUP to DocKind.DECLARATION),
                Triple("neet_ug", "left_hand_fingers_thumb", Pipeline.THUMB_CLEANUP to DocKind.FINGERS),
                Triple("upsc_cse", "triple_signature", Pipeline.SIGNATURE_CLEANUP to DocKind.SIGNATURE),
            )
        for ((exam, doc, expected) in cases) {
            val slot = (model(exam, doc).state as InkUiState.PickSource).slot
            assertEquals("$exam/$doc", expected, slot.pipeline to slot.kind)
        }
    }

    @Test
    fun aPickedImageStartsAsAWholeImageCropAndTheCaptureIsDeleted() {
        val m = model()
        m.onImageSelected(PHOTO)
        val crop = m.state as InkUiState.Crop
        assertEquals(CropRect(0, 0, 1200, 800), crop.rect)
        assertEquals(1, tools.discarded)
    }

    @Test
    fun anUnreadableFileGoesBackToThePickerWithAMessage() {
        val m = model()
        m.onImageSelected("content://missing")
        assertTrue((m.state as InkUiState.PickSource).openFailed)
    }

    @Test
    fun cornersResizeTheFrameAndResetGoesBackToTheWholeImage() {
        val m = model()
        m.onImageSelected(PHOTO)
        m.onResize(CropCorner.TOP_LEFT, 300.0, 200.0)
        assertEquals(CropRect(300, 200, 900, 600), (m.state as InkUiState.Crop).rect)
        m.onMove(5000.0, 0.0)
        assertEquals(300, (m.state as InkUiState.Crop).rect.x)
        m.onResetCrop()
        assertEquals(CropRect(0, 0, 1200, 800), (m.state as InkUiState.Crop).rect)
        m.onRotate()
        assertEquals(CropRect(0, 0, 800, 1200), (m.state as InkUiState.Crop).rect)
    }

    @Test
    fun reviewCleansExactlyTheCropAndChecksTheBytesAsTheSlotsKind() = runTest(dispatcher) {
        val m = model()
        m.onImageSelected(PHOTO)
        m.onResize(CropCorner.BOTTOM_RIGHT, -200.0, -100.0)
        m.onCropDone()
        advanceUntilIdle()
        val ready = m.review.result as InkReviewResult.Ready
        assertEquals(1000, tools.fitInput?.width)
        assertEquals(700, tools.fitInput?.height)
        assertEquals(16, ready.kb)
        assertTrue("140x60 at 16 KB meets the IBPS signature rules", ready.meetsRules)
        assertEquals(ReviewChecks(true, true, true), ready.checks)
        assertEquals(Pipeline.SIGNATURE_CLEANUP, tools.fits.last().first)
        assertEquals("a signature is crisp black by default", true, tools.fits.last().second.crispBlack)
    }

    @Test
    fun theReviewNotesWhichExamsAcceptTheFileAsTheSlotsKind() = runTest(dispatcher) {
        val note = (reviewed().result as InkReviewResult.Ready).note
        val rows = note.groups.flatMap { it.entries }
        assertTrue("IBPS PO accepts its own signature", rows.any { it.examId == "ibps_po" })
        assertTrue("other exams are named too", note.accepted > 1)
        assertTrue(
            "matched as a signature: only signature slots are listed",
            rows.all { it.docType == DocType.SIGNATURE || it.docType == DocType.TRIPLE_SIGNATURE },
        )
    }

    @Test
    fun aFileOutsideTheWindowDoesNotMeetTheRulesAndAFitErrorIsShown() = runTest(dispatcher) {
        tools.fit = { r -> tools.success(r, TestJpeg.make(140, 60, 30 * 1024)) }
        val ready = reviewed().result as InkReviewResult.Ready
        assertFalse(ready.meetsRules)
        assertEquals(ReviewChecks(false, true, true), ready.checks)
        tools.fit = { tools.failure(FitError.TOO_DETAILED) }
        assertEquals(InkReviewResult.Failed(FitError.TOO_DETAILED), reviewed().result)
    }

    @Test
    fun reviewChecksUseInspectedDimensionsEncodingAndCorruptBytes() = runTest(dispatcher) {
        val progressive = TestJpeg.make(140, 60, 16 * 1024).apply {
            this[indexOfFirst { it == 0xC0.toByte() }] = 0xC2.toByte()
        }
        val cases = listOf(
            TestJpeg.make(140, 100, 16 * 1024) to ReviewChecks(true, false, true),
            progressive to ReviewChecks(true, true, false),
            byteArrayOf(1, 2, 3) to ReviewChecks(false, true, false),
        )
        for ((bytes, expected) in cases) {
            tools.fit = { source -> tools.success(source, bytes) }
            val ready = reviewed().result as InkReviewResult.Ready
            assertEquals(expected, ready.checks)
            assertFalse(ready.meetsRules)
        }
    }

    @Test
    fun beforeIsTheCropBeforeCleanupPaddingAndFitAndStaysStableThroughRendersAndFailure() = runTest(dispatcher) {
        val model = model()
        model.onImageSelected(PHOTO)
        model.onRotate()
        model.onResize(CropCorner.TOP_LEFT, 100.0, 200.0)
        val crop = model.state as InkUiState.Crop
        val rect = crop.rect
        val expected = crop.image.crop(rect.x, rect.y, rect.w, rect.h)
        tools.fit = { tools.success(Raster.white(140, 60), TestJpeg.make(140, 60, 16 * 1024)) }
        model.onCropDone()
        advanceUntilIdle()
        val before = checkNotNull(model.review.before)
        assertEquals(rect.w, before.width)
        assertEquals(rect.h, before.height)
        assertArrayEquals(expected.rgb, before.rgb)
        assertSame(tools.fitInput, before)
        tools.fit = { source ->
            assertTrue(model.review.result is InkReviewResult.Working)
            assertSame(before, model.review.before)
            tools.success(source, TestJpeg.make(140, 60, 16 * 1024))
        }
        model.onCrispBlack(false)
        advanceUntilIdle()
        assertSame(before, model.review.before)
        model.onInkFactor(0.3)
        assertSame(before, model.review.before)
        advanceUntilIdle()
        assertSame(before, model.review.before)
        assertArrayEquals(expected.rgb, before.rgb)
        tools.fit = { tools.failure(FitError.TOO_DETAILED) }
        model.onCrispBlack(true)
        advanceUntilIdle()
        assertTrue(model.review.result is InkReviewResult.Failed)
        assertSame(before, model.review.before)
    }

    @Test
    fun theQualityGateWarnsButDoesNotBlockSaving() = runTest(dispatcher) {
        tools.quality = InkQuality.TOO_FAINT
        val review = reviewed(model(prefs = FakeUserPreferences(handwritingConfirmed = true)))
        assertEquals(InkQuality.TOO_FAINT, (review.result as InkReviewResult.Ready).quality)
        assertTrue(review.canSave)
    }

    @Test
    fun crispBlackIsOffForADeclarationAndAThumbHasNoInkOptions() = runTest(dispatcher) {
        tools.fit = { r -> tools.success(r, TestJpeg.make(800, 400, 80 * 1024)) }
        val declaration = model(doc = "handwritten_declaration")
        reviewed(declaration)
        assertEquals(false, tools.fits.last().second.crispBlack)
        declaration.onCrispBlack(true)
        advanceUntilIdle()
        assertEquals(true, tools.fits.last().second.crispBlack)

        tools.fit = { r -> tools.success(r, TestJpeg.make(240, 240, 38 * 1024)) }
        val thumb = model(doc = "left_thumb")
        reviewed(thumb)
        val before = tools.fits.size
        thumb.onCrispBlack(true)
        thumb.onInkFactor(0.3)
        advanceUntilIdle()
        assertEquals("a thumb keeps its ridges: no re-render", before, tools.fits.size)
        assertFalse(thumb.review.slot.needsHandwritingConfirmation)
    }

    @Test
    fun darkerInkSnapsToTenthsInsideTheRangeAndIsDebounced() = runTest(dispatcher) {
        val m = model(doc = "handwritten_declaration")
        tools.fit = { r -> tools.success(r, TestJpeg.make(800, 400, 80 * 1024)) }
        reviewed(m)
        val before = tools.fits.size
        m.onInkFactor(0.27)
        assertEquals(0.3, m.review.options.inkFactor, 1e-9)
        assertEquals("not fitted on every slider step", before, tools.fits.size)
        advanceUntilIdle()
        assertEquals(0.3, tools.fits.last().second.inkFactor, 1e-9)
        m.onInkFactor(0.66)
        advanceUntilIdle()
        assertEquals(0.7, tools.fits.last().second.inkFactor, 1e-9)
        m.onInkFactor(1.5)
        assertEquals(0.9, m.review.options.inkFactor, 1e-9)
    }

    @Test
    fun aSignatureNeedsTheHandwritingTickOnceThenSaves() = runTest(dispatcher) {
        val prefs = FakeUserPreferences()
        val m = model(prefs = prefs)
        reviewed(m)
        assertFalse("the first signature save waits for the tick", m.review.canSave)
        assertNull(m.onSave())
        assertTrue(destinations.events.isEmpty())
        m.onConfirmHandwriting()
        advanceUntilIdle()
        assertTrue(prefs.handwritingConfirmed.first())
        assertTrue(m.review.canSave)
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.review.save.state)
        assertEquals(setOf(SavedDocument("ibps_po", "SIGNATURE")), prefs.savedDocuments.first())
        assertEquals(DocKind.SIGNATURE, destinations.lastRequest?.kind)
    }

    @Test
    fun aRememberedTickIsNotAskedAgain() = runTest(dispatcher) {
        val review = reviewed(model(prefs = FakeUserPreferences(handwritingConfirmed = true)))
        assertTrue(review.handwritingConfirmed)
        assertTrue(review.canSave)
    }

    @Test
    fun aFailedVerificationKeepsTheReviewForARetry() = runTest(dispatcher) {
        val m = model(prefs = FakeUserPreferences(handwritingConfirmed = true))
        reviewed(m)
        destinations.operation = "corrupt"
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.VERIFY_FAILED, m.review.save.state)
        destinations.operation = "success"
        m.onSave()
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.review.save.state)
    }

    @Test
    fun onAndroid8And9TheUserPicksTheDestinationFirst() = runTest(dispatcher) {
        val picker = FakeExportDestinations(requiresPicker = true)
        val m = model(prefs = FakeUserPreferences(handwritingConfirmed = true), destination = picker)
        reviewed(m)
        val name = m.onSave()
        assertEquals("signature_IBPS-PO_140x60_16kb.jpg", name)
        assertEquals(SaveState.SAVING, m.review.save.state)
        m.onSaveDestination(null)
        assertEquals("a cancelled pick is not an error", SaveState.IDLE, m.review.save.state)
        m.onSave()
        m.onSaveDestination("content://picked/signature.jpg")
        advanceUntilIdle()
        assertEquals(SaveState.SAVED, m.review.save.state)
    }

    @Test
    fun backStepsThroughTheFlowThenLeaves() = runTest(dispatcher) {
        val m = model()
        assertFalse("the picker is the first step", m.onBack())
        reviewed(m)
        assertTrue(m.onBack())
        assertTrue(m.state is InkUiState.Crop)
        assertTrue(m.onBack())
        assertTrue(m.state is InkUiState.PickSource)
        assertFalse(m.onBack())
    }

    private companion object {
        const val PHOTO = "content://picker/signature.jpg"
    }
}
