import CoreGraphics
import DesignSystem
import Imaging
import SwiftUI

/// UI_UX §3 Capture/crop: the image with an aspect-locked crop frame. Drag moves the frame, pinch zooms it, and the
/// buttons do the same for people who cannot pinch (UI_UX §6).
struct PhotoCropView: View {
    let crop: CropState
    let model: PhotoFlowViewModel
    let strings: Strings

    @State private var image: CGImage?
    @State private var lastTranslation = CGSize.zero
    @State private var pending = CGPoint.zero
    @State private var lastMagnification: CGFloat = 1

    private static let zoomStep = 1.25

    var body: some View {
        VStack(alignment: .leading, spacing: ScanFitSpacing.md) {
            Text(strings.photoCropHeading).scanFitText(.headline).accessibilityAddTraits(.isHeader)
            editor
            Text(strings.photoCropHint).scanFitText(.caption).foregroundStyle(ScanFitColor.onSurfaceVariant)
            ViewThatFits(in: .horizontal) {
                HStack(spacing: ScanFitSpacing.sm) { tools }
                VStack(alignment: .leading, spacing: ScanFitSpacing.xs) { tools }
            }
            if let problem = crop.problem {
                NoticeCard(text: message(problem), kind: .error)
            } else if crop.tight {
                NoticeCard(text: strings.photoCropTight, kind: .warning)
            }
        }
        .padding(ScanFitSpacing.screenMargin)
        // Converted once per image (not per drag frame), off the main actor.
        .task(id: crop.image.width * 100_003 + crop.image.height) {
            let raster = crop.image
            image = await Task.detached(priority: .userInitiated) { raster.cgImage }.value
        }
    }

    @ViewBuilder
    private var tools: some View {
        Button(strings.photoZoomOut) { model.zoom(1 / Self.zoomStep) }
        Button(strings.photoZoomIn) { model.zoom(Self.zoomStep) }
        Button(strings.photoRotate) { Task { await model.rotate() } }
        Button(strings.photoReset) { Task { await model.resetCrop() } }
    }

    private var editor: some View {
        GeometryReader { geo in
            let raster = crop.image
            let scale = min(geo.size.width / CGFloat(raster.width), geo.size.height / CGFloat(raster.height))
            let size = CGSize(width: CGFloat(raster.width) * scale, height: CGFloat(raster.height) * scale)
            let origin = CGPoint(x: (geo.size.width - size.width) / 2, y: (geo.size.height - size.height) / 2)
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .frame(width: size.width, height: size.height)
                        .offset(x: origin.x, y: origin.y)
                }
                Canvas { context, _ in
                    let r = crop.rect
                    let frame = CGRect(
                        x: origin.x + CGFloat(r.x) * scale, y: origin.y + CGFloat(r.y) * scale,
                        width: CGFloat(r.w) * scale, height: CGFloat(r.h) * scale
                    )
                    var outside = Path(CGRect(origin: origin, size: size))
                    outside.addRect(frame)
                    context.fill(outside, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
                    // Brand colour on a white outline: visible on a plain light wall and on dark backgrounds alike.
                    context.stroke(Path(frame), with: .color(.white), lineWidth: 4)
                    context.stroke(Path(frame), with: .color(ScanFitColor.primary), lineWidth: 2)
                }
                .allowsHitTesting(false)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .gesture(drag(scale: scale).simultaneously(with: magnify))
        }
        .accessibilityElement()
        .accessibilityLabel(strings.photoCropHint)
    }

    /// Movement is sent in raster pixels; fractions are carried over so slow drags on a small image still move.
    private func drag(scale: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let dx = value.translation.width - lastTranslation.width
                let dy = value.translation.height - lastTranslation.height
                lastTranslation = value.translation
                guard scale > 0 else { return }
                pending.x += dx / scale
                pending.y += dy / scale
                let mx = pending.x.rounded(.towardZero), my = pending.y.rounded(.towardZero)
                if mx != 0 || my != 0 {
                    model.move(dx: Double(mx), dy: Double(my))
                    pending.x -= mx
                    pending.y -= my
                }
            }
            .onEnded { _ in
                lastTranslation = .zero
                pending = .zero
            }
    }

    private var magnify: some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let factor = value.magnification / lastMagnification
                lastMagnification = value.magnification
                if factor > 0, factor != 1 { model.zoom(Double(factor)) }
            }
            .onEnded { _ in lastMagnification = 1 }
    }

    private func message(_ problem: CropProblem) -> String {
        switch problem {
        case .noFace: strings.errorFaceNone
        case .severalFaces: strings.errorFaceMultiple
        case .failed: strings.errorGeneric
        }
    }
}
