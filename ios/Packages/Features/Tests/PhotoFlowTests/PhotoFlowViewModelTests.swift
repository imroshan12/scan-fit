import Foundation
import Imaging
import Match
@testable import PhotoFlow
import ScanModel
import ScanVision
import Testing
import TestSupport

/// Test double for `PhotoTools`: a fixed decoded raster and a configurable fit. Records what it was asked to do.
final class FakePhotoTools: PhotoTools, @unchecked Sendable {
    private let lock = NSLock()
    private var _decoded: Raster? = Raster.make(1000, 1400) { x, y in x < 500 ? 0x336699 : (y & 0xFF) }
    private var _strips: [(String, String)] = []
    private var _fitInput: Raster?
    private var _fit: @Sendable (Raster) -> Result<PipelineResult, FitError> = { raster in
        FakePhotoTools.success(raster, FakePhotoTools.jpeg(width: 200, height: 230, size: 34 * 1024))
    }

    var decoded: Raster? {
        get { lock.withLock { _decoded } }
        set { lock.withLock { _decoded = newValue } }
    }

    var strips: [(String, String)] { lock.withLock { _strips } }
    var fitInput: Raster? { lock.withLock { _fitInput } }

    func setFit(_ fit: @escaping @Sendable (Raster) -> Result<PipelineResult, FitError>) {
        lock.withLock { _fit = fit }
    }

    func decode(_: [UInt8], maxLongSide _: Int) -> Raster? { decoded }

    func drawStrip(_ photo: Raster, name: String, date: String) -> Raster {
        lock.withLock { _strips.append((name, date)) }
        return photo
    }

    func fit(_ prepared: Raster, spec _: DocSpec) -> Result<PipelineResult, FitError> {
        let fit = lock.withLock {
            _fitInput = prepared
            return _fit
        }
        return fit(prepared)
    }

    func today() -> Date {
        Calendar(identifier: .gregorian).date(from: DateComponents(year: 2026, month: 10, day: 3, hour: 12)) ?? Date()
    }

    static func success(_ prepared: Raster, _ bytes: [UInt8]) -> Result<PipelineResult, FitError> {
        .success(PipelineResult(
            fit: FitResult(bytes: bytes, width: 0, height: 0, quality: 90, strategy: .qualitySearch, encodes: 3),
            prepared: prepared, ink: nil
        ))
    }

    static func jpeg(width: Int, height: Int, size: Int) -> [UInt8] {
        TestJpeg.make(width: width, height: height, size: size)
    }
}

/// A detector that always fails, like a model that cannot load.
struct FailingDetector: FaceDetector {
    struct Failure: Error {}

    func detect(in _: Raster) async throws -> [Face] { throw Failure() }
}

@MainActor
@Suite("PhotoFlowViewModel")
struct PhotoFlowViewModelTests {
    private let tools = FakePhotoTools()
    private let face = Face(x: 400, y: 500, w: 200, h: 240)
    private let faces: FakeFaceDetector
    private let photo: [UInt8] = [1, 2, 3]

    init() {
        faces = FakeFaceDetector(faces: [face])
    }

    private func model(
        _ exam: String = "ibps_po",
        _ doc: DocType = .photo,
        detector: (any FaceDetector)? = nil,
        segmenter: FakePersonSegmenter = FakePersonSegmenter()
    ) async throws -> PhotoFlowViewModel {
        let bundle = try SpecPresets.bundle()
        let m = PhotoFlowViewModel(examId: exam, docType: doc, tools: tools, faces: detector ?? faces,
                                   segmenter: segmenter) { bundle }
        await m.onAppear()
        return m
    }

    private var autoCrop: CropRect { AutoFraming.frame(face, imgW: 1000, imgH: 1400, aspect: 200.0 / 230.0).crop }

    private func reviewed(_ m: PhotoFlowViewModel) async throws -> ReviewState {
        await m.imageSelected(photo)
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state else { throw Unexpected(state: m.state) }
        return review
    }

    struct Unexpected: Error { let state: PhotoState }

    @Test("starts at the picker for the exam's photo slot")
    func start() async throws {
        guard case let .pickSource(slot, problem) = try await model().state else { Issue.record("not picking"); return }
        #expect(slot.examName == "IBPS PO / MT" && slot.targetKb == 38 && problem == nil)
    }

    @Test("an unknown exam or slot is not found")
    func notFound() async throws {
        #expect(try await model("no_such_exam").state == .notFound)
        #expect(try await model("ibps_po", .postcardPhoto).state == .notFound)
    }

