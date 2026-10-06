package app.scanfit.core.data

import android.content.Context
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.data.draft.FileDraftStore
import app.scanfit.core.data.export.AndroidExportDestinations
import app.scanfit.core.data.export.ExportDestinations
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/** Device-side services shared by every flow: where images come from and where saved files go. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun exportDestinations(destinations: AndroidExportDestinations): ExportDestinations

    @Binds
    abstract fun imageSource(source: AndroidImageSource): ImageSource

    companion object {
        @Provides
        @Singleton
        fun draftStore(
            @ApplicationContext context: Context,
        ): DraftStore = FileDraftStore(File(context.noBackupFilesDir.canonicalFile, "drafts"))
    }
}
