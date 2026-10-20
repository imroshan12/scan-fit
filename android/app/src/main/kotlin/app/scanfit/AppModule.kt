package app.scanfit

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import app.scanfit.core.billing.Billing
import app.scanfit.core.billing.LocalBilling
import app.scanfit.core.data.DataStoreUserPreferences
import app.scanfit.core.data.UserPreferences
import app.scanfit.core.data.quota.QuotaConfig
import app.scanfit.core.presets.AssetEmbeddedPresetSource
import app.scanfit.core.presets.DefaultPresetsRepository
import app.scanfit.core.presets.PresetsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import javax.inject.Qualifier
import javax.inject.Singleton

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun presetsRepository(
        @ApplicationContext context: Context,
    ): PresetsRepository = DefaultPresetsRepository(
        source = AssetEmbeddedPresetSource(context),
        dispatcher = backgroundDispatcher(),
    )

    /** Free saves this build ships with (ALGORITHMS 14.2): 200 in CI test builds, 20 otherwise. */
    @Provides
    fun quotaConfig(): QuotaConfig {
        val total = QuotaConfig.fromBuildValue(BuildConfig.FREE_SAVES_TOTAL)
        return QuotaConfig { total }
    }

    /** One DataStore per file for the whole process (DataStore requirement), on an IO-backed app scope. */
    @Provides
    @Singleton
    fun userPreferences(
        @ApplicationContext context: Context,
    ): UserPreferences = DataStoreUserPreferences(
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            context.preferencesDataStoreFile("user_preferences")
        },
    )

    /** RevenueCat replaces this (LAUNCH_PLAN week 1). Debug builds sell fake products so the paywall can be walked. */
    @Provides
    @Singleton
    fun billing(
        @ApplicationContext context: Context,
    ): Billing {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        return LocalBilling(if (debuggable) LocalBilling.DEBUG_PRODUCTS else emptyList())
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Decoding the ~75 KB presets file takes a noticeable moment on a low-end phone. It runs on a background-priority
     * thread so the UI thread always wins CPU time and the first frame is never delayed by it.
     */
    private fun backgroundDispatcher(): CoroutineDispatcher = Executors
        .newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "presets-load").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
