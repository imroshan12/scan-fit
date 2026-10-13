import Foundation
import Imaging
@testable import InkFlow
import Match
import ScanData
import ScanModel
import Testing
import TestSupport

/// The ink flow for a Custom size (ALGORITHMS 11): the same pipeline, no draft, no Saved status, no handwriting tick.
@MainActor
@Suite("InkFlow Custom size")
struct InkCustomFlowTests {
    private let tools = FakeInkTools()
    private let exporter = RecordingExporter()
    private let drafts = FakeDraftStore()
    private let photo: [UInt8] = [1, 2, 3]
    private static let signature = "signature;140x60;10-20;200"

    private func preferences() -> UserPreferences {
        let name = "scanfit.inkcustom.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return UserPreferences(defaults: defaults)
    }

    private func model(_ text: String = signature, prefs: UserPreferences? = nil, bundle: Bool = true) async throws
        -> InkFlowViewModel {
        let size = try #require(CustomSize.parse(text))
        let loaded = bundle ? try SpecPresets.bundle() : nil
        let m = InkFlowViewModel(target: .custom(size), tools: tools, preferences: prefs, exporter: exporter,
                                 drafts: drafts) { loaded }
        await m.onAppear()
        return m
    }

    @Test("a custom signature uses the signature cleanup, its own limits and no handwriting tick")
    func slot() async throws {
        let slot = try #require(try await model().state.slot)
        #expect(slot.custom && slot.pipeline == .signatureCleanup && slot.kind == .signature)
        #expect(slot.spec == CustomSize.parse(Self.signature)?.spec)
        #expect(!slot.needsHandwritingConfirmation, "the CAPITAL-letters tick is an exam rule")
    }

    @Test("a custom size opens without presets; a photo size is not an ink flow")
    func opens() async throws {
        guard case .pickSource = try await model(bundle: false).state else { Issue.record("not picking"); return }
        #expect(try await model("photo;-;0-50;200").state == .notFound)
    }

    @Test("saving keeps no draft, records no Saved status and names the file Custom")
    func save() async throws {
        let prefs = preferences()
        let m = try await model(prefs: prefs)
        await m.imageSelected(photo)
        await m.cropDone()
        await m.renderTask?.value
        guard case let .review(review) = m.state, case let .ready(ready) = review.result else {
            Issue.record("not ready"); return
        }
        #expect(ready.meetsRules && m.canSave)
        #expect(ready.note.accepted > 0, "the catalogue exams that accept it are still listed")
        #expect(await drafts.retained.isEmpty)
        await m.save()
        #expect(m.exportState == .saved)
        #expect(prefs.savedDocuments.isEmpty)
        #expect(exporter.requests.last?.filename == "signature_Custom_140x60_16kb.jpg")
    }
}
