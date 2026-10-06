import CoreGraphics
import Imaging
import SwiftUI

public struct BeforeAfterImage: View {
    private let before: Raster?
    private let after: [UInt8]
    private let strings: Strings
    @State private var afterSelected = true
    @State private var loader = ReviewImageLoader()
    @GestureState private var held = false

    public init(before: Raster?, after: [UInt8], strings: Strings) {
        self.before = before
        self.after = after
        self.strings = strings
    }

    public var body: some View {
        let input = ReviewImageInput(before: before, after: after)
        let images = loader.images(for: input)
        let canCompare = before != nil && !after.isEmpty
        let showBefore = canCompare && (!afterSelected || held)
        let image = showBefore ? images?.before : images?.after
        VStack(spacing: ScanFitSpacing.sm) {
            ZStack {
                RoundedRectangle(cornerRadius: ScanFitRadius.card).fill(ScanFitColor.surfaceVariant)
                if let image {
                    Image(image, scale: 1, label: Text(showBefore ? strings.reviewBefore : strings.reviewAfter))
                        .resizable()
                        .scaledToFit()
                }
            }
            .frame(maxWidth: .infinity)
            .frame(height: 280)
            .contentShape(Rectangle())
            .gesture(holdGesture, including: canCompare && afterSelected ? .all : .none)
            if canCompare {
                Picker("", selection: $afterSelected) {
                    Text(strings.reviewBefore).tag(false)
                    Text(strings.reviewAfter).tag(true)
                }
                .pickerStyle(.segmented)
                .fixedSize(horizontal: false, vertical: true)
            }
        }
        .task(id: input) { await loader.load(input) }
    }

    private var holdGesture: some Gesture {
        LongPressGesture(minimumDuration: 0.25)
            .sequenced(before: DragGesture(minimumDistance: 0))
            .updating($held) { value, held, _ in
                if case .second(true, _) = value { held = true }
            }
    }
}
