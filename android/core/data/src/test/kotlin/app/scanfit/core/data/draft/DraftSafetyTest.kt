package app.scanfit.core.data.draft

import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import java.nio.file.Files

class DraftSafetyTest {
    @get:Rule val folder = TemporaryFolder()
    private val spec = SpecPresets.bundle.exams.first { it.id == "ibps_po" }.documents.first()
    private val bytes = TestJpeg.make(200, 230, 35 * 1024)

    @Test
    fun missingReadsDoNotEmitRevisionsAndRetainingDoesNotSetSaved() = runTest {
        val store = FileDraftStore(folder.newFolder().canonicalFile)
        repeat(3) { assertNull(store.read("ibps_po", spec, DocKind.PHOTO)) }
        assertEquals(0L, store.revisions.value)
        assertTrue(store.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        assertEquals(1L, store.revisions.value)
        store.delete("ibps_po", spec)
        store.delete("ibps_po", spec)
        assertEquals(2L, store.revisions.value)
    }

    @Test
    fun ttlLastMillisecondPassesBoundaryAndFutureFail() = runTest {
        val root = folder.newFolder().canonicalFile
        var now = 1_800_000_000_000L
        val store = FileDraftStore(root, { now })
        assertTrue(store.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        now += FileDraftStore.TTL_MILLIS - 1
        assertArrayEquals(bytes, requireNotNull(store.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        now++
        assertNull(store.read("ibps_po", spec, DocKind.PHOTO))
    }

    @Test
    fun malformedAndOversizedPayloadsNeverReplaceGoodBytes() = runTest {
        val root = folder.newFolder().canonicalFile
        val store = FileDraftStore(root)
        assertTrue(store.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        assertFalse(store.retain("ibps_po", spec, DocKind.PHOTO, byteArrayOf(1, 2)))
        assertFalse(store.retain("ibps_po", spec, DocKind.PHOTO, ByteArray(FileDraftStore.MAX_PAYLOAD + 1)))
        assertArrayEquals(bytes, requireNotNull(store.read("ibps_po", spec, DocKind.PHOTO)).bytes)
    }

    @Test
    fun oversizedSerializedRecordIsRemovedWithoutReadingItsPayload() = runTest {
        val root = folder.newFolder().canonicalFile
        val store = FileDraftStore(root)
        assertTrue(store.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        RandomAccessFile(root.listFiles().orEmpty().single(), "rw").use {
            it.setLength(FileDraftStore.MAX_RECORD.toLong() + 1)
        }
        assertNull(store.read("ibps_po", spec, DocKind.PHOTO))
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun symlinkSlotIsRejectedWithoutTouchingItsTarget() = runTest {
        val root = folder.newFolder().canonicalFile
        val store = FileDraftStore(root)
        assertTrue(store.retain("../outside", spec, DocKind.PHOTO, bytes))
        val record = root.listFiles().orEmpty().single()
        assertTrue(record.name.matches(Regex("[a-f0-9]{64}\\.json")))
        val outside = folder.newFile().canonicalFile
        outside.writeBytes(record.readBytes())
        record.delete()
        Files.createSymbolicLink(record.toPath(), outside.toPath())
        assertFalse(store.retain("../outside", spec, DocKind.PHOTO, bytes))
        assertNull(store.read("../outside", spec, DocKind.PHOTO))
        assertTrue(outside.exists())
        assertFalse(Files.exists(record.toPath()))
    }

    @Test
    fun symlinkRootNeverCreatesRecordsInItsTarget() = runTest {
        val target = folder.newFolder().canonicalFile
        val root = folder.newFolder().canonicalFile.resolve("drafts")
        Files.createSymbolicLink(root.toPath(), target.toPath())
        val store = FileDraftStore(root)
        assertFalse(store.retain("ibps_po", spec, DocKind.PHOTO, bytes))
        assertNull(store.read("ibps_po", spec, DocKind.PHOTO))
        assertTrue(target.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun corruptedStagingReadPreservesOldRecordAndRemovesTemporaryFile() = runTest {
        val root = folder.newFolder().canonicalFile
        assertTrue(FileDraftStore(root).retain("ibps_po", spec, DocKind.PHOTO, bytes))
        val failing = FileDraftStore(root, beforeCommit = { it.writeText("{broken") })
        assertFalse(failing.retain("ibps_po", spec, DocKind.PHOTO, TestJpeg.make(200, 230, 36 * 1024)))
        assertArrayEquals(bytes, requireNotNull(failing.read("ibps_po", spec, DocKind.PHOTO)).bytes)
        assertEquals(1, root.listFiles().orEmpty().size)
    }

    @Test
    fun cancelledStagingNeverCommitsOrEmitsRevision() = runTest {
        val root = folder.newFolder().canonicalFile
        val store = FileDraftStore(root, beforeCommit = { throw CancellationException("obsolete render") })
        try {
            store.retain("ibps_po", spec, DocKind.PHOTO, bytes)
            error("cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(0L, store.revisions.value)
            assertTrue(root.listFiles().orEmpty().isEmpty())
        }
    }
}
