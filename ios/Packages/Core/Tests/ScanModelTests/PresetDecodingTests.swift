import Foundation
import ScanModel
import TestSupport
import Testing

/// Proves the Swift model matches the JSON schema: every preset file in spec/ decodes on its own.
@Suite("Preset decoding")
struct PresetDecodingTests {
    @Test("every preset file decodes, and its id equals its file name")
    func everyPresetDecodes() throws {
        let files = try SpecFiles.presetFiles()
        #expect(files.count >= 55)
        for file in files {
            let exam = try PresetBundle.decodeExam(Data(contentsOf: file))
            #expect(file.deletingPathExtension().lastPathComponent == exam.id, "\(file.lastPathComponent)")
            #expect(!exam.documents.isEmpty, "\(exam.id) has documents")
            #expect(!exam.sources.isEmpty, "\(exam.id) has sources")
        }
    }

    @Test("IBPS PO matches the values in the preset file")
    func ibpsPo() throws {
        let data = try SpecFiles.data("presets/exams/banking/ibps_po.json")
        let exam = try PresetBundle.decodeExam(data)
        #expect(exam.name == "IBPS PO / MT")
        #expect(exam.category == .banking)
        #expect(exam.livePhotoCapture == true)
        #expect(exam.confidence == .high)
        let photo = try #require(exam.documents.first { $0.type == .photo })
        #expect(photo.sizeKb.min == 20 && photo.sizeKb.max == 50 && photo.sizeKb.target == 38)
        #expect(photo.dimensions.mode == .preferred)
        #expect(photo.dimensions.width == 200)
        #expect(photo.dimensions.height == 230)
        let declaration = try #require(exam.documents.first { $0.type == .handwrittenDeclaration })
        #expect(declaration.declarationText == nil, "not transcribed yet: the UI must say 'copy from notice'")
    }

    @Test("range dimensions and aspect ranges decode (GATE photo)")
    func rangeDimensions() throws {
        let exam = try PresetBundle.decodeExam(try SpecFiles.data("presets/exams/entrance/gate.json"))
        let photo = try #require(exam.documents.first { $0.type == .photo })
        #expect(photo.dimensions.mode == .range)
        #expect(photo.dimensions.minW != nil && photo.dimensions.maxH != nil)
    }

    @Test("low confidence is flagged unverified (CLAUDE.md rule 6)")
    func lowConfidenceIsUnverified() throws {
        let exams = try SpecFiles.presetFiles().map { try PresetBundle.decodeExam(Data(contentsOf: $0)) }
        let low = exams.filter { $0.confidence == .low }
        #expect(!low.isEmpty)
        let lowAllFlagged = low.allSatisfy { $0.isUnverified }
        let othersNotFlagged = exams.filter { $0.confidence != .low }.allSatisfy { !$0.isUnverified }
        #expect(lowAllFlagged)
        #expect(othersNotFlagged)
    }

    @Test("an unknown document type fails to decode rather than being silently dropped")
    func unknownEnumFails() {
        let json = #"{"type":"hologram","required":true,"formats":["jpg"],"size_kb":{"min":1,"max":2,"target":null},"dimensions":{"mode":"none"}}"#
        #expect(throws: DecodingError.self) {
            try PresetBundle.decoder().decode(DocSpec.self, from: Data(json.utf8))
        }
    }
}
