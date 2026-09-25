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
            .preferredColorScheme(.light) // 对齐设计图纯净白底
        }
    }
}
