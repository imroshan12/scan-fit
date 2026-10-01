package app.scanfit.core.vision

import org.junit.Assert.assertEquals
import org.junit.Test

class FaceCheckTest {
    private val face = FaceBox(10.0, 20.0, 100.0, 120.0)

    @Test
    fun zeroFacesBlocksTwoOrMoreAskForARecropAndOneContinues() {
        assertEquals(FaceCheck.NoFace, FaceCheck.of(emptyList()))
        assertEquals(FaceCheck.Single(face), FaceCheck.of(listOf(face)))
        assertEquals(FaceCheck.Several(2), FaceCheck.of(listOf(face, face)))
        assertEquals(FaceCheck.Several(5), FaceCheck.of(List(5) { face }))
    }

    @Test
    fun theFaceBoxKnowsItsCentre() {
        assertEquals(60.0, face.centerX, 0.0)
    }
}
