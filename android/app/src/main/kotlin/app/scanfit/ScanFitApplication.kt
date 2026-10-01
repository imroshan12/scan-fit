package app.scanfit

import android.app.Application
import app.scanfit.core.presets.PresetsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ScanFitApplication : Application() {
    @Inject
    lateinit var presets: PresetsRepository

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        // Verify the embedded presets once, at launch, off the main thread. Home and Settings observe the outcome.
        appScope.launch { presets.loadEmbedded() }
    }
}
