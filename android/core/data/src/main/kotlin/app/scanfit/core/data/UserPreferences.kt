package app.scanfit.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException

/** Small user choices that outlive the app process (UI_UX §2: "My exams", Settings "Show unverified exams"). */
interface UserPreferences {
    /** Pinned exam ids, most recently pinned first. */
    val pinnedExamIds: Flow<List<String>>

    /** Low-confidence presets are hidden until the user opts in (PRD F1, CLAUDE.md rule 6). */
    val showUnverified: Flow<Boolean>

    val savedDocuments: Flow<Set<SavedDocument>>

    suspend fun recordSaved(examId: String, docType: String)

    suspend fun setPinned(
        examId: String,
        pinned: Boolean,
    )

    suspend fun setShowUnverified(show: Boolean)

    /** The one-time "I signed in running handwriting, not CAPITAL letters" tick (ALGORITHMS 9.5). */
    val handwritingConfirmed: Flow<Boolean>

    suspend fun confirmHandwriting()
}

data class SavedDocument(val examId: String, val docType: String)

/** [UserPreferences] in a Preferences DataStore. A corrupt or unreadable file reads as defaults instead of crashing. */
class DataStoreUserPreferences(
    private val store: DataStore<Preferences>,
) : UserPreferences {
    private val data: Flow<Preferences> =
        store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    override val pinnedExamIds: Flow<List<String>> = data.map { decode(it[PINNED]) }.distinctUntilChanged()

    override val showUnverified: Flow<Boolean> =
        data.map { it[SHOW_UNVERIFIED] ?: SHOW_UNVERIFIED_DEFAULT }.distinctUntilChanged()

    override val savedDocuments: Flow<Set<SavedDocument>> = data.map { prefs ->
        prefs[SAVED_DOCUMENTS].orEmpty().mapNotNull { raw ->
            val parts = raw.split(":")
            if (parts.size == 2) SavedDocument(parts[0], parts[1]) else null
        }.toSet()
    }.distinctUntilChanged()

    override suspend fun recordSaved(examId: String, docType: String) {
        require(examId.matches(Regex("[a-z0-9_]+")) && docType.matches(Regex("[A-Z_0-9]+")))
        store.edit { prefs ->
            prefs[SAVED_DOCUMENTS] = prefs[SAVED_DOCUMENTS].orEmpty() + "$examId:$docType"
        }
    }

    override suspend fun setPinned(
        examId: String,
        pinned: Boolean,
    ) {
        store.edit { prefs ->
            val others = decode(prefs[PINNED]).filter { it != examId }
            prefs[PINNED] = (if (pinned) listOf(examId) + others else others).joinToString(SEPARATOR)
        }
    }

    override suspend fun setShowUnverified(show: Boolean) {
        store.edit { it[SHOW_UNVERIFIED] = show }
    }

    override val handwritingConfirmed: Flow<Boolean> =
        data.map { it[HANDWRITING_CONFIRMED] ?: false }.distinctUntilChanged()

    override suspend fun confirmHandwriting() {
        store.edit { it[HANDWRITING_CONFIRMED] = true }
    }

    private companion object {
        // Exam ids match ^[a-z0-9_]+$ (spec/schema), so a comma can never be part of one.
        const val SEPARATOR = ","
        val PINNED = stringPreferencesKey("pinned_exam_ids")
        val SHOW_UNVERIFIED = booleanPreferencesKey("show_unverified_exams")
        val SAVED_DOCUMENTS = stringSetPreferencesKey("saved_documents")
        val HANDWRITING_CONFIRMED = booleanPreferencesKey("handwriting_confirmed")

        /** On by default (ALGORITHMS §10): an unverified exam is listed with its badge rather than missing. */
        const val SHOW_UNVERIFIED_DEFAULT = true

        fun decode(raw: String?): List<String> = raw.orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
    }
}
