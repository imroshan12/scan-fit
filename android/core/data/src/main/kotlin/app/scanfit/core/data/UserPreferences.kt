package app.scanfit.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
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

    suspend fun setPinned(
        examId: String,
        pinned: Boolean,
    )

    suspend fun setShowUnverified(show: Boolean)
}

/** [UserPreferences] in a Preferences DataStore. A corrupt or unreadable file reads as defaults instead of crashing. */
class DataStoreUserPreferences(
    private val store: DataStore<Preferences>,
) : UserPreferences {
    private val data: Flow<Preferences> =
        store.data.catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }

    override val pinnedExamIds: Flow<List<String>> = data.map { decode(it[PINNED]) }.distinctUntilChanged()

    override val showUnverified: Flow<Boolean> = data.map { it[SHOW_UNVERIFIED] ?: false }.distinctUntilChanged()

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

    private companion object {
        // Exam ids match ^[a-z0-9_]+$ (spec/schema), so a comma can never be part of one.
        const val SEPARATOR = ","
        val PINNED = stringPreferencesKey("pinned_exam_ids")
        val SHOW_UNVERIFIED = booleanPreferencesKey("show_unverified_exams")

        fun decode(raw: String?): List<String> = raw.orEmpty().split(SEPARATOR).filter { it.isNotEmpty() }
    }
}
