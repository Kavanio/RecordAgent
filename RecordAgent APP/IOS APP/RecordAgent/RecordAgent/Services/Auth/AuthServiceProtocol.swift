import Foundation

public enum AuthCodePurpose: String, Sendable {
    case register, login, resetPassword = "reset_password"
}

/// 认证服务抽象协议
public protocol AuthServiceProtocol: Sendable {
    /// 邮箱 + 密码登录
    func loginWithPassword(email: String, password: String) async throws -> AuthSession

    /// 发送邮箱验证码
    func sendVerificationCode(email: String, purpose: AuthCodePurpose) async throws

    /// 邮箱 + 验证码登录
    func loginWithCode(email: String, code: String) async throws -> AuthSession

    /// 注册新账户
    func register(email: String, code: String, password: String) async throws -> AuthSession

    /// 使用邮箱验证码重设密码并撤销旧会话。
    func resetPassword(email: String, code: String, newPassword: String) async throws

    /// 退出登录
    func logout() async throws

    /// 获取当前会话状态
    func getCurrentSession() -> AuthSession?

    /// 启动时通过服务端验证或续期已保存会话。
    func restoreSession() async throws -> AuthSession?
}

public extension AuthServiceProtocol {
    func restoreSession() async throws -> AuthSession? { getCurrentSession() }
}
