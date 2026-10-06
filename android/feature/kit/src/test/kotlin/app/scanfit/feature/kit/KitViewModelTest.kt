package app.scanfit.feature.kit

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.scanfit.core.data.draft.RetainedDraft
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.Confidence
import app.scanfit.core.model.DocType
import app.scanfit.core.model.ExamStatus
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeDraftStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class KitViewModelTest {
    private val bank = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val upsc = SpecPresets.bundle.exams.first { it.id == "upsc_ese" }
    private val photo = bank.documents.first { it.type == DocType.PHOTO }
    private val signature = bank.documents.first { it.type == DocType.SIGNATURE }
    private val bundle = SpecPresets.bundle.copy(exams = listOf(upsc, bank))
    private val ready = PresetsLoadOutcome.Ready(PresetsSummary(2, 2))
    private val drafts = FakeDraftStore()
    private val presets = FakePresetsRepository(ready, bundle)
    private val bytes = TestJpeg.make(200, 230, 35 * 1024 + 512)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun ReceiveTurbine<KitUiState>.resolved(): KitUiState {
        var state = awaitItem()
        while (state == KitUiState.Loading) state = awaitItem()
        return state
    }

    @Test
    fun loadingCompletedFailureRetryAndVerifiedEmptyAreDistinct() = runTest {
        presets.bundle.value = null
        presets.outcome.value = null
        val model = KitViewModel(presets, drafts)
        model.uiState.test {
            assertEquals(KitUiState.Loading, awaitItem())
            presets.outcome.value = PresetsLoadOutcome.MissingEmbedded
            assertEquals(KitUiState.Error, resolved())
            model.retry()
            assertEquals(1, presets.loadCalls)
            expectNoEvents()
            presets.outcome.value = ready
            presets.bundle.value = bundle
            assertEquals(KitUiState.Empty, resolved())
        }
        val inconsistent = FakePresetsRepository(ready, null)
        KitViewModel(inconsistent, drafts).uiState.test { assertEquals(KitUiState.Error, resolved()) }
    }

    @Test
    fun photoSignatureAndHiddenUnverifiedExamKeepBundleAndDocumentOrderWithoutSavedMutation() = runTest {
        val preferences = FakeUserPreferences(showUnverified = false)
        preferences.recordSaved(bank.id, "PHOTO")
        val saved = preferences.savedDocuments.value
        assertTrue(drafts.retain(bank.id, signature, DocKind.SIGNATURE, TestJpeg.make(140, 60, 16 * 1024)))
        assertTrue(drafts.retain(bank.id, photo, DocKind.PHOTO, bytes))
        val triple = upsc.documents.first { it.type == DocType.TRIPLE_SIGNATURE }
        assertTrue(drafts.retain(upsc.id, triple, DocKind.SIGNATURE, TestJpeg.make(240, 180, 40 * 1024)))
        KitViewModel(presets, drafts).uiState.test {
            val state = resolved() as KitUiState.Ready
            assertEquals(listOf(upsc.id, bank.id), state.exams.map { it.id })
            assertEquals(Confidence.LOW, state.exams.first().confidence)
            assertEquals(listOf(KitDocument(DocType.TRIPLE_SIGNATURE, 40)), state.exams.first().documents)
            assertEquals(listOf(KitDocument(DocType.PHOTO, 36), KitDocument(DocType.SIGNATURE, 16)), state.exams.last().documents)
            assertArrayEquals(bytes, requireNotNull(drafts.read(bank.id, photo, DocKind.PHOTO)).bytes)
            assertEquals(saved, preferences.savedDocuments.value)
            assertEquals(false, preferences.showUnverified.value)
        }
    }

    @Test
    fun missingCorruptAndExpiredDraftsAreUnavailableAndCleanupSettles() = runTest {
        drafts.records[bank.id to photo.type] = RetainedDraft(byteArrayOf(1), drafts.now)
        drafts.records[bank.id to signature.type] = RetainedDraft(TestJpeg.make(140, 60, 16 * 1024), drafts.now - app.scanfit.core.data.draft.FileDraftStore.TTL_MILLIS)
        val reads = AtomicInteger()
        drafts.beforeRead = { reads.incrementAndGet() }
        KitViewModel(presets, drafts).uiState.test {
            assertEquals(KitUiState.Empty, resolved())
            assertTrue(drafts.records.isEmpty())
            assertTrue("cleanup may restart once, not indefinitely", reads.get() <= 3 * bundle.exams.sumOf { it.documents.size })
            expectNoEvents()
        }
    }

    @Test
    fun currentSlotChangesAndRemovedOrRetiredExamsRemoveRows() = runTest {
        drafts.retain(bank.id, photo, DocKind.PHOTO, bytes)
        KitViewModel(presets, drafts).uiState.test {
            assertTrue(resolved() is KitUiState.Ready)
            presets.bundle.value = bundle.copy(exams = listOf(bank.copy(status = ExamStatus.RETIRED)))
            assertEquals(KitUiState.Empty, resolved())
        }
        presets.bundle.value = bundle.copy(exams = emptyList())
        KitViewModel(presets, drafts).uiState.test { assertEquals(KitUiState.Empty, resolved()) }
        presets.bundle.value = bundle.copy(exams = listOf(bank.copy(documents = listOf(photo.copy(dpi = 300)))))
        KitViewModel(presets, drafts).uiState.test {
            assertEquals(KitUiState.Empty, resolved())
            assertTrue(drafts.records.isEmpty())
        }
    }

    @Test
    fun storeRevisionAndExplicitEntryOrForegroundRefreshRevalidate() = runTest {
        val model = KitViewModel(presets, drafts)
        model.uiState.test {
            assertEquals(KitUiState.Empty, resolved())
            drafts.retain(bank.id, photo, DocKind.PHOTO, bytes)
            assertTrue(resolved() is KitUiState.Ready)
            drafts.records.clear()
            model.refreshDrafts()
            assertEquals(KitUiState.Empty, resolved())
            drafts.retain(bank.id, signature, DocKind.SIGNATURE, TestJpeg.make(140, 60, 16 * 1024))
            assertTrue(resolved() is KitUiState.Ready)
            drafts.delete(bank.id, signature)
            assertEquals(KitUiState.Empty, resolved())
        }
    }

    @Test
    fun readFailureShowsRetryableErrorAndRetryReloadsPresetsAndDrafts() = runTest {
        drafts.beforeRead = { throw IOException("injected read failure") }
        val model = KitViewModel(presets, drafts)
        model.uiState.test {
            assertEquals(KitUiState.Error, resolved())
            drafts.beforeRead = {}
            model.retry()
            assertEquals(KitUiState.Empty, resolved())
            assertEquals(1, presets.loadCalls)
        }
    }

    @Test
    fun obsoleteNonCooperativeReadCannotPublishAfterTrustedBundleChanges() = runTest {
        drafts.retain(bank.id, photo, DocKind.PHOTO, bytes)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        drafts.beforeRead = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        KitViewModel(presets, drafts).uiState.test {
            assertEquals(KitUiState.Loading, awaitItem())
            started.await()
            presets.bundle.value = bundle.copy(exams = emptyList())
            release.complete(Unit)
            assertEquals(KitUiState.Empty, resolved())
            expectNoEvents()
        }
    }

    @Test
    fun supersededRefreshCannotPublishOldStoreResponse() = runTest {
        drafts.retain(bank.id, photo, DocKind.PHOTO, bytes)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        drafts.beforeRead = {
            started.complete(Unit)
            withContext(NonCancellable) { release.await() }
        }
        val model = KitViewModel(presets, drafts)
        model.uiState.test {
            assertEquals(KitUiState.Loading, awaitItem())
            started.await()
            drafts.records.clear()
            model.refreshDrafts()
            release.complete(Unit)
            assertEquals(KitUiState.Empty, resolved())
            expectNoEvents()
        }
    }
}
