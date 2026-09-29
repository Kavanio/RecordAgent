import Foundation

/// 邮箱账户的真实 HTTP 实现；凭据仅写入独立 Keychain 会话。
final class APIAuthService: AuthServiceProtocol, Sendable {
    private let client: APIClient
    private let keychain: KeychainService

    init(client: APIClient, keychain: KeychainService = .shared) {
        self.client = client
        self.keychain = keychain
    }

    func loginWithPassword(email: String, password: String) async throws -> AuthSession {
        try await authenticate(
            "/api/v1/auth/password-login", body: ["email": email, "password": password])
    }

    func sendVerificationCode(email: String, purpose: AuthCodePurpose) async throws {
        let body = try APIClient.encoder.encode(["email": email, "purpose": purpose.rawValue])
        _ = try await client.send("/api/v1/auth/codes", method: "POST", body: body, authenticated: false)
    }

    func loginWithCode(email: String, code: String) async throws -> AuthSession {
        try await authenticate("/api/v1/auth/code-login", body: ["email": email, "code": code])
    }

    func register(email: String, code: String, password: String) async throws -> AuthSession {
        try await authenticate(
            "/api/v1/auth/register", body: ["email": email, "code": code, "password": password])
    }

    func resetPassword(email: String, code: String, newPassword: String) async throws {
        let body = try APIClient.encoder.encode(
            ["email": email, "code": code, "newPassword": newPassword])
        _ = try await client.send(
            "/api/v1/auth/reset-password", method: "POST", body: body, authenticated: false)
        keychain.clearAPISession()
    }

    func logout() async throws {
        _ = try await client.send("/api/v1/auth/logout", method: "POST")
        keychain.clearAPISession()
    }

    func getCurrentSession() -> AuthSession? {
        guard let server = try? APIConfiguration.serverIdentity() else { return nil }
        return keychain.getAPISession(server: server)
    }

    func restoreSession() async throws -> AuthSession? {
        try await client.restoreSession()
    }

    private func authenticate(_ path: String, body: [String: String]) async throws -> AuthSession {
        let payload = try APIClient.encoder.encode(body)
        let data = try await client.send(path, method: "POST", body: payload, authenticated: false)
        let session = try APIClient.decoder.decode(AuthSession.self, from: data)
        let server = try APIConfiguration.serverIdentity()
        guard keychain.saveAPISession(session, server: server) else {
            throw APIClientError.unavailable
        }
        return session
    }
}
