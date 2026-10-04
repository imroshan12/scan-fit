import Foundation

/// Exam search and browse for Home (ALGORITHMS §10). Pure and deterministic; the same rules run in Kotlin
/// (`ExamSearch`) and in the reference `spec/tools/exam_search.py`, and all three must agree on `search_cases`
/// in `spec/fixtures/cases.json`.
///
/// Build one per bundle: the index is computed once, so searching on every keystroke is cheap. Everything works on
/// Unicode scalars (never `Character`s), because grapheme clustering would glue a stray mark onto a separator space.
public struct ExamSearch: Sendable {
    private typealias Scalars = [UInt32]

    private struct Entry: Sendable {
        let exam: Exam
        let tokens: [Scalars]
        let nameKey: Scalars
    }

    private let popularIds: [String]
    private let rank: [String: Int]
    private let entries: [Entry]

    public init(bundle: PresetBundle) {
        let popular = bundle.popular ?? []
        popularIds = popular
        rank = Dictionary(popular.enumerated().map { ($1, $0) }, uniquingKeysWith: { first, _ in first })
        let categoryAliases = Self.categoryAliases(bundle.categories ?? [:])
        entries = bundle.exams.map { exam in
            Entry(
                exam: exam,
                tokens: Self.indexTokens(exam, categoryAliases: categoryAliases[exam.category] ?? []),
                nameKey: Self.joined(Self.normScalars(exam.name), separator: " ")
            )
        }
    }

    /// Matching exams, best first. A blank query gives no results: the screen shows its browse sections instead.
    public func search(_ query: String, category: ExamCategory? = nil, showUnverified: Bool = false) -> [Exam] {
        let tokens = Self.normScalars(query)
        guard !tokens.isEmpty else { return [] }
        let joinedQuery = Self.joined(tokens, separator: " ")
        struct Scored {
            let entry: Entry
            let score: Int
            let startsName: Bool
        }
        let scored: [Scored] = entries.compactMap { entry in
            guard Self.visible(entry.exam, showUnverified),
                  category == nil || entry.exam.category == category else { return nil }
            let best = tokens.map { q in entry.tokens.map { Self.level(q, $0) }.max() ?? 0 }
            guard let worst = best.min(), worst >= 1 else { return nil }
            return Scored(entry: entry, score: best.reduce(0, +), startsName: entry.nameKey.starts(with: joinedQuery))
        }
        return scored.sorted { a, b in
            if a.score != b.score { return a.score > b.score }
            if a.startsName != b.startsName { return a.startsName }
            let ra = popularity(a.entry.exam), rb = popularity(b.entry.exam)
            if ra != rb { return ra < rb }
            if a.entry.nameKey != b.entry.nameKey { return a.entry.nameKey.lexicographicallyPrecedes(b.entry.nameKey) }
            return a.entry.exam.id < b.entry.exam.id
        }.map(\.entry.exam)
    }

    /// The visible exams of one category: popularity, then name, then id.
    public func browse(_ category: ExamCategory, showUnverified: Bool = false) -> [Exam] {
        entries.filter { Self.visible($0.exam, showUnverified) && $0.exam.category == category }
            .sorted { a, b in
                let ra = popularity(a.exam), rb = popularity(b.exam)
                if ra != rb { return ra < rb }
                if a.nameKey != b.nameKey { return a.nameKey.lexicographicallyPrecedes(b.nameKey) }
                return a.exam.id < b.exam.id
            }
            .map(\.exam)
    }

    /// The first `count` visible exams in the bundle's popularity order; unknown or hidden ids are skipped.
    public func popular(_ count: Int, showUnverified: Bool = false) -> [Exam] {
        let byId = Dictionary(
            entries.filter { Self.visible($0.exam, showUnverified) }.map { ($0.exam.id, $0.exam) },
            uniquingKeysWith: { first, _ in first }
        )
        return Array(popularIds.compactMap { byId[$0] }.prefix(count))
    }

    private func popularity(_ exam: Exam) -> Int { rank[exam.id] ?? popularIds.count }

    // MARK: - Rules (ALGORITHMS §10)

    private static let exact = 3, prefix = 2, fuzzy = 1, fuzzyMinLength = 4
    private static let deleted: Set<UInt32> = [0x093C, 0x200C, 0x200D] // Devanagari nukta, ZWNJ, ZWJ

