import Foundation
import Security

/// 基于 iOS Keychain 的安全密钥与凭据存储服务
public final class KeychainService: Sendable {
    public static let shared = KeychainService()

    private let serviceName = "com.butvan.recordagent"
    private let tokenKey = "auth_bearer_token"
    private let emailKey = "saved_login_email"

    public init() {}

    // MARK: - Token 操作

    public func saveToken(_ token: String) -> Bool {
        save(key: tokenKey, value: token)
    }

    public func getToken() -> String? {
        getString(key: tokenKey)
    }

    public func clearToken() {
        delete(key: tokenKey)
    }

    // MARK: - 记住的邮箱操作

    public func saveEmail(_ email: String) -> Bool {
        save(key: emailKey, value: email)
    }

    public func getSavedEmail() -> String? {
        getString(key: emailKey)
    }

    public func clearSavedEmail() {
        delete(key: emailKey)
    }

    // MARK: - 底层 Keychain API

    private func save(key: String, value: String) -> Bool {
        guard let data = value.data(using: .utf8) else { return false }

        // 先尝试删除旧条目
        delete(key: key)

        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]

        let status = SecItemAdd(query as CFDictionary, nil)
        return status == errSecSuccess
    }

    private func getString(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        guard status == errSecSuccess, let data = item as? Data else {
            return nil
        }
        return String(data: data, encoding: .utf8)
    }

    private func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}
