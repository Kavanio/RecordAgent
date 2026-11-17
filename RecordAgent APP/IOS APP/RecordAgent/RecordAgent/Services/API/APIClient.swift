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

private struct APIResultEnvelope<T: Decodable>: Decodable {
    let code: Int
    let message: String?
    let data: T?
}

extension Notification.Name {
    static let apiSessionExpired = Notification.Name("recordagent.api.sessionExpired")
}

/// 统一处理地址、JSON、超时、错误映射和 Bearer Token 鉴权。
actor APIClient {
    private let keychain: KeychainService
    private let session: URLSession

    init(keychain: KeychainService = .shared, session: URLSession = .shared) {
        self.keychain = keychain
        self.session = session
    }

    func send(
        _ path: String, method: String = "GET", query: [URLQueryItem] = [],
        body: Data? = nil, authenticated: Bool = true, idempotencyKey: UUID? = nil
    ) async throws -> Data {
        let base = try APIConfiguration.baseURL()
        let server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if !authenticated {
            let response = try await perform(
                path, base: base, method: method, query: query, body: body, token: nil,
                idempotencyKey: idempotencyKey)
            return try accepted(response)
        }
        guard let current = keychain.getAPISession(server: server) else {
            throw APIClientError.unauthenticated
        }
        let token = current.token.trimmingCharacters(in: .whitespacesAndNewlines)
        let response = try await perform(
            path, base: base, method: method, query: query, body: body,
            token: token.isEmpty ? nil : token, idempotencyKey: idempotencyKey)
        if response.1.statusCode == 401 {
            invalidateSession()
            throw APIClientError.unauthenticated
        }
        return try accepted(response)
    }

    /// 直连校验服务器地址与 Token，成功后持久化配置与单用户会话。
    func connect(serverAddress: String, token: String) async throws -> AuthSession {
        let base = try APIConfiguration.validateBaseURL(serverAddress)
        let server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let trimmedToken = token.trimmingCharacters(in: .whitespacesAndNewlines)
        let result = try await perform(
            "/api/health", base: base, method: "GET", token: trimmedToken.isEmpty ? nil : trimmedToken)
        if result.1.statusCode == 401 {
            throw APIClientError.unauthenticated
        }
        _ = try accepted(result)
        _ = try APIConfiguration.saveServerAddress(server)
        let user = AuthUser(
            id: "butvan-single-user",
            email: base.host ?? server,
            nickname: "Butvan"
        )
        let authSession = AuthSession(token: trimmedToken, refreshToken: nil, user: user, expiresAt: nil)
        guard keychain.saveAPISession(authSession, server: server) else {
            throw APIClientError.unavailable
        }
        return authSession
    }

    /// 启动时向服务端 `/api/health` 核验旧会话与 Token 有效性。
    func restoreSession() async throws -> AuthSession? {
        let server = try APIConfiguration.serverIdentity()
        guard let session = keychain.getAPISession(server: server) else { return nil }
        do {
            _ = try await send("/api/health")
            return session
        } catch APIClientError.unauthenticated {
            invalidateSession()
            return nil
        }
    }

    func sendResult<T: Decodable>(
        _ path: String, method: String = "GET", query: [URLQueryItem] = [],
        body: Data? = nil, authenticated: Bool = true, idempotencyKey: UUID? = nil
    ) async throws -> T {
        let data = try await send(
            path, method: method, query: query, body: body, authenticated: authenticated,
            idempotencyKey: idempotencyKey)
        return try Self.decodeResult(T.self, from: data)
    }

    func sendOptionalResult<T: Decodable>(
        _ path: String, method: String = "GET", query: [URLQueryItem] = [],
        body: Data? = nil, authenticated: Bool = true, idempotencyKey: UUID? = nil
    ) async throws -> T? {
        let data = try await send(
            path, method: method, query: query, body: body, authenticated: authenticated,
            idempotencyKey: idempotencyKey)
        let envelope = try Self.decoder.decode(APIResultEnvelope<T>.self, from: data)
        if envelope.code == 409 { throw APIClientError.conflict }
        guard envelope.code == 200 else {
            throw APIClientError.server(code: String(envelope.code), status: envelope.code)
        }
        return envelope.data
    }

    /// 请求非 `Result` 包装的原生 JSON 接口，保留 204/404 给调用方处理空状态。
    func sendRawAllowingEmpty(
        _ path: String,
        method: String = "GET",
        body: Data? = nil,
        emptyStatusCodes: Set<Int> = [204]
    ) async throws -> Data? {
        let base = try APIConfiguration.baseURL()
        let server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let current = keychain.getAPISession(server: server) else {
            throw APIClientError.unauthenticated
        }
        let token = current.token.trimmingCharacters(in: .whitespacesAndNewlines)
        let response = try await perform(
            path, base: base, method: method, body: body,
            token: token.isEmpty ? nil : token
        )
        if response.1.statusCode == 401 {
            invalidateSession()
            throw APIClientError.unauthenticated
        }
        if emptyStatusCodes.contains(response.1.statusCode) {
            return nil
        }
        return try accepted(response)
    }

    /// 打开带 Bearer Token 鉴权的长连接字节流，供 Agent SSE 实时消费。
    func openStreamBytes(
        _ path: String,
        method: String = "POST",
        body: Data? = nil
    ) async throws -> URLSession.AsyncBytes {
        let base = try APIConfiguration.baseURL()
        let server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let current = keychain.getAPISession(server: server) else {
            throw APIClientError.unauthenticated
        }
        guard var components = URLComponents(url: base, resolvingAgainstBaseURL: false) else {
            throw APIClientError.invalidServer
        }
        components.path = path
        guard let url = components.url else { throw APIClientError.invalidServer }

        var request = URLRequest(url: url)
        request.httpMethod = method
        request.httpBody = body
        request.timeoutInterval = 300
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        if body != nil {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let token = current.token.trimmingCharacters(in: .whitespacesAndNewlines)
        if !token.isEmpty {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }

        do {
            let (bytes, response) = try await session.bytes(for: request)
            guard let httpResponse = response as? HTTPURLResponse else {
                throw APIClientError.invalidResponse
            }
            if httpResponse.statusCode == 401 {
                invalidateSession()
                throw APIClientError.unauthenticated
            }
            guard (200..<300).contains(httpResponse.statusCode) else {
                if httpResponse.statusCode == 409 { throw APIClientError.conflict }
                if httpResponse.statusCode == 503 { throw APIClientError.unavailable }
                throw APIClientError.server(code: "", status: httpResponse.statusCode)
            }
            return bytes
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

    func sendMultipartResult<T: Decodable>(
        _ path: String, fieldName: String = "file", filename: String, mimeType: String, fileData: Data
    ) async throws -> T {
        let base = try APIConfiguration.baseURL()
        let server = base.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let current = keychain.getAPISession(server: server) else {
            throw APIClientError.unauthenticated
        }
        let boundary = "Boundary-\(UUID().uuidString)"
        var payload = Data()
        payload.append(Data("--\(boundary)\r\n".utf8))
        payload.append(Data("Content-Disposition: form-data; name=\"\(fieldName)\"; filename=\"\(filename)\"\r\n".utf8))
        payload.append(Data("Content-Type: \(mimeType)\r\n\r\n".utf8))
        payload.append(fileData)
        payload.append(Data("\r\n--\(boundary)--\r\n".utf8))

        let token = current.token.trimmingCharacters(in: .whitespacesAndNewlines)
        let response = try await perform(
            path, base: base, method: "POST", body: payload,
            token: token.isEmpty ? nil : token,
            contentType: "multipart/form-data; boundary=\(boundary)"
        )
        if response.1.statusCode == 401 {
            invalidateSession()
            throw APIClientError.unauthenticated
        }
        let data = try accepted(response)
        return try Self.decodeResult(T.self, from: data)
    }

    static func decodeResult<T: Decodable>(_ type: T.Type, from data: Data) throws -> T {
        let envelope = try decoder.decode(APIResultEnvelope<T>.self, from: data)
        if envelope.code == 409 { throw APIClientError.conflict }
        guard envelope.code == 200 else {
            throw APIClientError.server(code: String(envelope.code), status: envelope.code)
        }
        guard let value = envelope.data else {
            throw APIClientError.invalidResponse
        }
        return value
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

    private func invalidateSession() {
        keychain.clearAPISession()
        NotificationCenter.default.post(name: .apiSessionExpired, object: nil)
    }

    private func perform(
        _ path: String, base: URL, method: String, query: [URLQueryItem] = [], body: Data? = nil,
        token: String?, idempotencyKey: UUID? = nil, contentType: String = "application/json"
    ) async throws -> (Data, HTTPURLResponse) {
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
        if body != nil { request.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        if let idempotencyKey {
            request.setValue(idempotencyKey.uuidString, forHTTPHeaderField: "Idempotency-Key")
        }
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
