import SwiftUI

@main
struct RecordAgentApp: App {
    @StateObject private var environment = AppEnvironment.shared

    var body: some Scene {
        WindowGroup {
            RootCoordinatorView(authViewModel: AuthViewModel(
                authService: environment.authService,
                keychain: environment.keychain
            ))
            .environmentObject(environment)
            .environment(\.locale, environment.currentLocale)
            .id(environment.selectedLanguage) // 切换语言时热刷新整棵视图树
            .preferredColorScheme(.light) // 对齐设计图纯净白底
        }
    }
}
