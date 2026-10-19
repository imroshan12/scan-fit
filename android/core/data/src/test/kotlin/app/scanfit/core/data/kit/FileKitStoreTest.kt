package app.scanfit.core.data.kit

import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.data.export.PdfExport
import app.scanfit.core.match.DocKind
import app.scanfit.core.testing.SpecPresets
import app.scanfit.core.testing.TestJpeg
import app.scanfit.core.testing.TestPdf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class FileKitStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val exam = SpecPresets.bundle.exams.first { it.id == "ibps_po" }
    private val spec = exam.documents.first()
    private val picture = TestJpeg.make(1600, 1200, 300 * 1024)
    private val fitted = TestJpeg.make(200, 230, 35 * 1024)
    private var now = 1_800_000_000_000L

    private fun store(root: File, beforeIndex: () -> Unit = {}) = FileKitStore(root, { now }, beforeIndex = beforeIndex)

    private fun request(bytes: ByteArray = fitted) = ExportRequest(exam.id, exam.name, spec, DocKind.PHOTO, bytes)

    @Test
    fun aPdfExportKeepsAPdfCopyWithItsPageCount() = runTest {
        val root = folder.newFolder().canonicalFile
        val jee = SpecPresets.bundle.exams.first { it.id == "jee_main" }
        val slot = jee.documents.first { it.type.name == "CLASS10_CERTIFICATE" }
        val pdf = TestPdf.make(3, 120 * 1024)
        val request = ExportRequest(jee.id, jee.name, slot, DocKind.PDF_DOCUMENT, pdf, pdf = PdfExport(3, written = true))
        assertTrue(store(root).recordExport(request))
        val reopened = store(root)
        val entry = reopened.history().single()
        assertEquals(3, entry.record.pages)
        assertEquals(0, entry.record.width)
        assertEquals("class10_certificate_JEE-Main_120kb.pdf", entry.record.fileName)
        assertTrue(entry.shareable)
        assertTrue(File(root, "exports/${entry.record.id}.pdf").isFile)
        assertArrayEquals(pdf, reopened.exportCopy(entry.record.id))
        // A JPEG copy for the same id would be an orphan: only the entry's own copy name is kept.
        File(root, "exports/${entry.record.id}.jpg").writeBytes(fitted)
        store(root).history()
        assertFalse(File(root, "exports/${entry.record.id}.jpg").exists())
    }

    @Test
    fun aPdfWithAPasswordIsNotKept() = runTest {
        val jee = SpecPresets.bundle.exams.first { it.id == "jee_main" }
        val slot = jee.documents.first { it.type.name == "CLASS10_CERTIFICATE" }
        val locked = TestPdf.make(1, 90 * 1024, encrypted = true)
        val request = ExportRequest(jee.id, jee.name, slot, DocKind.PDF_DOCUMENT, locked, pdf = PdfExport(1, written = false))
        val kit = store(folder.newFolder().canonicalFile)
        assertFalse(kit.recordExport(request))
        assertTrue(kit.history().isEmpty())
    }

    @Test
    fun entriesFromBeforePdfsStillLoadAndBadPdfEntriesAreDropped() = runTest {
        val root = folder.newFolder().canonicalFile
        File(root, "exports").mkdirs()
        val old = "3f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"
        val bad = "4f2b8c1e-9a4d-4e6f-8b2a-1c3d5e7f9a0b"
        val common = """"created_at_ms":$now,"exam_id":"ibps_po","exam_name":"IBPS PO / MT","doc_type":"photo","kind":"photo","""
        File(root, "index.json").writeText(
            """{"version":1,"originals":[],"exports":[""" +
                """{"id":"$old",$common"file_name":"a.jpg","size":${fitted.size},"width":200,"height":230},""" +
                """{"id":"$bad",$common"file_name":"b.pdf","size":10,"width":200,"height":230,"format":"pdf","pages":1}]}""",
        )
        File(root, "exports/$old.jpg").writeBytes(fitted)
        val history = store(root).history()
        assertEquals(listOf(old), history.map { it.record.id })
        assertNull(history.single().record.pages)
        assertArrayEquals(fitted, store(root).exportCopy(old))
    }

    @Test
    fun savedOriginalSurvivesARelaunchByteForByte() = runTest {
        val root = folder.newFolder().canonicalFile
        val saved = store(root).saveOriginal(DocKind.SIGNATURE, picture) as SaveOriginalResult.Saved
        val reopened = store(root)
        val listed = reopened.originals().single()
        assertEquals(KitOriginal(saved.id, DocKind.SIGNATURE, now, 1600, 1200, picture.size), listed)
        assertArrayEquals(picture, reopened.original(saved.id))
    }

    @Test
    fun originalsAreNewestFirst() = runTest {
        val kit = store(folder.newFolder().canonicalFile)
        val first = kit.saveOriginal(DocKind.PHOTO, picture) as SaveOriginalResult.Saved
        now += 1
        val second = kit.saveOriginal(DocKind.PHOTO, picture) as SaveOriginalResult.Saved
        assertEquals(listOf(second.id, first.id), kit.originals().map { it.id })
    }

    @Test
    fun aFullKitWritesNothing() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        repeat(KitStore.MAX_ORIGINALS) { assertTrue(kit.saveOriginal(DocKind.PHOTO, picture) is SaveOriginalResult.Saved) }
        assertEquals(SaveOriginalResult.Full, kit.saveOriginal(DocKind.PHOTO, picture))
        assertEquals(KitStore.MAX_ORIGINALS, File(root, "originals").list()?.size)
    }

    @Test
    fun picturesThatAreNotCleanOriginalsAreRefused() = runTest {
        val kit = store(folder.newFolder().canonicalFile)
        val exif = picture.take(2).toByteArray() + byteArrayOf(0xff.toByte(), 0xe1.toByte(), 0, 8, 69, 120, 105, 102, 0, 0) +
            picture.drop(2).toByteArray()
        assertEquals(SaveOriginalResult.Failed, kit.saveOriginal(DocKind.PHOTO, exif))
        assertEquals(SaveOriginalResult.Failed, kit.saveOriginal(DocKind.PHOTO, TestJpeg.make(2401, 100, 10 * 1024)))
        assertEquals(SaveOriginalResult.Failed, kit.saveOriginal(DocKind.PDF_DOCUMENT, picture))
        assertTrue(kit.originals().isEmpty())
    }

    @Test
    fun deathBetweenBlobAndIndexLeavesNoOrphan() = runTest {
        val root = folder.newFolder().canonicalFile
        val dying = store(root) { throw IOException("process death") }
        assertEquals(SaveOriginalResult.Failed, dying.saveOriginal(DocKind.PHOTO, picture))
        assertEquals(1, File(root, "originals").list()?.size)
        assertTrue(store(root).originals().isEmpty())
        assertEquals(0, File(root, "originals").list()?.size)
    }

    @Test
    fun aDamagedOriginalIsRemovedWhenRead() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        val saved = kit.saveOriginal(DocKind.PHOTO, picture) as SaveOriginalResult.Saved
        File(root, "originals/${saved.id}.jpg").writeBytes(TestJpeg.make(1600, 1199, picture.size))
        assertNull(kit.original(saved.id))
        assertTrue(kit.originals().isEmpty())
    }

    @Test
    fun badIdsNeverReachThePath() = runTest {
        val root = folder.newFolder().canonicalFile
        File(root, "secret.jpg").writeBytes(picture)
        val kit = store(root)
        assertNull(kit.original("../secret"))
        kit.deleteOriginal("../secret")
        assertNull(kit.exportCopy("../secret"))
    }

    @Test
    fun aCorruptIndexCountsAsEmptyAndIsReplaced() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        kit.saveOriginal(DocKind.PHOTO, picture)
        File(root, "index.json").writeText("{not json")
        assertTrue(store(root).originals().isEmpty())
        assertEquals(0, File(root, "originals").list()?.size)
        assertTrue(store(root).saveOriginal(DocKind.PHOTO, picture) is SaveOriginalResult.Saved)
    }

    @Test
    fun deleteRemovesEntryAndBytes() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        val saved = kit.saveOriginal(DocKind.THUMB, picture) as SaveOriginalResult.Saved
        kit.deleteOriginal(saved.id)
        assertTrue(kit.originals().isEmpty())
        assertFalse(File(root, "originals/${saved.id}.jpg").exists())
    }

    @Test
    fun exportsAreRecordedWithAShareableCopy() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        assertTrue(kit.recordExport(request()))
        val entry = store(root).history().single()
        assertTrue(entry.shareable)
        assertEquals(exam.id, entry.record.examId)
        assertEquals(spec.type, entry.record.docType)
        assertEquals(request().fileName, entry.record.fileName)
        assertEquals(200 to 230, entry.record.width to entry.record.height)
        assertArrayEquals(fitted, kit.exportCopy(entry.record.id))
    }

    @Test
    fun copiesExpireAfterThirtyDaysButTheEntryStays() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        kit.recordExport(request())
        now += ExportHistory.SHARE_WINDOW_MILLIS
        val entry = kit.history().single()
        assertFalse(entry.shareable)
        assertNull(kit.exportCopy(entry.record.id))
        assertEquals(0, File(root, "exports").list()?.size)
    }

    @Test
    fun historyKeepsTheNewestHundred() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        repeat(ExportHistory.MAX_ENTRIES + 2) {
            now += 1
            assertTrue(kit.recordExport(request()))
        }
        val history = kit.history()
        assertEquals(ExportHistory.MAX_ENTRIES, history.size)
        assertEquals(now, history.first().record.createdAtMillis)
        assertEquals(ExportHistory.MAX_ENTRIES, File(root, "exports").list()?.size)
    }

    @Test
    fun aCopyThatNoLongerMatchesIsNotShared() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        kit.recordExport(request())
        val id = kit.history().single().record.id
        File(root, "exports/$id.jpg").writeBytes(TestJpeg.make(200, 231, fitted.size))
        assertNull(kit.exportCopy(id))
    }

    @Test
    fun clearHistoryKeepsOriginals() = runTest {
        val root = folder.newFolder().canonicalFile
        val kit = store(root)
        kit.saveOriginal(DocKind.PHOTO, picture)
        kit.recordExport(request())
        kit.clearHistory()
        assertTrue(kit.history().isEmpty())
        assertEquals(0, File(root, "exports").list()?.size)
        assertEquals(1, kit.originals().size)
    }

    @Test
    fun aSymlinkedRootIsNotUsed() = runTest {
        val real = folder.newFolder().canonicalFile
        val link = File(folder.root.canonicalFile, "link")
        Files.createSymbolicLink(link.toPath(), real.toPath())
        val kit = store(link)
        assertEquals(SaveOriginalResult.Failed, kit.saveOriginal(DocKind.PHOTO, picture))
        assertFalse(kit.recordExport(request()))
    }

    @Test
    fun revisionsAdvanceOnEveryChange() = runTest {
        val kit = store(folder.newFolder().canonicalFile)
        val start = kit.revisions.value
        val saved = kit.saveOriginal(DocKind.PHOTO, picture) as SaveOriginalResult.Saved
        kit.recordExport(request())
        kit.deleteOriginal(saved.id)
        assertEquals(start + 3, kit.revisions.value)
    }
}
