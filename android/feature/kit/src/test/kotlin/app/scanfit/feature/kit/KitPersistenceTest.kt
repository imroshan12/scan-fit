package app.scanfit.feature.kit

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.scanfit.core.data.draft.FileDraftStore
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.FakeUserPreferences
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class KitPersistenceTest {
    @get:Rule val folder = TemporaryFolder()
    private val bank = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val photo = bank.documents.first { it.type == DocType.PHOTO }
    private val signature = bank.documents.first { it.type == DocType.SIGNATURE }
    private val photoBytes = TestJpeg.make(200, 230, 35 * 1024)
    private val signatureBytes = TestJpeg.make(140, 60, 16 * 1024)
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(1, 2)), SpecPresets.bundle.copy(exams = listOf(bank)))

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun ReceiveTurbine<KitUiState>.resolved(): KitUiState {
        var state = awaitItem()
        while (state == KitUiState.Loading) state = awaitItem()
        return state
    }

    @Test
    fun persistedPhotoAndSignatureAppearBeforeAndAfterProcessRelaunchWithIdenticalRecords() = runTest {
        val root = folder.newFolder().canonicalFile
        val store = FileDraftStore(root)
        val preferences = FakeUserPreferences(showUnverified = false)
        preferences.recordSaved(bank.id, "PHOTO")
        val saved = preferences.savedDocuments.value
        assertTrue(store.retain(bank.id, photo, DocKind.PHOTO, photoBytes))
        assertTrue(store.retain(bank.id, signature, DocKind.SIGNATURE, signatureBytes))
        val original = requireNotNull(root.listFiles()).associate { it.name to it.readBytes() }
        val expected = listOf(KitDocument(DocType.PHOTO, 35), KitDocument(DocType.SIGNATURE, 16))
        for (instance in listOf(store, FileDraftStore(root))) {
            KitViewModel(presets, instance).uiState.test {
                val state = resolved() as KitUiState.Ready
                assertEquals(listOf(bank.id), state.exams.map { it.id })
                assertEquals(expected, state.exams.single().documents)
                assertArrayEquals(photoBytes, requireNotNull(instance.read(bank.id, photo, DocKind.PHOTO)).bytes)
                assertArrayEquals(signatureBytes, requireNotNull(instance.read(bank.id, signature, DocKind.SIGNATURE)).bytes)
                assertEquals(saved, preferences.savedDocuments.value)
            }
        }
        assertEquals(original.keys, requireNotNull(root.listFiles()).map { it.name }.toSet())
        for (file in requireNotNull(root.listFiles())) assertArrayEquals(original.getValue(file.name), file.readBytes())
    }

    @Test
    fun actualCorruptMissingAndExpiredFilesAreUnavailableWithoutRepeatedCleanupNotifications() = runTest {
        val root = folder.newFolder().canonicalFile
        var now = 1_800_000_000_000L
        val store = FileDraftStore(root, clock = { now })
        assertTrue(store.retain(bank.id, photo, DocKind.PHOTO, photoBytes))
        requireNotNull(root.listFiles()).single().writeText("corrupted")
        val model = KitViewModel(presets, store)
        model.uiState.test {
            assertEquals(KitUiState.Empty, resolved())
            assertEquals(2L, store.revisions.value)
            assertTrue(requireNotNull(root.listFiles()).isEmpty())
            model.refreshDrafts()
            assertEquals(KitUiState.Empty, resolved())
            assertEquals(2L, store.revisions.value)
            assertTrue(store.retain(bank.id, signature, DocKind.SIGNATURE, signatureBytes))
            assertTrue(resolved() is KitUiState.Ready)
            now += FileDraftStore.TTL_MILLIS
            model.refreshDrafts()
            assertEquals(KitUiState.Empty, resolved())
            assertEquals(4L, store.revisions.value)
            model.refreshDrafts()
            assertEquals(KitUiState.Empty, resolved())
            assertEquals(4L, store.revisions.value)
        }
    }
}
