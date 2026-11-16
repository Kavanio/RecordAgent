import Foundation

/// API 地址只从统一配置读取；支持在应用内配置本地/局域网（Debug）或远程 HTTPS 地址。
enum APIConfiguration {
    static let debugAddressKey = "debug_server_base_url"
    static let serverAddressKey = "server_base_url"

    static func savedServerAddress() -> String {
        if let saved = UserDefaults.standard.string(forKey: serverAddressKey)?
            .trimmingCharacters(in: .whitespacesAndNewlines),
           !saved.isEmpty
        {
            return saved
        }
        #if DEBUG
            if let legacy = UserDefaults.standard.string(forKey: debugAddressKey)?
                .trimmingCharacters(in: .whitespacesAndNewlines),
               !legacy.isEmpty
            {
                return legacy
            }
        #endif
        return (Bundle.main.object(forInfoDictionaryKey: "RecordAgentAPIBaseURL") as? String ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
    }

    @discardableResult
    static func saveServerAddress(_ raw: String) throws -> URL {
        let url = try validateBaseURL(raw)
        let normalized = url.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        UserDefaults.standard.set(normalized, forKey: serverAddressKey)
        #if DEBUG
            UserDefaults.standard.set(normalized, forKey: debugAddressKey)
        #endif
        return url
    }

    static func clearServerAddress() {
        UserDefaults.standard.removeObject(forKey: serverAddressKey)
        #if DEBUG
            UserDefaults.standard.removeObject(forKey: debugAddressKey)
        #endif
    }

    static func baseURL() throws -> URL {
        try validateBaseURL(savedServerAddress())
    }

    static func validateBaseURL(_ raw: String) throws -> URL {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        guard let url = URL(string: trimmed),
              let scheme = url.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              url.host != nil, url.user == nil, url.password == nil,
              url.path.isEmpty || url.path == "/"
        else { throw APIClientError.invalidServer }
        #if DEBUG
            if scheme == "http" {
                guard let host = url.host?.lowercased(), isLocalHost(host) else {
                    throw APIClientError.invalidServer
                }
            }
        #else
            guard scheme == "https" else { throw APIClientError.invalidServer }
        #endif
        return url
    }

    static func serverIdentity() throws -> String {
        try baseURL().absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    }

    #if DEBUG
    private static func isLocalHost(_ host: String) -> Bool {
        if host == "localhost" || host.hasSuffix(".local") || host == "::1" { return true }
        let octets = host.split(separator: ".").compactMap { UInt8($0) }
        guard octets.count == 4 else { return false }
        return octets[0] == 10 || octets[0] == 127
            || (octets[0] == 172 && (16...31).contains(octets[1]))
            || (octets[0] == 192 && octets[1] == 168)
            || (octets[0] == 169 && octets[1] == 254)
    }
    #endif
}