    /// §10 normalisation: NFKC, default lower-casing, drop nukta/ZWNJ/ZWJ, non letter/mark/digit becomes a separator.
    public static func norm(_ text: String) -> [String] {
        normScalars(text).map { token in
            var view = String.UnicodeScalarView()
            view.append(contentsOf: token.compactMap(Unicode.Scalar.init))
            return String(view)
        }
    }

    /// 3 exact, 2 prefix, 1 one typo against a prefix of `t` (only for queries of 4+ scalars), else 0.
    public static func level(_ q: String, _ t: String) -> Int {
        level(q.unicodeScalars.map(\.value), t.unicodeScalars.map(\.value))
    }

    /// Optimal string alignment distance: Levenshtein plus adjacent transposition, each costing 1.
    static func osa(_ a: [UInt32], _ b: [UInt32]) -> Int {
        var d = [[Int]](repeating: [Int](repeating: 0, count: b.count + 1), count: a.count + 1)
        for i in 0 ... a.count { d[i][0] = i }
        for j in 0 ... b.count { d[0][j] = j }
        if a.isEmpty || b.isEmpty { return d[a.count][b.count] }
        for i in 1 ... a.count {
            for j in 1 ... b.count {
                let cost = a[i - 1] == b[j - 1] ? 0 : 1
                d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if i > 1, j > 1, a[i - 1] == b[j - 2], a[i - 2] == b[j - 1] {
                    d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
                }
            }
        }
        return d[a.count][b.count]
    }

    private static func level(_ q: Scalars, _ t: Scalars) -> Int {
        if q == t { return exact }
        if t.starts(with: q) { return prefix }
        if q.count >= fuzzyMinLength {
            for k in (q.count - 1) ... (q.count + 1) where k <= t.count && osa(q, Array(t.prefix(k))) <= 1 {
                return fuzzy
            }
        }
        return 0
    }

    private static func normScalars(_ text: String) -> [Scalars] {
        let lowered = text.precomposedStringWithCompatibilityMapping.lowercased()
        var tokens: [Scalars] = []
        var current: Scalars = []
        for scalar in lowered.unicodeScalars where !deleted.contains(scalar.value) {
            if isLetterMarkOrDigit(scalar) {
                current.append(scalar.value)
            } else if !current.isEmpty {
                tokens.append(current)
                current = []
            }
        }
        if !current.isEmpty { tokens.append(current) }
        return tokens
    }

    private static func isLetterMarkOrDigit(_ scalar: Unicode.Scalar) -> Bool {
        switch scalar.properties.generalCategory {
        case .uppercaseLetter, .lowercaseLetter, .titlecaseLetter, .modifierLetter, .otherLetter,
             .nonspacingMark, .spacingMark, .enclosingMark, .decimalNumber:
            true
        default:
            false
        }
    }

    private static func indexTokens(_ exam: Exam, categoryAliases: [String]) -> [Scalars] {
        let aliases = exam.aliases ?? []
        var tokens = [exam.name, exam.body, exam.id, exam.category.rawValue].flatMap(normScalars)
        tokens += categoryAliases.flatMap(normScalars)
        tokens += aliases.flatMap(normScalars)
        tokens += ([exam.name] + aliases).map { joined(normScalars($0), separator: nil) }
        return tokens.filter { !$0.isEmpty }
    }

    /// Category keys may arrive as `state_psc` or, after `convertFromSnakeCase`, `statePsc`: match them
    /// separator-insensitively.
    private static func categoryAliases(_ raw: [String: PresetBundle.CategoryInfo]) -> [ExamCategory: [String]] {
        func key(_ s: String) -> String { s.lowercased().replacingOccurrences(of: "_", with: "") }
        let byKey = Dictionary(raw.map { (key($0.key), $0.value.aliases) }, uniquingKeysWith: { first, _ in first })
        return Dictionary(uniqueKeysWithValues: ExamCategory.allCases.compactMap { category in
            byKey[key(category.rawValue)].map { (category, $0) }
        })
    }

    private static func joined(_ tokens: [Scalars], separator: UInt32?) -> Scalars {
        var out: Scalars = []
        for (i, token) in tokens.enumerated() {
            if i > 0, let separator { out.append(separator) }
            out += token
        }
        return out
    }

    private static func joined(_ tokens: [Scalars], separator: Character) -> Scalars {
        joined(tokens, separator: separator.unicodeScalars.first?.value)
    }

    private static func visible(_ exam: Exam, _ showUnverified: Bool) -> Bool {
        exam.status == .active && (showUnverified || exam.confidence != .low)
    }
}
