import Foundation

/// 认证表单模式
public enum AuthMode: String, CaseIterable, Identifiable, Sendable {
    case passwordLogin = "密码登录"
    case codeLogin = "验证码登录"
    case register = "注册"

    public var id: String { rawValue }
}

/// 登录认证会话
public struct AuthSession: Codable, Sendable, Equatable {
    public let token: String
    public let refreshToken: String?
    public let user: AuthUser
    public let expiresAt: Date?

    public init(token: String, refreshToken: String? = nil, user: AuthUser, expiresAt: Date? = nil) {
        self.token = token
        self.refreshToken = refreshToken
        self.user = user
        self.expiresAt = expiresAt
    }
}

/// 用户领域实体
public struct AuthUser: Codable, Identifiable, Sendable, Equatable {
    public let id: String
    public let email: String
    public let nickname: String?
    public let avatarUrl: String?
    public let createdAt: Date

    public init(id: String, email: String, nickname: String? = nil, avatarUrl: String? = nil, createdAt: Date = Date()) {
        self.id = id
        self.email = email
        self.nickname = nickname
        self.avatarUrl = avatarUrl
        self.createdAt = createdAt
    }
}

/// 认证业务错误枚举
public enum AuthError: LocalizedError, Sendable, Equatable {
    case emptyEmail
    case invalidEmail
    case emptyPassword
    case passwordTooShort
    case emptyVerificationCode
    case invalidVerificationCode
    case networkUnavailable
    case unauthorized
    case serverError(String)
    case custom(String)

    public var errorDescription: String? {
        switch self {
        case .emptyEmail:
            return "请输入邮箱地址"
        case .invalidEmail:
            return "邮箱格式不正确，请检查输入"
        case .emptyPassword:
            return "请输入密码"
        case .passwordTooShort:
            return "密码长度至少需要 6 位"
        case .emptyVerificationCode:
            return "请输入邮箱验证码"
        case .invalidVerificationCode:
            return "验证码错误或已过期"
        case .networkUnavailable:
            return "网络连接不可用，请稍后重试"
        case .unauthorized:
            return "登录状态已失效，请重新登录"
        case .serverError(let message):
            return message
        case .custom(let message):
            return message
        }
    }
}

/// 输入校验工具
public enum AuthValidator {
    private static let emailRegex = #"^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$"#

    public static func validateEmail(_ email: String) -> AuthError? {
        let trimmed = email.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .emptyEmail
        }
        guard trimmed.range(of: emailRegex, options: .regularExpression) != nil else {
            return .invalidEmail
        }
        return nil
    }

    public static func validatePassword(_ password: String) -> AuthError? {
        if password.isEmpty {
            return .emptyPassword
        }
        if password.count < 6 {
            return .passwordTooShort
        }
        return nil
    }

    public static func validateCode(_ code: String) -> AuthError? {
        let trimmed = code.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            return .emptyVerificationCode
        }
        if trimmed.count < 4 {
            return .invalidVerificationCode
        }
        return nil
    }
}
