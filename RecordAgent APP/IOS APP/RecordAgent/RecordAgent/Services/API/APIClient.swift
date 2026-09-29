import Foundation

/// 服务端稳定错误码与客户端连接错误；不向界面或日志暴露请求正文和凭据。
enum APIClientError: LocalizedError, Sendable {
    case invalidServer
    case network
    case unauthenticated
    case conflict
    case unavailable
    case invalidResponse
    case server(code: String, status: Int)

    var errorDescription: String? {
        switch self {
        case .invalidServer: return String(localized: "api.error.server_address")
        case .network: return String(localized: "api.error.network")
        case .unauthenticated: return String(localized: "api.error.login")
        case .conflict: return String(localized: "api.error.conflict")
        case .unavailable: return String(localized: "api.error.unavailable")
        case .invalidResponse: return String(localized: "api.error.response")
        case .server: return String(localized: "api.error.request")
        }
    }
}

private struct ServerError: Decodable {
    let code: String
}

extension Notification.Name {
    static let apiSessionExpired = Notification.Name("recordagent.api.sessionExpired")
}

/// 统一处理地址、JSON、超时、错误映射和访问令牌续期。
actor APIClient {
    private let keychain: KeychainService
    private let session: URLSession
    private var refreshTask: Task<AuthSession, Error>?

    init(keychain: KeychainService = .shared, session: URLSession = .shared) {
        self.keychain = keychain
        self.session = session
    }

    func send(
        _ path: String, method: String = "GET", query: [URLQueryItem] = [],
        body: Data? = nil, authenticated: Bool = true
    ) async throws -> Data {
        let server = try APIConfiguration.serverIdentity()
        if !authenticated {
            let response = try await perform(path, method: method, query: query, body: body, token: nil)
            return try accepted(response)
        }
        guard var current = keychain.getAPISession(server: server) else {
            throw APIClientError.unauthenticated
        }
        if current.expiresAt.map({ $0 <= Date().addingTimeInterval(30) }) == true {
            current = try await refresh(current, server: server)
        }
        var response = try await perform(path, method: method, query: query, body: body, token: current.token)
        if response.1.statusCode == 401 {
            current = try await refresh(current, server: server)
            response = try await perform(path, method: method, query: query, body: body, token: current.token)
        }
        if response.1.statusCode == 401 { invalidateSession() }
        return try accepted(response)
    }

    /// 启动时向服务端核验旧会话，避免显示失效或其他服务器的账户数据。
    func restoreSession() async throws -> AuthSession? {
        let server = try APIConfiguration.serverIdentity()
        guard keychain.getAPISession(server: server) != nil else { return nil }
        do {
            let data = try await send("/api/v1/me")
            let user = try Self.decoder.decode(AuthUser.self, from: data)
            guard let session = keychain.getAPISession(server: server) else { return nil }
            return AuthSession(
                token: session.token, refreshToken: session.refreshToken, user: user,
                expiresAt: session.expiresAt)
        } catch APIClientError.unauthenticated {
            invalidateSession()
            return nil
        }
    }

    static var encoder: JSONEncoder { JSONEncoder() }

    static var decoder: JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let container = try decoder.singleValueContainer()
            let value = try container.decode(String.self)
            let fractional = ISO8601DateFormatter()
            fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            if let date = fractional.date(from: value) { return date }
            let regular = ISO8601DateFormatter()
            regular.formatOptions = [.withInternetDateTime]
            if let date = regular.date(from: value) { return date }
            throw APIClientError.invalidResponse
        }
        return decoder
    }

    private func refresh(_ previous: AuthSession, server: String) async throws -> AuthSession {
        if let refreshTask { return try await refreshTask.value }
        let task = Task { try await self.renew(previous, server: server) }
        refreshTask = task
        defer { refreshTask = nil }
        return try await task.value
    }

    private func renew(_ previous: AuthSession, server: String) async throws -> AuthSession {
        guard let refreshToken = previous.refreshToken else {
            invalidateSession()
            throw APIClientError.unauthenticated
        }
        let body = try Self.encoder.encode(["refreshToken": refreshToken])
        let result = try await perform("/api/v1/auth/refresh", method: "POST", body: body, token: nil)
        if result.1.statusCode == 401 {
            invalidateSession()
            throw APIClientError.unauthenticated
        }
        _ = try accepted(result)
        let next = try Self.decoder.decode(AuthSession.self, from: result.0)
        guard keychain.saveAPISession(next, server: server) else { throw APIClientError.unavailable }
        return next
    }

    private func invalidateSession() {
        keychain.clearAPISession()
        NotificationCenter.default.post(name: .apiSessionExpired, object: nil)
    }

    private func perform(
        _ path: String, method: String, query: [URLQueryItem] = [], body: Data? = nil,
        token: String?
    ) async throws -> (Data, HTTPURLResponse) {
        let base = try APIConfiguration.baseURL()
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false)
        else { throw APIClientError.invalidServer }
        components.path = path
        components.queryItems = query.isEmpty ? nil : query
        guard let url = components.url else { throw APIClientError.invalidServer }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 12
        request.setValue("*/*", forHTTPHeaderField: "Accept")
        if body != nil { request.setValue("application/json", forHTTPHeaderField: "Content-Type") }
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        do {
            let (data, response) = try await session.data(for: request)
            guard let response = response as? HTTPURLResponse else { throw APIClientError.invalidResponse }
            return (data, response)
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError where error.code == .cancelled {
            throw CancellationError()
        } catch let error as APIClientError {
            throw error
        } catch {
            throw APIClientError.network
        }
    }

    private func accepted(_ result: (Data, HTTPURLResponse)) throws -> Data {
        guard !(200..<300).contains(result.1.statusCode) else { return result.0 }
        let code = (try? Self.decoder.decode(ServerError.self, from: result.0))?.code ?? ""
        switch result.1.statusCode {
        case 401: throw APIClientError.unauthenticated
        case 409: throw APIClientError.conflict
        case 503: throw APIClientError.unavailable
        default: throw APIClientError.server(code: code, status: result.1.statusCode)
        }
    }
}
