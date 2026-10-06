package app.scanfit.core.data.draft

import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FileDraftStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()
    private val bytes = TestJpeg.make(200, 230, 35 * 1024)

    @Test
    fun reloadedInstanceReturnsExactlyTheVerifiedBytes() = runTest {
        val root = folder.newFolder().canonicalFile
        assertTrue(FileDraftStore(root).retain("ibps_po", spec, DocKind.PHOTO, bytes))
        val restored = requireNotNull(FileDraftStore(root).read("ibps_po", spec, DocKind.PHOTO))
        assertArrayEquals(bytes, restored.bytes)
    }

    @Test
    fun failedReplacementPreservesThePreviousGoodRecord() = runTest {
        val root = folder.newFolder().canonicalFile
        assertTrue(FileDraftStore(root).retain("ibps_po", spec, DocKind.PHOTO, bytes))
        val failing = FileDraftStore(root, beforeCommit = { throw IOException("injected failure") })
        assertFalse(failing.retain("ibps_po", spec, DocKind.PHOTO, TestJpeg.make(200, 230, 36 * 1024)))
        assertArrayEquals(bytes, requireNotNull(failing.read("ibps_po", spec, DocKind.PHOTO)).bytes)
    }
}
