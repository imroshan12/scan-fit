import Foundation
import Imaging
import Kit
import ScanData
import ScanModel
import TestSupport
import Testing

actor KitPresetSource {
    private var bundle: PresetBundle?
    private(set) var loads = 0
    let gate = DraftGate()

    init(_ bundle: PresetBundle?) { self.bundle = bundle }

    func set(_ bundle: PresetBundle?) { self.bundle = bundle }

    func load() async -> PresetBundle? {
        loads += 1
        let snapshot = bundle
        await gate.wait()
        return snapshot
    }
}

enum KitFixtures {
    static func exam(_ identifier: String) throws -> Exam {
        try #require(SpecPresets.bundle().exams.first { $0.id == identifier })
    }

    static func bundle(_ exams: [Exam]) -> PresetBundle {
        PresetBundle(presetsVersion: 2, exams: exams, categories: [:], popular: [])
    }

    static func changed(_ exam: Exam, status: String? = nil, dpi: Int? = nil) throws -> Exam {
        var object = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(exam)) as? [String: Any])
        if let status { object["status"] = status }
        if let dpi {
            var documents = try #require(object["documents"] as? [[String: Any]])
            for index in documents.indices { documents[index]["dpi"] = dpi }
            object["documents"] = documents
        }
        return try JSONDecoder().decode(Exam.self, from: JSONSerialization.data(withJSONObject: object))
    }

    static func spec(_ exam: Exam, _ type: DocType) throws -> DocSpec {
        try #require(exam.documents.first { $0.type == type })
    }

    @MainActor
    static func groups(_ model: KitViewModel) throws -> [KitGroup] {
        guard case let .ready(groups) = model.state else {
            Issue.record("Expected prepared Kit content, got \(model.state)")
            return []
        }
        return groups
    }

    static func encoded(_ exam: Exam, _ type: DocType) throws -> [UInt8] {
        let source = Raster.make(240, 180) { column, row in
            let stroke = [40, 90, 140].contains(row) && (20...200).contains(column)
            return stroke ? 0x101010 : 0xF0F0F0
        }
        let pipeline: Pipeline = type == .photo ? .plain : .signatureCleanup
        return try FitPipeline(encoder: ImageIOJpegEncoder())
            .run(source, spec: spec(exam, type), pipeline: pipeline).get().fit.bytes
    }
}
