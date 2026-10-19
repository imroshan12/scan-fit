package app.scanfit.feature.pdf

import app.scanfit.core.imaging.CropCorner

/** Everything the PDF maker screen can ask for. */
class PdfActions(
    val onBack: () -> Unit = {},
    /** Gallery pictures; the argument is the ID card side to fill, or `null` to add. */
    val onChoosePictures: (Int?) -> Unit = {},
    /** Files: pictures and/or PDFs, as the tool accepts; the argument is the ID card side. */
    val onChooseFiles: (Int?) -> Unit = {},
    /** `null` when the device has no camera. */
    val onTakePhoto: ((Int?) -> Unit)? = null,
    val onRemove: (Long) -> Unit = {},
    val onMove: (Long, Int) -> Unit = { _, _ -> },
    val onTurn: (Long) -> Unit = {},
    val onCrop: (Long) -> Unit = {},
    val onCropMove: (Double, Double) -> Unit = { _, _ -> },
    val onCropResize: (CropCorner, Double, Double) -> Unit = { _, _, _ -> },
    val onCropReset: () -> Unit = {},
    val onCropDone: () -> Unit = {},
    val onCropCancel: () -> Unit = {},
    val onGrayscale: (Boolean) -> Unit = {},
    val onCleanup: (Boolean) -> Unit = {},
    val onMaxKb: (String) -> Unit = {},
    val onMake: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onDone: () -> Unit = {},
)
