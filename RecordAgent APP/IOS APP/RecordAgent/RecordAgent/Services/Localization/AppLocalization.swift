import Foundation

/// 非 SwiftUI 文本必须按应用语言选取资源包；Locale 参数本身只保证格式化区域。
enum AppLocalization {
    static func string(_ key: String.LocalizationValue, locale: Locale) -> String {
        let language = Bundle.preferredLocalizations(
            from: Bundle.main.localizations, forPreferences: [locale.identifier]
        ).first
        let bundle =
            language.flatMap { Bundle.main.path(forResource: $0, ofType: "lproj") }
            .flatMap { Bundle(path: $0) } ?? .main
        return String(localized: key, bundle: bundle, locale: locale)
    }
}
