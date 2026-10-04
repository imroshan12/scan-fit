package app.scanfit.core.data

import app.scanfit.core.data.export.AndroidExportDestinations
import app.scanfit.core.data.export.ExportDestinations
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Device-side services shared by every flow: where images come from and where saved files go. */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class DataModule {
    @Binds
    abstract fun exportDestinations(destinations: AndroidExportDestinations): ExportDestinations

    @Binds
    abstract fun imageSource(source: AndroidImageSource): ImageSource
}
