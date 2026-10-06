package app.scanfit.core.data.draft

import app.scanfit.core.data.export.ExportVerifier
import app.scanfit.core.match.DocKind
import app.scanfit.core.model.DocSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
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
import java.security.MessageDigest
import java.util.Base64

class FileDraftStore(
    private val root: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeCommit: (File) -> Unit = {},
) : DraftStore {
    private val mutex = Mutex()
    private val changes = MutableStateFlow(0L)
    override val revisions = changes.asStateFlow()

    override suspend fun read(
        examId: String,
        spec: DocSpec,
        kind: DocKind,
    ): RetainedDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (attempt { safeRoot(create = false) } != true) return@withLock null
            val file = slot(examId, spec)
            if (!Files.exists(file.toPath(), NOFOLLOW_LINKS)) return@withLock null
            val result = attempt { decode(file, spec, kind) }
            if (result == null) remove(file)
            result
        }
    }

    override suspend fun retain(
        examId: String,
        spec: DocSpec,
        kind: DocKind,
        bytes: ByteArray,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            var temporary: File? = null
            try {
                if (bytes.size > MAX_PAYLOAD || !ExportVerifier.verifies(bytes, bytes, spec, kind)) {
                    return@withLock false
                }
                check(safeRoot(create = true))
                val destination = slot(examId, spec)
                check(!Files.isSymbolicLink(destination.toPath()))
                val created = clock()
                val record = buildJsonObject {
                    put("version", 1)
                    put("created_at_ms", created)
                    put("jpeg", Base64.getEncoder().encodeToString(bytes))
                }.toString().toByteArray(Charsets.UTF_8)
                check(record.size <= MAX_RECORD)
                val staged = Files.createTempFile(root.toPath(), ".draft-", ".tmp").toFile()
                temporary = staged
                FileChannel.open(staged.toPath(), WRITE, NOFOLLOW_LINKS).use { channel ->
                    val buffer = ByteBuffer.wrap(record)
                    while (buffer.hasRemaining()) channel.write(buffer)
                    channel.force(true)
                }
                beforeCommit(staged)
                val written = decode(staged, spec, kind)
                check(written.createdAtMillis == created)
                check(ExportVerifier.verifies(bytes, written.bytes, spec, kind))
                currentCoroutineContext().ensureActive()
                check(safeRoot(create = false) && !Files.isSymbolicLink(destination.toPath()))
                Files.move(staged.toPath(), destination.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                changes.value += 1
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            } finally {
                temporary?.let { attempt { Files.deleteIfExists(it.toPath()) } }
            }
        }
    }

    override suspend fun delete(examId: String, spec: DocSpec) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (attempt { safeRoot(create = false) } == true) remove(slot(examId, spec))
        }
    }

    private fun safeRoot(create: Boolean): Boolean {
        if (create && !root.exists()) root.mkdirs()
        return root.isDirectory && root.absoluteFile == root.canonicalFile && !Files.isSymbolicLink(root.toPath())
    }

    private fun slot(examId: String, spec: DocSpec): File {
        val key = Json.encodeToString(listOf(examId, spec.type.name))
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        return File(root, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    private fun decode(file: File, spec: DocSpec, kind: DocKind): RetainedDraft {
        check(Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        val record = FileChannel.open(file.toPath(), READ, NOFOLLOW_LINKS).use { channel ->
            val length = channel.size()
            check(length in 1..MAX_RECORD.toLong())
            val buffer = ByteBuffer.allocate(length.toInt())
            while (buffer.hasRemaining()) check(channel.read(buffer) > 0)
            check(channel.read(ByteBuffer.allocate(1)) == -1)
            buffer.array()
        }
        val fields = Json.parseToJsonElement(record.toString(Charsets.UTF_8)).jsonObject
        check(fields.getValue("version").jsonPrimitive.long == 1L)
        val created = fields.getValue("created_at_ms").jsonPrimitive.long
        val now = clock()
        check(created <= now && created > now - TTL_MILLIS)
        val encoded = fields.getValue("jpeg").jsonPrimitive.content
        check(encoded.length <= MAX_BASE64)
        val bytes = Base64.getDecoder().decode(encoded)
        check(bytes.size <= MAX_PAYLOAD && ExportVerifier.verifies(bytes, bytes, spec, kind))
        return RetainedDraft(bytes, created)
    }

    private fun remove(file: File) {
        if (attempt { Files.deleteIfExists(file.toPath()) } == true) changes.value += 1
    }

    private inline fun <T> attempt(block: () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        const val TTL_MILLIS = 2_592_000_000L
        const val MAX_PAYLOAD = 64 * 1024 * 1024
        const val MAX_RECORD = 96 * 1024 * 1024
        private const val MAX_BASE64 = ((MAX_PAYLOAD + 2) / 3) * 4
    }
}
