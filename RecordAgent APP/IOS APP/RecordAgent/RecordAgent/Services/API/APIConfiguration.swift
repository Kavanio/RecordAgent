import Foundation

/// API 地址只从统一配置读取；Debug 可在应用内设置，正式环境必须配置 HTTPS。
enum APIConfiguration {
    static let debugAddressKey = "debug_server_base_url"

    static func baseURL() throws -> URL {
        #if DEBUG
            let raw = UserDefaults.standard.string(forKey: debugAddressKey) ?? ""
        #else
            let raw = Bundle.main.object(forInfoDictionaryKey: "RecordAgentAPIBaseURL") as? String ?? ""
        #endif
        guard let url = URL(string: raw.trimmingCharacters(in: .whitespacesAndNewlines)),
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
