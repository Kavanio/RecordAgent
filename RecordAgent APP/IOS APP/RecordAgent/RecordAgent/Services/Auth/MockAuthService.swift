import Foundation

/// 开发与测试期间的可控假认证服务
public final class MockAuthService: AuthServiceProtocol, @unchecked Sendable {
    private let keychain: KeychainService
    private var currentSession: AuthSession?
    private let lock = NSLock()

    public init(keychain: KeychainService = .shared) {
        self.keychain = keychain
        // 尝试从 Keychain 恢复已有 Token
        if let token = keychain.getToken(), let email = keychain.getSavedEmail() {
            let mockUser = AuthUser(
                id: "user_\(abs(email.hashValue))",
                email: email,
                nickname: email.components(separatedBy: "@").first ?? "RecordUser"
            )
            self.currentSession = AuthSession(token: token, user: mockUser)
        }
    }

    public func loginWithPassword(email: String, password: String) async throws -> AuthSession {
        // 模拟网络延迟
        try await Task.sleep(nanoseconds: 600_000_000)

        if let error = AuthValidator.validateEmail(email) {
            throw error
        }
        if let error = AuthValidator.validatePassword(password) {
            throw error
        }

        // 模拟凭据校验：如果密码为 error123 则模拟密码错误
        if password == "error123" {
            throw AuthError.custom("账号或密码不正确，请重新输入")
        }

        let user = AuthUser(
            id: "usr_\(UUID().uuidString.prefix(8).lowercased())",
            email: email,
            nickname: email.components(separatedBy: "@").first ?? "RecordUser"
        )
        let token = "mock_jwt_token_\(UUID().uuidString)"
        let session = AuthSession(token: token, user: user, expiresAt: Date().addingTimeInterval(86400 * 30))

        lock.lock()
        self.currentSession = session
        lock.unlock()

        _ = keychain.saveToken(token)
        _ = keychain.saveEmail(email)

        return session
    }

    public func sendVerificationCode(email: String) async throws {
        try await Task.sleep(nanoseconds: 500_000_000)
        if let error = AuthValidator.validateEmail(email) {
            throw error
        }
        // 模拟发送成功
    }

    public func loginWithCode(email: String, code: String) async throws -> AuthSession {
        try await Task.sleep(nanoseconds: 600_000_000)

        if let error = AuthValidator.validateEmail(email) {
            throw error
        }
        if let error = AuthValidator.validateCode(code) {
            throw error
        }

        // 模拟验证码错误分支 (若输入 0000 则判定错误)
        if code == "0000" {
            throw AuthError.invalidVerificationCode
        }

        let user = AuthUser(
            id: "usr_\(UUID().uuidString.prefix(8).lowercased())",
            email: email,
            nickname: email.components(separatedBy: "@").first ?? "RecordUser"
        )
        let token = "mock_jwt_code_token_\(UUID().uuidString)"
        let session = AuthSession(token: token, user: user, expiresAt: Date().addingTimeInterval(86400 * 30))

        lock.lock()
        self.currentSession = session
        lock.unlock()

        _ = keychain.saveToken(token)
        _ = keychain.saveEmail(email)

        return session
    }

    public func register(email: String, code: String, password: String) async throws -> AuthSession {
        try await Task.sleep(nanoseconds: 700_000_000)

        if let error = AuthValidator.validateEmail(email) {
            throw error
        }
        if let error = AuthValidator.validateCode(code) {
            throw error
        }
        if let error = AuthValidator.validatePassword(password) {
            throw error
        }

        let user = AuthUser(
            id: "usr_\(UUID().uuidString.prefix(8).lowercased())",
            email: email,
            nickname: email.components(separatedBy: "@").first ?? "RecordUser"
        )
        let token = "mock_jwt_register_token_\(UUID().uuidString)"
        let session = AuthSession(token: token, user: user, expiresAt: Date().addingTimeInterval(86400 * 30))

        lock.lock()
        self.currentSession = session
        lock.unlock()

        _ = keychain.saveToken(token)
        _ = keychain.saveEmail(email)

        return session
    }

    public func logout() async throws {
        try await Task.sleep(nanoseconds: 200_000_000)
        lock.lock()
        self.currentSession = nil
        lock.unlock()
        keychain.clearToken()
    }

    public func getCurrentSession() -> AuthSession? {
        lock.lock()
        defer { lock.unlock() }
        return currentSession
    }
}
