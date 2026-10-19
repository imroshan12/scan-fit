package app.scanfit.core.data.kit

import app.scanfit.core.data.export.ExportRequest
import app.scanfit.core.inspect.Inspector
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE

/**
 * My Kit on disk (ALGORITHMS 13.1): `index.json` (metadata only) and the bytes in `originals/<id>.jpg` and
 * `exports/<id>.jpg`. Bytes are staged, re-read and verified, then renamed into place before the index names them, all
 * under one lock; loading drops bad entries and removes files no entry names. [root] is app-private no-backup storage.
 */
@Suppress("TooManyFunctions")
class FileKitStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = KitIds::newId,
    /** Test hook: runs between the blob rename and the index write (simulates process death there). */
    private val beforeIndex: () -> Unit = {},
) : KitStore {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    override val revisions = changes.asStateFlow()

    private val originalsDir get() = File(root, ORIGINALS)
    private val exportsDir get() = File(root, EXPORTS)
    private val indexFile get() = File(root, INDEX)

    private data class Index(
        val originals: List<KitOriginal>,
        val exports: List<ExportRecord>,
    )

    override suspend fun originals(): List<KitOriginal> = locked {
        load().originals.sortedByDescending { it.createdAtMillis }
    }

    override suspend fun saveOriginal(
        kind: DocKind,
        jpeg: ByteArray,
    ): SaveOriginalResult = try {
        locked {
            val file = Inspector.inspect(jpeg)
            val width = file.width ?: 0
            val height = file.height ?: 0
            check(kind != DocKind.PDF_DOCUMENT && kitOriginalReadable(jpeg, width, height))
            val index = load()
            if (index.originals.size >= KitStore.MAX_ORIGINALS) return@locked SaveOriginalResult.Full
            val id = newId()
            check(KitIds.isValid(id))
            writeBlob(originalsDir, id, jpeg) { kitOriginalReadable(it, width, height) }
            beforeIndex()
            // A failed index write leaves the blob unnamed: the next load removes it.
            val entry = KitOriginal(id, kind, clock(), width, height, jpeg.size)
            writeIndex(index.copy(originals = index.originals + entry))
            changes.value += 1
            SaveOriginalResult.Saved(id)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SaveOriginalResult.Failed
    }

    override suspend fun original(id: String): ByteArray? = attempt {
        if (!KitIds.isValid(id)) return@attempt null
        locked {
            val index = load()
            val entry = index.originals.firstOrNull { it.id == id } ?: return@locked null
            val bytes = attempt { readBlob(originalsDir, id, entry.size) }
            if (bytes != null && kitOriginalReadable(bytes, entry.width, entry.height)) {
                bytes
            } else {
                writeIndex(index.copy(originals = index.originals - entry))
                attempt { Files.deleteIfExists(blob(originalsDir, id).toPath()) }
                changes.value += 1
                null
            }
        }
    }

    override suspend fun deleteOriginal(id: String) {
        if (!KitIds.isValid(id)) return
        attempt {
            locked {
                val index = load()
                val remaining = index.originals.filterNot { it.id == id }
                if (remaining.size != index.originals.size) writeIndex(index.copy(originals = remaining))
                Files.deleteIfExists(blob(originalsDir, id).toPath())
                changes.value += 1
            }
        }
    }

    override suspend fun history(): List<HistoryEntry> = locked {
        val now = clock()
        load().exports.let { ExportHistory.prune(it, now) }
            .map { it.copy(shareable = it.shareable && copy(it.record).isFile) }
    }

    override suspend fun recordExport(request: ExportRequest): Boolean = attempt {
        locked {
            val bytes = request.bytes
            val file = Inspector.inspect(bytes)
            val width = file.width ?: 0
            val height = file.height ?: 0
            val pages = request.pdf?.pages
            val readable =
                if (pages != null) pages >= 1 && kitPdfReadable(bytes, bytes.size) else kitJpegReadable(bytes, width, height)
            check(bytes.size <= KitStore.MAX_COPY_BYTES && readable)
            val index = load()
            val id = newId()
            check(KitIds.isValid(id))
            val record = ExportRecord(
                id, clock(), request.examId, request.examName, request.spec.type, request.kind,
                request.fileName, bytes.size, if (pages != null) 0 else width, if (pages != null) 0 else height, pages,
            )
            writeBlob(copy(record), bytes) { it.contentEquals(bytes) }
            beforeIndex()
            writeIndex(index.copy(exports = index.exports + record))
            // Pruning on the next load drops entries past the newest 100 and their copies.
            load()
            changes.value += 1
            true
        }
    } == true

    override suspend fun exportCopy(id: String): ByteArray? = attempt {
        if (!KitIds.isValid(id)) return@attempt null
        locked {
            val entry = load().exports.firstOrNull { it.id == id } ?: return@locked null
            if (!ExportHistory.shareable(entry.createdAtMillis, clock())) return@locked null
            val bytes = attempt { readFile(copy(entry), entry.size.toLong()).also { check(it.size == entry.size) } }
            if (bytes != null && readable(entry, bytes)) {
                bytes
            } else {
                attempt { Files.deleteIfExists(copy(entry).toPath()) }
                changes.value += 1
                null
            }
        }
    }

    override suspend fun clearHistory() {
        attempt {
            locked {
                val index = load()
                writeIndex(index.copy(exports = emptyList()))
                load()
                changes.value += 1
            }
        }
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) { mutex.withLock { block() } }

    /**
     * The index with every bad entry dropped, history pruned (§13.3) and every file no entry names removed. Writes the
     * cleaned index back when anything was dropped. Throws when the root is unsafe or cannot be created.
     */
    private fun load(): Index {
        check(safeRoot())
        // A missing, corrupt or unsupported index counts as empty and is replaced; its files become orphans.
        val parsed = attempt { parse(readIndex()) }
        val now = clock()
        val originals = parsed?.originals.orEmpty().distinctBy { it.id }
        val exports = parsed?.exports.orEmpty().distinctBy { it.id }
        val kept = ExportHistory.prune(exports, now).map { it.record.id }.toSet()
        val keptExports = exports.filter { it.id in kept }
        val clean = Index(originals, keptExports)
        if (clean != parsed) writeIndex(clean)
        sweep(originalsDir, originals.map { "${it.id}.jpg" }.toSet())
        val copies = keptExports.filter { ExportHistory.shareable(it.createdAtMillis, now) }.map { it.copyName }.toSet()
        sweep(exportsDir, copies)
        sweep(root, setOf(INDEX, ORIGINALS, EXPORTS))
        return clean
    }

    /** Removes every file in [dir] whose name is not in [keep] (temporary files included). */
    private fun sweep(
        dir: File,
        keep: Set<String>,
    ) {
        val names = dir.list() ?: return
        for (name in names) {
            if (name !in keep) {
                val path = File(dir, name).toPath()
                if (!Files.isDirectory(path, NOFOLLOW_LINKS)) attempt { Files.deleteIfExists(path) }
            }
        }
    }

    private fun safeRoot(): Boolean {
        for (dir in listOf(root, originalsDir, exportsDir)) {
            if (!dir.exists()) dir.mkdirs()
            if (!dir.isDirectory || Files.isSymbolicLink(dir.toPath()) || dir.absoluteFile != dir.canonicalFile) {
                return false
            }
        }
        return true
    }

    private fun blob(
        dir: File,
        id: String,
    ): File {
        check(KitIds.isValid(id))
        return File(dir, "$id.jpg")
    }

    private fun readable(
        entry: ExportRecord,
        bytes: ByteArray,
    ) = if (entry.isPdf) kitPdfReadable(bytes, entry.size) else kitJpegReadable(bytes, entry.width, entry.height)

    /** An export's private copy (`exports/<id>.jpg` or `.pdf`). */
    private fun copy(record: ExportRecord): File {
        check(KitIds.isValid(record.id))
        return File(exportsDir, record.copyName)
    }

    private fun writeBlob(
        dir: File,
        id: String,
        bytes: ByteArray,
        verifies: (ByteArray) -> Boolean,
    ) = writeBlob(blob(dir, id), bytes, verifies)

    private fun writeBlob(
        destination: File,
        bytes: ByteArray,
        verifies: (ByteArray) -> Boolean,
    ) {
        val staged = Files.createTempFile(destination.toPath().parent, ".kit-", ".tmp").toFile()
        try {
            writeFile(staged, bytes)
            check(verifies(readFile(staged, bytes.size.toLong())))
            check(!Files.exists(destination.toPath(), NOFOLLOW_LINKS))
            Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE)
        } finally {
            attempt { Files.deleteIfExists(staged.toPath()) }
        }
    }

    private fun readBlob(
        dir: File,
        id: String,
        size: Int,
    ): ByteArray = readFile(blob(dir, id), size.toLong()).also { check(it.size == size) }

    private fun readIndex(): ByteArray? {
        if (!Files.exists(indexFile.toPath(), NOFOLLOW_LINKS)) return null
        return readFile(indexFile, MAX_INDEX)
    }

    private fun writeIndex(index: Index) {
        val text = buildJsonObject {
            put("version", 1)
            put("originals", JsonArray(index.originals.map { it.toJson() }))
            put("exports", JsonArray(index.exports.map { it.toJson() }))
        }.toString().toByteArray(Charsets.UTF_8)
        check(text.size <= MAX_INDEX)
        val staged = Files.createTempFile(root.toPath(), ".index-", ".tmp").toFile()
        try {
            writeFile(staged, text)
            check(!Files.isSymbolicLink(indexFile.toPath()))
            Files.move(staged.toPath(), indexFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } finally {
            attempt { Files.deleteIfExists(staged.toPath()) }
        }
    }

    private fun parse(bytes: ByteArray?): Index? {
        bytes ?: return null
        val fields = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        check(fields.getValue("version").jsonPrimitive.int == 1)
        val originals = fields.getValue("originals").jsonArray.mapNotNull { attempt { original(it.jsonObject) } }
        val exports = fields.getValue("exports").jsonArray.mapNotNull { attempt { export(it.jsonObject) } }
        return Index(originals, exports)
    }

    private fun original(o: JsonObject): KitOriginal? {
        val entry = KitOriginal(
            id = o.text("id"),
            kind = DocKind.valueOf(o.text("kind").uppercase()),
            createdAtMillis = o.getValue("created_at_ms").jsonPrimitive.long,
            width = o.getValue("width").jsonPrimitive.int,
            height = o.getValue("height").jsonPrimitive.int,
            size = o.getValue("size").jsonPrimitive.int,
        )
        val sane = KitIds.isValid(entry.id) && entry.kind != DocKind.PDF_DOCUMENT && entry.createdAtMillis >= 0 &&
            entry.width > 0 && entry.height > 0 && entry.size in 1..KitStore.MAX_ORIGINAL_BYTES
        return entry.takeIf { sane }
    }

    private fun export(o: JsonObject): ExportRecord? {
        val entry = ExportRecord(
            id = o.text("id"),
            createdAtMillis = o.getValue("created_at_ms").jsonPrimitive.long,
            examId = o.text("exam_id"),
            examName = o.text("exam_name"),
            docType = DocType.valueOf(o.text("doc_type").uppercase()),
            kind = DocKind.valueOf(o.text("kind").uppercase()),
            fileName = o.text("file_name"),
            size = o.getValue("size").jsonPrimitive.int,
            width = o.getValue("width").jsonPrimitive.int,
            height = o.getValue("height").jsonPrimitive.int,
            pages = if (o.format() == PDF) o.getValue("pages").jsonPrimitive.int else null,
        )
        check(o.format() in setOf(JPG, PDF))
        val shape =
            entry.pages?.let { it >= 1 && entry.width == 0 && entry.height == 0 } ?: (entry.width > 0 && entry.height > 0)
        val sane = KitIds.isValid(entry.id) && entry.createdAtMillis >= 0 && shape &&
            entry.size in 1..KitStore.MAX_COPY_BYTES && entry.examId.isNotEmpty() && entry.fileName.isNotEmpty()
        return entry.takeIf { sane }
    }

    private fun KitOriginal.toJson() = buildJsonObject {
        put("id", id)
        put("kind", kind.name.lowercase())
        put("created_at_ms", createdAtMillis)
        put("width", width)
        put("height", height)
        put("size", size)
    }

    private fun ExportRecord.toJson() = buildJsonObject {
        put("id", id)
        put("created_at_ms", createdAtMillis)
        put("exam_id", examId)
        put("exam_name", examName)
        put("doc_type", docType.name.lowercase())
        put("kind", kind.name.lowercase())
        put("file_name", fileName)
        put("size", size)
        put("width", width)
        put("height", height)
        put("format", if (isPdf) PDF else JPG)
        pages?.let { put("pages", it) }
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    /** An export's `format`; entries written before PDFs have none and are JPEGs. */
    private fun JsonObject.format(): String = this["format"]?.jsonPrimitive?.content ?: JPG

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val INDEX = "index.json"
        const val ORIGINALS = "originals"
        const val EXPORTS = "exports"
        const val JPG = "jpg"
        const val PDF = "pdf"
        const val MAX_INDEX = 4L * 1024 * 1024

        fun writeFile(
            file: File,
            bytes: ByteArray,
        ) {
            FileChannel.open(file.toPath(), WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }

        /** The whole regular file, refusing symlinks and anything longer than [limit]. */
        fun readFile(
            file: File,
            limit: Long,
        ): ByteArray {
            check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
            return FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
                val length = channel.size()
                check(length in 1..limit)
                val buffer = ByteBuffer.allocate(length.toInt())
                while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
                check(channel.read(ByteBuffer.allocate(1)) == -1)
                buffer.array()
            }
        }
    }
}
