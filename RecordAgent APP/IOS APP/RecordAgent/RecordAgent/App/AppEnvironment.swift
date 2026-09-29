import Foundation
import SwiftUI

/// 应用全局依赖装配容器
@MainActor
public final class AppEnvironment: ObservableObject {
    public static let shared = AppEnvironment()

    private let languageStorageKey = "app_selected_language"

    public let authService: AuthServiceProtocol
    public let keychain: KeychainService
    let homeService: any HomeDataServiceProtocol
    let calendarService: any CalendarDataServiceProtocol
    let financeService: any FinanceServiceProtocol
    let profileOverviewService: any ProfileOverviewServiceProtocol
    let profileActivityService: any ProfileActivityServiceProtocol
    let profileAppearanceService: any ProfileAppearanceServiceProtocol

    /// 当前选中的应用语言（支持跟随系统、简体中文、英文）
    @Published public var selectedLanguage: AppLanguage {
        didSet {
            UserDefaults.standard.set(selectedLanguage.rawValue, forKey: languageStorageKey)
        }
    }

    /// 当前生效的 Locale 环境
    public var currentLocale: Locale {
        selectedLanguage.locale
    }

    public init(
        authService: AuthServiceProtocol? = nil,
        keychain: KeychainService = .shared
    ) {
        let client = APIClient(keychain: keychain)
        self.authService = authService ?? APIAuthService(client: client, keychain: keychain)
        self.keychain = keychain
        let sharedData = APICalendarDataService(client: client)
        self.homeService = sharedData
        self.calendarService = sharedData
        self.financeService = APIFinanceService(client: client)
        self.profileOverviewService = APIProfileOverviewService(client: client)
        self.profileActivityService = HomeProfileActivityService(homeService: homeService)
        self.profileAppearanceService = APIProfileAppearanceService(client: client)

        // 从 UserDefaults 读取持久化的语言偏好，默认跟随系统
        if let savedRaw = UserDefaults.standard.string(forKey: languageStorageKey),
            let language = AppLanguage(rawValue: savedRaw)
        {
            self.selectedLanguage = language
        } else {
            self.selectedLanguage = .system
        }
    }

    /// 设置应用语言
    public func setLanguage(_ language: AppLanguage) {
        selectedLanguage = language
    }
}
