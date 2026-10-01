import Foundation
import Imaging
import Inspect
import ScanModel
import TestSupport
import Testing

/// Phase 1 exit gate: "Fit of the photo fixture <= 800 ms on a mid device" (`max_ms_midrange` of `ibps_photo_from_phone`).
///
/// Debug builds are an order of magnitude slower than release, so the budget is **asserted only in optimised builds**
/// (`swift test -c release -Xswiftc -enable-testing`); in debug it just records the time. Either way this is a build-machine
/// number, much faster than a mid-range phone: it is a regression guard, and the device figure comes from XCTest `measure` on
/// an iPhone SE in Phase 5.
@Suite("Fit benchmark")
struct FitBenchmarkTests {
    @Test("the IBPS photo fixture fits within the mid-device budget on the build machine")
    func ibpsPhoto() throws {
        let cases = try CasesFile.section("fit_cases")
        let c = try #require(cases.first { $0.string("id") == "ibps_photo_from_phone" })
        let budget = try #require(c.dict("expect")?.int("max_ms_midrange"))
        let bytes = try CasesFile.image(try #require(c.string("input")))
        let original = Inspector.inspect(bytes)
        let spec = try TestSlots.slot("ibps_po", .photo)
        let rect = try #require(c.dict("crop"))
        let pipeline = FitPipeline(encoder: ImageIOJpegEncoder())

        func once() throws -> Double {
            let start = DispatchTime.now().uptimeNanoseconds
            let decoded = try #require(ImageIODecoder.decode(bytes, maxLongSide: ImageIODecoder.longSideCap(largestTargetDimension: 230)))
            let crop = CropRect(x: rect.int("x") ?? 0, y: rect.int("y") ?? 0, w: rect.int("w") ?? 1, h: rect.int("h") ?? 1)
                .scaled(srcW: original.width ?? 1, srcH: original.height ?? 1, to: decoded)
            guard case .success = pipeline.run(decoded, spec: spec, pipeline: .plain, crop: crop) else {
                Issue.record("the fit must succeed")
                return .infinity
            }
            return Double(DispatchTime.now().uptimeNanoseconds - start) / 1_000_000
        }

        for _ in 0..<2 { _ = try once() } // warm-up
        let times = try (0..<5).map { _ in try once() }.sorted()
        let median = times[2]
        #if DEBUG
        print("BENCH ibps_photo_from_phone median=\(Int(median))ms (debug build: not asserted; budget \(budget) ms)")
        #else
        print("BENCH ibps_photo_from_phone median=\(Int(median))ms runs=\(times.map { Int($0) }) budget=\(budget)ms (release, build machine)")
        #expect(median <= Double(budget), "median \(median) ms exceeds the \(budget) ms mid-device budget even on the build machine")
        #endif
    }
}
