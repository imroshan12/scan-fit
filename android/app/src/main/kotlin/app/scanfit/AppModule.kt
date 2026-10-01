package app.scanfit

import android.content.Context
import app.scanfit.core.presets.AssetEmbeddedPresetSource
import app.scanfit.core.presets.DefaultPresetsRepository
import app.scanfit.core.presets.FileVerifiedDigestStore
import app.scanfit.core.presets.PresetsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File
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
    ): PresetsRepository =
        DefaultPresetsRepository(
            source = AssetEmbeddedPresetSource(context),
            verifiedDigests = FileVerifiedDigestStore(File(context.noBackupFilesDir, "presets_verified.sha256")),
        )

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
