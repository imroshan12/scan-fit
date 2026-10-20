import Foundation
import Imaging
import Inspect
import Match
import ScanData
import ScanModel
import ScanVision

extension PhotoFlowViewModel {
    nonisolated static func background<Value: Sendable>(
        _ work: @escaping @Sendable () async throws -> Value
    ) async -> Value? {
        try? await Task.detached(priority: .userInitiated, operation: work).value
    }

    nonisolated static func decodeCap(_ spec: DocSpec) -> Int {
        let dimensions = spec.dimensions
        let largest = [dimensions.width, dimensions.height, dimensions.maxW, dimensions.maxH]
            .compactMap { $0 }.max() ?? 0
        return ImageIODecoder.longSideCap(largestTargetDimension: largest)
    }

    nonisolated static func framed(_ slot: PhotoSlot, _ image: Raster, _ found: [Face]) -> CropState {
        let aspect = slot.aspect ?? Double(image.width) / Double(image.height)
        switch FaceCheck.of(found) {
        case let .single(face):
            let framing = AutoFraming.frame(face, imgW: image.width, imgH: image.height, aspect: aspect)
            return CropState(slot: slot, image: image, rect: framing.crop, tight: framing.coverageAdjusted)
        case .noFace, .several:
            let rect = Geometry.defaultCrop(srcW: image.width, srcH: image.height, aspect: aspect)
            let problem: CropProblem? = found.count > 1 ? .severalFaces : nil
            return CropState(slot: slot, image: image, rect: rect, tight: false, problem: problem)
        }
    }

    nonisolated static func ddmmyyyy(_ date: Date) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let components = calendar.dateComponents([.day, .month, .year], from: date)
        func pad(_ number: Int, _ width: Int) -> String {
            let digits = String(number)
            return String(repeating: "0", count: max(0, width - digits.count)) + digits
        }
        return "\(pad(components.day ?? 1, 2))/\(pad(components.month ?? 1, 2))/\(pad(components.year ?? 2000, 4))"
    }
}

public extension PhotoFlowViewModel {
    /// The flow for one slot of a trusted exam preset (ALGORITHMS 2); see `init(target:)` for a Custom size.
    convenience init(
        examId: String,
        docType: DocType,
        tools: any PhotoTools = LivePhotoTools(),
        faces: any FaceDetector = VisionFaceDetector(),
        segmenter: any PersonSegmenter = VisionPersonSegmenter(),
        preferences: UserPreferences? = nil,
        exporter: any DocumentExporting = FilesExporter(),
        drafts: any DraftStore = NoDraftStore(),
        kit: any KitStore = NoKitStore(),
        gate: SaveGate? = nil,
        load: @escaping @Sendable () async -> PresetBundle?
    ) {
        self.init(
            target: .exam(examId: examId, docType: docType), tools: tools, faces: faces, segmenter: segmenter,
            preferences: preferences, exporter: exporter, drafts: drafts, kit: kit, gate: gate, load: load
        )
    }
}

public extension PhotoFlowViewModel {

    /// What Save to My Kit would keep (ALGORITHMS 13.3 previews): the working source, downsampled for display only.
    func originalPreview() async -> Raster? {
        guard let source = lastCrop?.image else { return nil }
        return await Task.detached(priority: .userInitiated) {
            let scale = 720.0 / Double(max(source.width, source.height))
            guard scale < 1 else { return source }
            return Resampler.resize(source, width: max(1, Int(Double(source.width) * scale)),
                                    height: max(1, Int(Double(source.height) * scale)))
        }.value
    }

    /// Keeps the picture this review was made from in My Kit (ALGORITHMS 13.1). The review itself never changes.
    func saveOriginal() async {
        guard case let .review(review) = state, !review.restored, let source = lastCrop?.image,
              kitSave != .saving, kitSave != .saved else { return }
        let gen = sourceGeneration
        kitSave = .saving
        let tools = self.tools
        let jpeg = await Self.background { tools.encodeOriginal(source) }.flatMap { $0 }
        let result: KitSaveStatus = if let jpeg {
            KitSaveStatus(await kit.saveOriginal(kind: .photo, jpeg: jpeg))
        } else {
            .failed
        }
        if gen == sourceGeneration { kitSave = result }
    }
}

public extension PhotoFlowViewModel {
    /// Saves the review (ALGORITHMS 1.6) once the running render has finished; Saved is recorded only after the
    /// exporter verified and published the file, and the free save is spent only then (ALGORITHMS 14.1).
    func save() async {
        guard canSave else { return }
        exportState = .saving
        await renderTask?.value
        guard case let .review(review) = state, case let .ready(ready) = review.result, ready.meetsRules else {
            exportState = .idle
            return
        }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        let request = ExportRequest(bytes: ready.bytes, filename: filename, spec: review.slot.spec, kind: .photo)
        // ALGORITHMS 14.3: spent free saves open the paywall; the review stays as it was.
        guard gate?.allows([ready.bytes], trigger: .save) ?? true else {
            exportState = .idle
            return
        }
        exportState = await exporter.save(request)
        guard exportState == .saved else { return }
        await gate?.record(request.bytes)
        if !review.slot.custom { preferences?.recordSaved(review.slot.examId, review.slot.spec.type) }
        await kit.recordExport(request, examId: review.slot.examId, examName: review.slot.examName)
    }

    /// Shares exactly the review bytes under the §9.8 name (ALGORITHMS 1.6.2). Allowed whenever Save is, once the
    /// render has finished; it never records Saved, because the destination cannot be re-read.
    func share() async {
        guard canSave, !renderPending, case let .review(review) = state, case let .ready(ready) = review.result,
              ready.meetsRules else { return }
        let filename = ExportNaming.fileName(
            examName: review.slot.examName, slot: review.slot.spec,
            width: ready.width, height: ready.height, bytes: ready.bytes.count
        )
        guard gate?.allows([ready.bytes], trigger: .share) ?? true else { return }
        let url = await ShareStager.stage(ready.bytes, filename: filename)
        if url != nil { await gate?.record(ready.bytes) }
        guard case let .review(current) = state, current.result == review.result else { return }
        share = url.map { .ready(SharedFile(url: $0)) } ?? .failed
    }

    /// The share sheet closed.
    func shareHandled() {
        if case .ready = share { share = .none }
    }
}

extension PhotoFlowViewModel {
    /// Re-inspects the fitted bytes and evaluates them against the slot, as the export will be (rule 3).
    nonisolated static func ready(
        _ bytes: [UInt8], _ spec: DocSpec, _ exams: [Exam], _ popular: [String]
    ) -> ReviewReady {
        let inspected = Inspector.inspect(bytes)
        let facts = FileFacts(inspected, docKind: .photo)
        let evaluation = MatchEngine.evaluate(spec, facts)
        return ReviewReady(
            bytes: bytes,
            kb: (bytes.count + 512) / 1024,
            width: inspected.width ?? 0,
            height: inspected.height ?? 0,
            meetsRules: evaluation.verdict == .exact || evaluation.verdict == .accepted,
            note: MatchNote.of(facts, exams: exams, popularity: popular),
            checks: .of(evaluation)
        )
    }
}

public extension PhotoFlowViewModel {
    var canSave: Bool {
        guard !draftValidationPending, exportState != .saving, case let .review(review) = state else { return false }
        if renderPending { return true }
        guard case let .ready(ready) = review.result else { return false }
        return ready.meetsRules
    }
}
