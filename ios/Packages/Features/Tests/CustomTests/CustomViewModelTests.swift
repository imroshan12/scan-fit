import Custom
import Foundation
import ScanData
import ScanModel
import Testing

@MainActor
@Suite("CustomViewModel")
struct CustomViewModelTests {
    private func preferences() -> UserPreferences {
        let name = "scanfit.customtests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: name) ?? .standard
        defaults.removePersistentDomain(forName: name)
        return UserPreferences(defaults: defaults)
    }

    @Test("a fresh form shows no errors until Continue is tried")
    func fresh() {
        let m = CustomViewModel(preferences: preferences())
        #expect(m.errors == [.maxKbMissing])
        #expect(!m.shows(.maxKbMissing), "a missing max is not shouted before Continue")
        #expect(m.continueTapped() == nil)
        #expect(m.attempted && m.shows(.maxKbMissing))
    }

    @Test("a Home card chooses the kind; anything else starts at Photo")
    func homeCard() {
        #expect(CustomViewModel(preferences: nil, doc: .signature).form.doc == .signature)
        #expect(CustomViewModel(preferences: nil, doc: .thumbImpression).form.doc == .thumbImpression)
        #expect(CustomViewModel(preferences: nil, doc: .handwrittenDeclaration).form.doc == .handwrittenDeclaration)
        #expect(CustomViewModel(preferences: nil, doc: .class10Certificate).form.doc == .photo)
        #expect(CustomViewModel(preferences: nil).form.doc == .photo)
    }

    @Test("fields keep only what they can hold")
    func filters() {
        let m = CustomViewModel(preferences: nil)
        m.setMaxKb("2a0 KB")
        m.setWidth("1,40")
        #expect(m.form.maxKb == "20")
        #expect(m.form.width == "140", "pixels are whole numbers")
        m.form.unit = .cm
        m.setWidth("3.5.67")
        #expect(m.form.width == "3.56", "one decimal point, two decimals")
    }

    @Test("centimetres show their pixels at the dpi")
    func centimetres() {
        let m = CustomViewModel(preferences: nil)
        m.form.unit = .cm
        m.setWidth("3.5")
        m.setHeight("4.5")
        #expect(m.pixels?.width == 276 && m.pixels?.height == 354, "200 dpi by default")
        m.setDpi("300")
        #expect(m.pixels?.width == 413 && m.pixels?.height == 531)
        m.form.unit = .px
        #expect(m.pixels == nil, "no pixel line when typing pixels")
    }

    @Test("a range error shows once its field has text")
    func rangeErrors() {
        let m = CustomViewModel(preferences: nil)
        m.setWidth("10")
        #expect(m.shows(.widthRange))
        #expect(!m.shows(.sizePair), "half a pair is fine while typing")
        m.setMaxKb("20")
        m.setMinKb("30")
        #expect(m.shows(.minKbRange))
    }

    @Test("Continue returns the size and remembers it")
    func continueRemembers() {
        let prefs = preferences()
        let m = CustomViewModel(preferences: prefs)
        m.form.doc = .signature
        m.setWidth("140")
        m.setHeight("60")
        m.setMinKb("10")
        m.setMaxKb("20")
        let size = m.continueTapped()
        #expect(size?.text == "signature;140x60;10-20;200")
        #expect(prefs.recentCustomSizes == ["signature;140x60;10-20;200"])
        #expect(m.recent == [size].compactMap { $0 })
    }

    @Test("a recent size refills the form in pixels")
    func recent() throws {
        let m = CustomViewModel(preferences: nil)
        m.form.unit = .cm
        m.useRecent(try #require(CustomSize.parse("signature;140x60;10-20;300")))
        #expect(m.form == CustomForm(doc: .signature, unit: .px, width: "140", height: "60", minKb: "10", maxKb: "20", dpi: "300"))
        #expect(m.errors.isEmpty)
    }
}
