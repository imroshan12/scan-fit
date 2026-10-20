import XCTest

@MainActor
final class PaywallSmokeTests: XCTestCase {
    private func launch(used: Int, unavailable: Bool = false, hindiLarge: Bool = false) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["--ui-testing", "-AppleLanguages", hindiLarge ? "(hi)" : "(en)"]
        app.launchEnvironment["SCANFIT_UI_TEST_SAVES_USED"] = String(used)
        if unavailable { app.launchEnvironment["SCANFIT_UI_TEST_STORE"] = "unavailable" }
        if hindiLarge {
            app.launchArguments += [
                "-AppleLocale", "hi_IN", "-AppleInterfaceStyle", "Dark",
                "-UIPreferredContentSizeCategoryName", "UICTContentSizeCategoryAccessibilityXXXL",
            ]
        }
        app.launch()
        let settings = app.tabBars.buttons.element(boundBy: 2)
        XCTAssertTrue(settings.waitForExistence(timeout: 15))
        settings.tap()
        XCTAssertTrue(app.buttons["settings.get_pro"].waitForExistence(timeout: 10))
        return app
    }

    private func tap(_ element: XCUIElement, in app: XCUIApplication) {
        for _ in 0..<8 {
            if element.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(element.isHittable, "Control must be reachable without text clipping")
        element.press(forDuration: 0.2)
    }

    func testFreeSavesAndDismissalKeepSettingsIntact() {
        let app = launch(used: 17)
        defer { app.terminate() }
        XCTAssertEqual(app.staticTexts["settings.free_saves"].label, "3 free saves left")
        tap(app.buttons["settings.get_pro"], in: app)
        XCTAssertTrue(app.staticTexts["paywall.title"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["paywall.product.pro_yearly"].exists)
        tap(app.buttons["paywall.restore"], in: app)
        XCTAssertTrue(app.staticTexts["paywall.message"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["paywall.message"].label, "No Pro purchase was found for this account.")
        app.buttons["paywall.close"].tap()
        XCTAssertTrue(app.buttons["settings.get_pro"].waitForExistence(timeout: 5))
        XCTAssertEqual(app.staticTexts["settings.free_saves"].label, "3 free saves left")
    }

    func testExhaustedQuotaPurchaseUpdatesProStatus() {
        let app = launch(used: 20)
        defer { app.terminate() }
        XCTAssertTrue(app.staticTexts["settings.free_saves"].label.hasPrefix("No free saves left."))
        tap(app.buttons["settings.get_pro"], in: app)
        let lifetime = app.buttons["paywall.product.pro_lifetime"]
        XCTAssertTrue(lifetime.waitForExistence(timeout: 5))
        tap(lifetime, in: app)
        XCTAssertTrue(app.staticTexts["paywall.thanks"].waitForExistence(timeout: 5))
        tap(app.buttons["paywall.done"], in: app)
        XCTAssertTrue(app.staticTexts["settings.pro_active"].waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["settings.get_pro"].exists)
    }

    func testUnavailableStoreCanRetryAndClose() {
        let app = launch(used: 20, unavailable: true)
        defer { app.terminate() }
        tap(app.buttons["settings.get_pro"], in: app)
        let retry = app.buttons["paywall.retry"]
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        tap(retry, in: app)
        XCTAssertTrue(retry.waitForExistence(timeout: 5))
        XCTAssertFalse(app.buttons["paywall.product.pro_yearly"].exists)
        tap(app.buttons["paywall.restore"], in: app)
        XCTAssertTrue(app.staticTexts["paywall.message"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["paywall.message"].label.contains("Can't reach the store"))
        app.buttons["paywall.close"].tap()
        XCTAssertTrue(app.buttons["settings.get_pro"].waitForExistence(timeout: 5))
    }

    func testHindiLargestTextHasStackedProductsAndReachableLegalLinks() {
        let app = launch(used: 19, hindiLarge: true)
        defer { app.terminate() }
        tap(app.buttons["settings.get_pro"], in: app)
        let title = app.staticTexts["paywall.title"]
        XCTAssertTrue(title.waitForExistence(timeout: 5))
        XCTAssertEqual(title.label, "Pro के साथ सेव करते रहें")
        let yearly = app.buttons["paywall.product.pro_yearly"]
        let lifetime = app.buttons["paywall.product.pro_lifetime"]
        for _ in 0..<5 {
            if lifetime.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(lifetime.isHittable)
        XCTAssertTrue(yearly.label.contains("प्रति वर्ष"))
        XCTAssertTrue(lifetime.label.contains("एक बार"))
        XCTAssertGreaterThanOrEqual(lifetime.frame.minY, yearly.frame.maxY)
        XCTAssertEqual(lifetime.frame.minX, yearly.frame.minX, accuracy: 2)
        let privacy = app.descendants(matching: .any)["paywall.privacy"].firstMatch
        let terms = app.descendants(matching: .any)["paywall.terms"].firstMatch
        for _ in 0..<8 {
            if privacy.isHittable && terms.isHittable { break }
            app.swipeUp()
        }
        XCTAssertTrue(privacy.isHittable, app.debugDescription)
        XCTAssertTrue(terms.isHittable, app.debugDescription)
        app.buttons["paywall.close"].tap()
        XCTAssertTrue(app.buttons["settings.get_pro"].waitForExistence(timeout: 5))
    }
}
