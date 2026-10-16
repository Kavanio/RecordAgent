import Foundation
import SwiftUI

/// 应用支持的多语言枚举
public enum AppLanguage: String, CaseIterable, Identifiable, Sendable {
    case system = "system"
    case zhHans = "zh-Hans"
    case en = "en"

    public var id: String { rawValue }

    /// 在语言切换界面中的显示名称（本地语言自称 + 选项提示）
    public var displayName: String {
        switch self {
        case .system:
            return String(localized: "language.system", defaultValue: "跟随系统")
        case .zhHans:
            return "简体中文"
        case .en:
            return "English"
        }
    }

    /// 映射到 Foundation 的 Locale 实例
    public var locale: Locale {
        switch self {
        case .system:
            return .autoupdatingCurrent
        case .zhHans:
            return Locale(identifier: "zh-Hans")
        case .en:
            return Locale(identifier: "en")
        }
    }
}
