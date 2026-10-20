package app.scanfit.core.data.quota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** Where the free-saves state lives (ALGORITHMS 14.4). */
interface QuotaStore {
    /** Applies [transform] to the stored state under one lock and returns the new state. */
    suspend fun update(transform: (QuotaState) -> QuotaState): QuotaState
}

/**
 * One versioned JSON record, `{version: 1, used, allowance, hashes}`, replaced atomically. A missing, corrupt or
 * unknown record reads as the new-install state. [root] is no-backup app storage; the account copy restores the count.
 */
class FileQuotaStore(
    private val root: File,
) : QuotaStore {
    private val mutex = Mutex()
    private var cached: QuotaState? = null
    private val file get() = File(root, FILE)

    override suspend fun update(transform: (QuotaState) -> QuotaState): QuotaState = mutex.withLock {
        withContext(Dispatchers.IO) {
            val current = cached ?: read().also { cached = it }
            val next = transform(current)
            if (next != current) {
                // A failed write keeps the new state for this process; the next launch reads the last good record.
                runCatching { write(next) }
                cached = next
            }
            next
        }
    }

    private fun read(): QuotaState = try {
        val obj = Json.parseToJsonElement(file.readText()).jsonObject
        check(obj.getValue("version").jsonPrimitive.int == VERSION)
        val used = obj.getValue("used").jsonPrimitive.int
        val allowance = obj.getValue("allowance").jsonPrimitive.int
        val hashes = obj.getValue("hashes").jsonArray.map { it.jsonPrimitive.content }
        check(used >= 0 && allowance >= 0 && hashes.all { HASH.matches(it) })
        QuotaState(used, allowance, hashes.takeLast(SaveQuotaRules.MAX_HASHES))
    } catch (_: IOException) {
        QuotaState()
    } catch (_: SerializationException) {
        QuotaState()
    } catch (_: IllegalArgumentException) {
        QuotaState()
    } catch (_: IllegalStateException) {
        QuotaState()
    } catch (_: NoSuchElementException) {
        QuotaState()
    }

    private fun write(state: QuotaState) {
        root.mkdirs()
        val json = buildJsonObject {
            put("version", VERSION)
            put("used", state.used)
            put("allowance", state.allowance)
            put("hashes", JsonArray(state.hashes.map(::JsonPrimitive)))
        }
        val temp = File(root, "$FILE.tmp")
        temp.writeText(json.toString())
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }

    private companion object {
        const val FILE = "quota.json"
        const val VERSION = 1
        val HASH = Regex("[0-9a-f]{64}")
    }
}
