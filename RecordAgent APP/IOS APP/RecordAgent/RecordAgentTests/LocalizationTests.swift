import XCTest
@testable import RecordAgent

final class LocalizationTests: XCTestCase {
    func testAppLanguageLocales() {
        XCTAssertEqual(AppLanguage.zhHans.locale.identifier, "zh-Hans")
        XCTAssertEqual(AppLanguage.en.locale.identifier, "en")
        XCTAssertEqual(AppLanguage.system.locale.identifier, Locale.autoupdatingCurrent.identifier)
    }

    func testAppLanguageDisplayNames() {
        XCTAssertEqual(AppLanguage.zhHans.displayName, "简体中文")
        XCTAssertEqual(AppLanguage.en.displayName, "English")
    }

    @MainActor
    func testAppEnvironmentLanguageSwitching() {
        let env = AppEnvironment()
        
        env.setLanguage(.en)
        XCTAssertEqual(env.selectedLanguage, .en)
        XCTAssertEqual(env.currentLocale.identifier, "en")
        
        env.setLanguage(.zhHans)
        XCTAssertEqual(env.selectedLanguage, .zhHans)
        XCTAssertEqual(env.currentLocale.identifier, "zh-Hans")
        
        env.setLanguage(.system)
        XCTAssertEqual(env.selectedLanguage, .system)
    }

    func testLocalizableStringsFileValidity() throws {
        guard let bundlePath = Bundle(for: type(of: self)).path(forResource: "Localizable", ofType: "xcstrings") ??
                Bundle.main.path(forResource: "Localizable", ofType: "xcstrings") else {
            // 在测试环境中如果未直接打包资源，则回退读取源码资源目录
            let projectDir = URL(fileURLWithPath: #file)
                .deletingLastPathComponent()
                .deletingLastPathComponent()
            let xcstringsURL = projectDir.appendingPathComponent("RecordAgent/Resources/Localizable.xcstrings")
            
            let data = try Data(contentsOf: xcstringsURL)
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
            XCTAssertNotNil(json)
            XCTAssertEqual(json?["sourceLanguage"] as? String, "zh-Hans")
            
            let strings = json?["strings"] as? [String: Any]
            XCTAssertNotNil(strings)
            XCTAssertNotNil(strings?["tab.home"])
            XCTAssertNotNil(strings?["auth.login.button"])
            return
        }
        
        let data = try Data(contentsOf: URL(fileURLWithPath: bundlePath))
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        XCTAssertNotNil(json)
    }
}
