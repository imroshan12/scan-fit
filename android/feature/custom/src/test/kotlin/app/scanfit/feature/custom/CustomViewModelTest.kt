package app.scanfit.feature.custom

import androidx.lifecycle.SavedStateHandle
import app.scanfit.core.model.CustomSize
import app.scanfit.core.model.CustomSizeError
import app.scanfit.core.model.DocType
import app.scanfit.core.model.SizeUnit
import app.scanfit.core.testing.FakeUserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

@OptIn(ExperimentalCoroutinesApi::class)
class CustomViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val prefs = FakeUserPreferences()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.model(): CustomViewModel {
        val m = CustomViewModel(prefs)
        backgroundScope.launch(dispatcher) { m.uiState.collect {} }
        return m
    }

    private val CustomViewModel.state get() = uiState.value

    @Test
    fun aHomeCardChoosesTheKindAndAnythingElseFallsBackToPhoto() = runTest(dispatcher) {
        fun opened(doc: String?): DocType {
            val m = CustomViewModel(prefs, SavedStateHandle(listOfNotNull(doc?.let { CustomViewModel.DOC to it }).toMap()))
            backgroundScope.launch(dispatcher) { m.uiState.collect {} }
            return m.state.form.doc
        }
        assertEquals(DocType.SIGNATURE, opened("signature"))
        assertEquals(DocType.THUMB_IMPRESSION, opened("thumb_impression"))
        assertEquals(DocType.HANDWRITTEN_DECLARATION, opened("handwritten_declaration"))
        assertEquals("a type Custom size does not make", DocType.PHOTO, opened("class10_certificate"))
        assertEquals(DocType.PHOTO, opened(null))
    }

    @Test
    fun aFreshFormShowsNoErrorsUntilContinueIsTried() = runTest(dispatcher) {
        val m = model()
        assertEquals(listOf(CustomSizeError.MAX_KB_MISSING), m.state.errors)
        assertFalse("a missing max is not shouted before Continue", m.state.shows(CustomSizeError.MAX_KB_MISSING))
        assertNull(m.onContinue())
        assertTrue(m.state.attempted && m.state.shows(CustomSizeError.MAX_KB_MISSING))
    }

    @Test
    fun fieldsKeepOnlyWhatTheyCanHold() = runTest(dispatcher) {
        val m = model()
        m.onMaxKb("2a0 KB")
        m.onWidth("1,40")
        assertEquals("20", m.state.form.maxKb)
        assertEquals("pixels are whole numbers", "140", m.state.form.width)
        m.onUnit(SizeUnit.CM)
        m.onWidth("3.5.67")
        assertEquals("one decimal point, two decimals", "3.56", m.state.form.width)
    }

    @Test
    fun centimetresShowTheirPixelsAtTheDpi() = runTest(dispatcher) {
        val m = model()
        m.onUnit(SizeUnit.CM)
        m.onWidth("3.5")
        m.onHeight("4.5")
        assertEquals("200 dpi by default", 276 to 354, m.state.pixels)
        m.onDpi("300")
        assertEquals(413 to 531, m.state.pixels)
        m.onUnit(SizeUnit.PX)
        assertNull("no pixel line when typing pixels", m.state.pixels)
    }

    @Test
    fun aRangeErrorShowsOnceItsFieldHasText() = runTest(dispatcher) {
        val m = model()
        m.onWidth("10")
        assertTrue(m.state.shows(CustomSizeError.WIDTH_RANGE))
        assertFalse("half a pair is fine while typing", m.state.shows(CustomSizeError.SIZE_PAIR))
        m.onMaxKb("20")
        m.onMinKb("30")
        assertTrue(m.state.shows(CustomSizeError.MIN_KB_RANGE))
    }

    @Test
    fun continueReturnsTheSizeAndRemembersIt() = runTest(dispatcher) {
        val m = model()
        m.onDoc(DocType.SIGNATURE)
        m.onWidth("140")
        m.onHeight("60")
        m.onMinKb("10")
        m.onMaxKb("20")
        val size = m.onContinue()
        assertEquals("signature;140x60;10-20;200", size?.text)
        advanceUntilIdle()
        assertEquals(listOf("signature;140x60;10-20;200"), prefs.recentCustomSizes.first())
        assertEquals(listOf(size), m.state.recent)
    }

    @Test
    fun aRecentSizeRefillsTheFormInPixels() = runTest(dispatcher) {
        prefs.recentCustomSizes.value = listOf("photo;-;0-50;200", "not a size")
        val m = model()
        assertEquals("unreadable entries are dropped", 1, m.state.recent.size)
        m.onUnit(SizeUnit.CM)
        m.onRecent(checkNotNull(CustomSize.parse("signature;140x60;10-20;300")))
        assertEquals(CustomForm(DocType.SIGNATURE, SizeUnit.PX, "140", "60", "10", "20", "300"), m.state.form)
        assertTrue(m.state.errors.isEmpty())
    }
}
