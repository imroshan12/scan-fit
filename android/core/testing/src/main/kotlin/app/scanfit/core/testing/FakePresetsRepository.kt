package app.scanfit.core.testing

import app.scanfit.core.model.PresetBundle
import app.scanfit.core.presets.PresetsLoadOutcome
import app.scanfit.core.presets.PresetsRepository
import kotlinx.coroutines.flow.MutableStateFlow

/** Test double for [PresetsRepository]: set [outcome] to drive any state without touching crypto or assets. */
class FakePresetsRepository(
    initial: PresetsLoadOutcome? = null,
    bundle: PresetBundle? = null,
) : PresetsRepository {
    override val outcome = MutableStateFlow(initial)
    override val bundle: MutableStateFlow<PresetBundle?> = MutableStateFlow(bundle)

    var loadCalls = 0
        private set

    override suspend fun loadEmbedded(): PresetsLoadOutcome {
        loadCalls++
        return outcome.value ?: PresetsLoadOutcome.MissingEmbedded
    }
}
