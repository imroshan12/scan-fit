package app.scanfit

import android.content.Context
import android.os.Process
import app.scanfit.core.presets.AssetEmbeddedPresetSource
import app.scanfit.core.presets.DefaultPresetsRepository
import app.scanfit.core.presets.FileVerifiedDigestStore
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
import java.io.File
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
        verifiedDigests = FileVerifiedDigestStore(File(context.noBackupFilesDir, "presets_verified.sha256")),
        dispatcher = backgroundDispatcher(),
    )

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Signature verification is pure-Java crypto that can saturate a core for 1-5 s on a low-end phone. It runs on a
     * background-priority thread so the UI thread always wins CPU time and the first frame is never delayed by it.
     */
    private fun backgroundDispatcher(): CoroutineDispatcher = Executors
        .newSingleThreadExecutor { task ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "presets-verify").apply { isDaemon = true }
        }.asCoroutineDispatcher()
}
