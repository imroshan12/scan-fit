import CoreGraphics
import DesignSystem
import Imaging
import SwiftUI

/// UI_UX §3 Capture/crop for ink documents: a free rectangle (§9.5) with corner handles, Rotate and Reset.
struct InkCropView: View {
    let crop: InkCropState
    let model: InkFlowViewModel
    let strings: Strings

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
            Text(strings.photoCropHeading).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            FreeCropEditor(
                raster: crop.image, rect: crop.rect, description: strings.inkCropHint,
                onMove: { model.move(dx: $0, dy: $1) },
                onResize: { model.resize($0, dx: $1, dy: $2) }
            )
            Text(strings.inkCropHint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            HStack(spacing: ScanFitSpacing.sm) {
                Button(strings.photoRotate) { Task { await model.rotate() } }
                Button(strings.photoReset) { model.resetCrop() }
            }
        }
        .padding(ScanFitSpacing.screenMargin)
    }
}