    @Test("one face is framed automatically")
    func framing() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        guard case let .crop(crop) = m.state else { Issue.record("no crop"); return }
        #expect(crop.rect == autoCrop && crop.problem == nil)
    }

    @Test("an unreadable or undecodable file goes back to the picker with a message")
    func unreadable() async throws {
        let m = try await model()
        await m.imageSelected(nil)
        #expect(m.state == .pickSource(try #require(m.state.slot), .openFailed))
        tools.decoded = nil
        await m.imageSelected(photo)
        #expect(m.state == .pickSource(try #require(m.state.slot), .openFailed))
    }

    @Test("no face blocks; several faces ask for a re-crop until one is left")
    func faceCount() async throws {
        let m = try await model()
        faces.setFaces([])
        await m.imageSelected(photo)
        #expect(m.state == .pickSource(try #require(m.state.slot), .noFace))
        faces.setFaces([face, Face(x: 700, y: 500, w: 150, h: 180)])
        await m.imageSelected(photo)
        guard case let .crop(crop) = m.state else { Issue.record("no crop"); return }
        #expect(crop.problem == .severalFaces)
        await m.cropDone()
        guard case let .crop(still) = m.state else { Issue.record("left the crop"); return }
        #expect(still.problem == .severalFaces && !still.checking)
        faces.setFaces([face])
        await m.cropDone()
        await m.renderTask?.value
        guard case .review = m.state else { Issue.record("no review"); return }
    }

    @Test("a detector failure offers a retry instead of crashing")
    func detectorFailure() async throws {
        let m = try await model(detector: FailingDetector())
        await m.imageSelected(photo)
        #expect(m.state == .pickSource(try #require(m.state.slot), .failed))
    }

    @Test("move and zoom stay aspect-locked and inside the image; reset restores the framing")
    func adjust() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        m.move(dx: -5000, dy: 0)
        guard case let .crop(moved) = m.state else { return }
        #expect(moved.rect.x == 0)
        m.zoom(2)
        guard case let .crop(zoomed) = m.state else { return }
        #expect(zoomed.rect.h < autoCrop.h)
        #expect(abs(Double(zoomed.rect.w) / Double(zoomed.rect.h) - 200.0 / 230.0) < 0.02)
        await m.resetCrop()
        guard case let .crop(reset) = m.state else { return }
        #expect(reset.rect == autoCrop)
    }

    @Test("rotate turns the image and detects again")
    func rotate() async throws {
        let m = try await model()
        await m.imageSelected(photo)
        await m.rotate()
        guard case let .crop(crop) = m.state else { Issue.record("no crop"); return }
        #expect(crop.image.width == 1400 && crop.image.height == 1000)
        #expect(faces.calls == 2)
    }

    @Test("review fits exactly the crop and checks the bytes")
    func review() async throws {
        let review = try await reviewed(model())
        guard case let .ready(ready) = review.result else { Issue.record("not ready"); return }
        #expect(ready.kb == 34 && ready.width == 200 && ready.height == 230 && ready.meetsRules)
        #expect(ready.checks == ReviewChecks(size: true, dimensions: true, jpeg: true))
        #expect(tools.fitInput?.width == autoCrop.w && tools.fitInput?.height == autoCrop.h)
        let rect = autoCrop
        let original = try #require(tools.decoded).crop(x: rect.x, y: rect.y, width: rect.w, height: rect.h)
        #expect(review.before == original)
        #expect(!review.options.nameDate, "IBPS does not require a strip")
    }

    @Test("the review notes which exams accept the photo")
    func matchNote() async throws {
        guard case let .ready(ready) = try await reviewed(model()).result else { Issue.record("not ready"); return }
        let rows = ready.note.groups.flatMap(\.entries)
        #expect(rows.contains { $0.examId == "ibps_po" }, "IBPS PO accepts its own photo")
        #expect(ready.note.accepted > 1, "the IBPS family shares the photo rules")
        #expect(!rows.contains { $0.examId == "ssc_cgl" }, "SSC captures the photo live: never listed")
    }

    @Test("a file outside the window does not meet the rules; a fit error is shown")
    func failures() async throws {
        tools.setFit { FakePhotoTools.success($0, FakePhotoTools.jpeg(width: 200, height: 230, size: 60 * 1024)) }
        guard case let .ready(ready) = try await reviewed(model()).result else { Issue.record("not ready"); return }
        #expect(!ready.meetsRules)
        #expect(ready.checks == ReviewChecks(size: false, dimensions: true, jpeg: true))
        tools.setFit { _ in .failure(.tooDetailed) }
        #expect(try await reviewed(model()).result == .failed(.tooDetailed))
    }

    @Test("an unverified exam is flagged for the 'likely OK' wording")
    func unverified() async throws {
        #expect(try await model("niacl_ao").state.slot?.unverified == true)
        #expect(try await model().state.slot?.unverified == false)
    }

    @Test("white background whitens the surroundings, or turns itself off when unavailable")
    func whiteBackground() async throws {
        let crop = autoCrop
        var segmenter = FakePersonSegmenter()
        // person = left half of the crop
        segmenter.mask = (0..<(crop.w * crop.h)).map { $0 % crop.w < crop.w / 2 ? 255 : 0 }
        let m = try await model(segmenter: segmenter)
        let initial = try await reviewed(m)
        m.setWhiteBackground(true)
        guard case let .review(working) = m.state else { Issue.record("no working review"); return }
        #expect(working.before == initial.before && working.result == .working(targetKb: 38))
        await m.renderTask?.value
        let input = try #require(tools.fitInput)
        #expect(input.g(crop.w - 2, crop.h / 2) == 255, "far background is white")
        guard case let .review(review) = m.state else { return }
        #expect(review.options.whiteBackground)
        #expect(review.before == initial.before && review.before != tools.fitInput)
        m.setNameDate(true)
        await m.renderTask?.value
        guard case let .review(stripped) = m.state else { Issue.record("no strip review"); return }
        #expect(stripped.before == initial.before)
        tools.setFit { _ in .failure(.tooDetailed) }
        m.setWhiteBackground(false)
        await m.renderTask?.value
        guard case let .review(failed) = m.state else { Issue.record("no failed review"); return }
        #expect(failed.before == initial.before && failed.result == .failed(.tooDetailed))

        let off = try await model(segmenter: FakePersonSegmenter(mask: nil))
        _ = try await reviewed(off)
        off.setWhiteBackground(true)
        await off.renderTask?.value
        guard case let .review(after) = off.state else { return }
        #expect(!after.options.whiteBackground && !after.options.whiteBackgroundAvailable)
    }

    @Test("the strip is off by default even when the preset asks for it; on, it uses today's date and the typed name")
    func strip() async throws {
        let m = try await model("upsc_cse")
        let review = try await reviewed(m)
        #expect(review.slot.spec.nameDateStrip?.required == true, "the preset asks for it (the screen shows a hint)")
        #expect(!review.options.nameDate && tools.strips.isEmpty && review.options.date == "03/10/2026")
        m.setNameDate(true)
        await m.renderTask?.value
        m.setName("  asha rao ")
        guard case let .review(typing) = m.state else { return }
        #expect(typing.options.name == "  asha rao ", "the field shows what was typed at once")
        try await Task.sleep(for: .milliseconds(100)) // well inside the 400 ms debounce
        #expect(!tools.strips.contains { $0.0 == "asha rao" }, "typing is debounced, not fitted per key")
        await m.renderTask?.value
        #expect(tools.strips.last.map { $0.0 == "asha rao" && $0.1 == "03/10/2026" } == true)
        m.setDate("")
        await m.renderTask?.value
        #expect(tools.strips.last?.1 == "03/10/2026", "an empty date falls back to today")
    }

    @Test("back steps through the flow, then leaves")
    func back() async throws {
        let m = try await model()
        #expect(!m.back(), "the picker is the first step")
        _ = try await reviewed(m)
        #expect(m.back())
        guard case let .crop(crop) = m.state else { Issue.record("not back on the crop"); return }
        #expect(crop.rect == autoCrop)
        #expect(m.back())
        guard case .pickSource = m.state else { Issue.record("not back on the picker"); return }
        #expect(!m.back())
    }

    @Test("a free-ratio slot keeps the photo's shape")
    func freeRatio() async throws {
        let m = try await model("upsc_cse")
        tools.decoded = Raster.make(900, 1200) { _, _ in 0x808080 }
        faces.setFaces([Face(x: 300, y: 300, w: 300, h: 360)])
        await m.imageSelected(photo)
        guard case let .crop(crop) = m.state else { Issue.record("no crop"); return }
        #expect(abs(Double(crop.rect.w) / Double(crop.rect.h) - 900.0 / 1200.0) < 0.01)
    }

    @Test("the strip date is DD/MM/YYYY with ASCII digits")
    func dateFormat() throws {
        let components = DateComponents(year: 2027, month: 1, day: 5, hour: 12)
        let date = try #require(Calendar(identifier: .gregorian).date(from: components))
        #expect(PhotoFlowViewModel.ddmmyyyy(date) == "05/01/2027")
    }
}
