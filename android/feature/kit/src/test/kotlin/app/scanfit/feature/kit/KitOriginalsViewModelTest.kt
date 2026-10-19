package app.scanfit.feature.kit

import app.cash.turbine.ReceiveTurbine
import app.cash.turbine.test
import app.scanfit.core.data.kit.SaveOriginalResult
import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.FakeKitStore
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KitOriginalsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val kit = FakeKitStore()
    private val images = FakeKitImages()
    private val picture = TestJpeg.make(1600, 1200, 4096)

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun model() = KitOriginalsViewModel(kit, images, dispatcher)

    private suspend fun ReceiveTurbine<KitOriginalsUiState>.resolved(): KitOriginalsUiState {
        var state = awaitItem()
        while (state == KitOriginalsUiState.Loading) state = awaitItem()
        return state
    }

    private suspend fun save(kind: DocKind): String {
        kit.now += 1
        return (kit.saveOriginal(kind, picture) as SaveOriginalResult.Saved).id
    }

    @Test
    fun anEmptyKitSaysSo() = runTest(dispatcher) {
        model().uiState.test { assertEquals(KitOriginalsUiState.Empty, resolved()) }
    }

    @Test
    fun originalsAreGroupedByKindInSpecOrderNewestFirst() = runTest(dispatcher) {
        val declaration = save(DocKind.DECLARATION)
        val oldPhoto = save(DocKind.PHOTO)
        val signature = save(DocKind.SIGNATURE)
        val newPhoto = save(DocKind.PHOTO)
        model().uiState.test {
            val ready = resolved() as KitOriginalsUiState.Ready
            assertEquals(listOf(DocKind.PHOTO, DocKind.SIGNATURE, DocKind.DECLARATION), ready.groups.map { it.kind })
            assertEquals(listOf(newPhoto, oldPhoto), ready.groups[0].originals.map { it.id })
            assertEquals(listOf(signature), ready.groups[1].originals.map { it.id })
            assertEquals(listOf(declaration), ready.groups[2].originals.map { it.id })
        }
    }

    @Test
    fun aNewOriginalShowsWithoutARefresh() = runTest(dispatcher) {
        model().uiState.test {
            assertEquals(KitOriginalsUiState.Empty, resolved())
            val id = save(DocKind.THUMB)
            val ready = resolved() as KitOriginalsUiState.Ready
            assertEquals(id, ready.groups.single().originals.single().id)
        }
    }

    @Test
    fun aStorageFailureIsRetryableNotEmpty() = runTest(dispatcher) {
        save(DocKind.PHOTO)
        kit.failReads = true
        val m = model()
        m.uiState.test {
            assertEquals(KitOriginalsUiState.Error, resolved())
            kit.failReads = false
            m.refresh()
            assertEquals(1, (resolved() as KitOriginalsUiState.Ready).groups.size)
        }
    }

    @Test
    fun thumbnailsAreDecodedSmall() = runTest(dispatcher) {
        val id = save(DocKind.PHOTO)
        val m = model()
        val thumbnail = m.thumbnail(id)
        assertEquals(KitOriginalsViewModel.THUMBNAIL_LONG_SIDE, thumbnail?.width)
        assertEquals(listOf(KitOriginalsViewModel.THUMBNAIL_LONG_SIDE), images.caps)
        assertNull(m.thumbnail(kit.newId()))
    }
}
