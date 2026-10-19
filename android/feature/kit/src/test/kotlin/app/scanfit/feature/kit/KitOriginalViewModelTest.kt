package app.scanfit.feature.kit

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.data.kit.SaveOriginalResult
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsSummary
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.FakePresetsRepository
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KitOriginalViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = FakeKitStore()
    private val images = FakeKitImages()
    private val presets = FakePresetsRepository(PresetsLoadOutcome.Ready(PresetsSummary(55, 2)), SpecPresets.bundle)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model(id: String) = KitOriginalViewModel(SavedStateHandle(mapOf(KitOriginalViewModel.ORIGINAL_ID to id)), kit, presets, images, dispatcher)

    private suspend fun saved(kind: DocKind) = (kit.saveOriginal(kind, TestJpeg.make(1600, 1200, 4096)) as SaveOriginalResult.Saved).id

    @Test
    fun showsAPreviewAndTheSlotsOfItsKind() = runTest(dispatcher) {
        val id = saved(DocKind.SIGNATURE)
        val ready = model(id).uiState.value as KitOriginalUiState.Ready
        assertEquals(id, ready.original.id)
        assertTrue(ready.preview != null)
        assertTrue(images.caps.single() < 1600)
        assertTrue(ready.targets.isNotEmpty())
        assertTrue(ready.targets.all { DocKind.of(it.slot.type) == DocKind.SIGNATURE })
        assertTrue(ready.targets.any { it.slot.type == DocType.SIGNATURE && it.exam.id == "ibps_po" })
    }

    @Test
    fun searchNarrowsTheList() = runTest(dispatcher) {
        val m = model(saved(DocKind.PHOTO))
        val all = (m.uiState.value as KitOriginalUiState.Ready).targets
        m.onQuery("ibps po")
        val found = (m.uiState.value as KitOriginalUiState.Ready).targets
        assertTrue(found.isNotEmpty() && found.size < all.size)
        assertEquals("ibps_po", found.first().exam.id)
    }

    @Test
    fun aMissingOrMalformedIdIsMissing() = runTest(dispatcher) {
        assertEquals(KitOriginalUiState.Missing, model(kit.newId()).uiState.value)
        assertEquals(KitOriginalUiState.Missing, model("../index").uiState.value)
    }

    @Test
    fun deleteRemovesItAndLeaves() = runTest(dispatcher) {
        val m = model(saved(DocKind.THUMB))
        m.onDelete()
        assertEquals(KitOriginalUiState.Deleted, m.uiState.value)
        assertTrue(kit.originals.isEmpty())
    }
}
