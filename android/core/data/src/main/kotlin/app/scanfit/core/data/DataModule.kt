package app.scanfit.core.data

import android.content.Context
import app.scanfit.core.data.draft.DraftStore
import app.scanfit.core.data.draft.FileDraftStore
import app.scanfit.core.data.export.AndroidExportDestinations
import app.scanfit.core.data.export.AndroidShareStager
import app.scanfit.core.data.export.ExportDestinations
import app.scanfit.core.data.export.ShareStager
import app.scanfit.core.data.kit.FileKitStore
import app.scanfit.core.data.kit.KitStore
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

    @Binds
    abstract fun shareStager(stager: AndroidShareStager): ShareStager

    companion object {
        @Provides
        @Singleton
        fun draftStore(
            @ApplicationContext context: Context,
        ): DraftStore = FileDraftStore(File(context.noBackupFilesDir.canonicalFile, "drafts"))

        /** My Kit originals and export history (ALGORITHMS 13): no-backup storage, like the drafts. */
        @Provides
        @Singleton
        fun kitStore(
            @ApplicationContext context: Context,
        ): KitStore = FileKitStore(File(context.noBackupFilesDir.canonicalFile, "kit"))
    }
}
