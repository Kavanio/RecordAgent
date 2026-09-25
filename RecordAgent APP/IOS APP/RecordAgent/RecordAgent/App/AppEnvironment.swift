import Foundation
import SwiftUI

/// 应用全局依赖装配容器
@MainActor
public final class AppEnvironment: ObservableObject {
    public static let shared = AppEnvironment()

    public let authService: AuthServiceProtocol
    public let keychain: KeychainService

    public init(
        authService: AuthServiceProtocol = MockAuthService(),
        keychain: KeychainService = .shared
    ) {
        self.authService = authService
        self.keychain = keychain
    }
}
