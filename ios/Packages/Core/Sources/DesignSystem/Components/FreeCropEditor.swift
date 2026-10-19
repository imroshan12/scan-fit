import CoreGraphics
import Imaging
import SwiftUI

/// The image with a free crop frame and corner handles (ALGORITHMS 9.5 free crop; ink documents and PDF pages). A drag
/// that starts on a corner resizes from that corner; inside the frame it moves the frame. Movement is sent in raster
/// pixels; fractions are carried over so slow drags on a small image still move.
public struct FreeCropEditor: View {
    let raster: Raster
    let rect: CropRect
    let description: String
    let onMove: (_ dx: Double, _ dy: Double) -> Void
    let onResize: (_ corner: CropCorner, _ dx: Double, _ dy: Double) -> Void

    @State private var image: CGImage?
    @State private var mode: DragMode?
    @State private var lastTranslation = CGSize.zero
    @State private var pending = CGPoint.zero

    private enum DragMode: Equatable {
        case move
        case resize(CropCorner)
    }

    private static let handleTouch: CGFloat = 32
    private static let handleDrawn: CGFloat = 7

    public init(
        raster: Raster, rect: CropRect, description: String,
        onMove: @escaping (Double, Double) -> Void, onResize: @escaping (CropCorner, Double, Double) -> Void
    ) {
        self.raster = raster
        self.rect = rect
        self.description = description
        self.onMove = onMove
        self.onResize = onResize
    }

    public var body: some View {
        GeometryReader { geo in
            // Inset by the handle size so corner handles at the image edge are not clipped by the canvas.
            let inset = Self.handleDrawn + 2
            let scale = min((geo.size.width - 2 * inset) / CGFloat(raster.width),
                            (geo.size.height - 2 * inset) / CGFloat(raster.height))
            let size = CGSize(width: CGFloat(raster.width) * scale, height: CGFloat(raster.height) * scale)
            let origin = CGPoint(x: (geo.size.width - size.width) / 2, y: (geo.size.height - size.height) / 2)
            let frame = Self.frame(rect, origin: origin, scale: scale)
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(decorative: image, scale: 1)
                        .resizable()
                        .frame(width: size.width, height: size.height)
                        .offset(x: origin.x, y: origin.y)
                }
                Canvas { context, _ in
                    var outside = Path(CGRect(origin: origin, size: size))
                    outside.addRect(frame)
                    context.fill(outside, with: .color(.black.opacity(0.55)), style: FillStyle(eoFill: true))
                    // Brand colour on a white outline: visible on white paper and on dark backgrounds alike.
                    context.stroke(Path(frame), with: .color(.white), lineWidth: 4)
                    context.stroke(Path(frame), with: .color(ScanFitColor.primary), lineWidth: 2)
                    for corner in Self.corners(frame).values {
                        let d = Self.handleDrawn
                        let ring = CGRect(x: corner.x - d - 2, y: corner.y - d - 2, width: 2 * d + 4, height: 2 * d + 4)
                        context.fill(Path(ellipseIn: ring), with: .color(.white))
                        let dot = CGRect(x: corner.x - d, y: corner.y - d, width: 2 * d, height: 2 * d)
                        context.fill(Path(ellipseIn: dot), with: .color(ScanFitColor.primary))
                    }
                }
                .allowsHitTesting(false)
            }
            .frame(width: geo.size.width, height: geo.size.height)
            .contentShape(Rectangle())
            .gesture(drag(frame: frame, scale: scale))
        }
        .accessibilityElement()
        .accessibilityLabel(description)
        // Converted once per image (not per drag frame), off the main actor.
        .task(id: raster.width * 100_003 + raster.height) {
            let source = raster
            image = await Task.detached(priority: .userInitiated) { source.cgImage }.value
        }
    }

    private func drag(frame: CGRect, scale: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                if mode == nil { mode = Self.hitTest(value.startLocation, frame: frame) }
                let dx = value.translation.width - lastTranslation.width
                let dy = value.translation.height - lastTranslation.height
                lastTranslation = value.translation
                guard scale > 0, let mode else { return }
                pending.x += dx / scale
                pending.y += dy / scale
                let mx = pending.x.rounded(.towardZero), my = pending.y.rounded(.towardZero)
                guard mx != 0 || my != 0 else { return }
                pending.x -= mx
                pending.y -= my
                switch mode {
                case .move: onMove(Double(mx), Double(my))
                case let .resize(corner): onResize(corner, Double(mx), Double(my))
                }
            }
            .onEnded { _ in
                mode = nil
                lastTranslation = .zero
                pending = .zero
            }
    }

    private static func frame(_ r: CropRect, origin: CGPoint, scale: CGFloat) -> CGRect {
        CGRect(x: origin.x + CGFloat(r.x) * scale, y: origin.y + CGFloat(r.y) * scale,
               width: CGFloat(r.w) * scale, height: CGFloat(r.h) * scale)
    }

    private static func corners(_ f: CGRect) -> [CropCorner: CGPoint] {
        [.topLeft: CGPoint(x: f.minX, y: f.minY), .topRight: CGPoint(x: f.maxX, y: f.minY),
         .bottomLeft: CGPoint(x: f.minX, y: f.maxY), .bottomRight: CGPoint(x: f.maxX, y: f.maxY)]
    }

    private static func hitTest(_ point: CGPoint, frame: CGRect) -> DragMode? {
        func distance(_ corner: CGPoint) -> CGFloat { hypot(corner.x - point.x, corner.y - point.y) }
        let nearest = corners(frame).min { distance($0.value) < distance($1.value) }
        if let nearest, distance(nearest.value) <= handleTouch {
            return .resize(nearest.key)
        }
        return frame.contains(point) ? .move : nil
    }
}
