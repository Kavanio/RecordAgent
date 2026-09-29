import Foundation

/// 仅用于 Debug 的健康探测，不发送认证凭据。
#if DEBUG
struct DebugServerProbe: Sendable {
    func isHealthy(address: String) async -> Bool {
        guard let base = URL(string: address.trimmingCharacters(in: .whitespacesAndNewlines)),
              let scheme = base.scheme?.lowercased(),
              ["http", "https"].contains(scheme),
              base.host != nil,
              var components = URLComponents(url: base, resolvingAgainstBaseURL: false)
        else { return false }
        components.path = "/actuator/health"
        components.query = nil
        guard let url = components.url else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 8
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            return (response as? HTTPURLResponse)?.statusCode == 200
                && String(data: data, encoding: .utf8)?.contains("\"UP\"") == true
        } catch {
            return false
        }
    }
}
#endif
