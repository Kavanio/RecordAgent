import Foundation

/// 认证服务抽象协议
public protocol AuthServiceProtocol: Sendable {
    /// 邮箱 + 密码登录
    func loginWithPassword(email: String, password: String) async throws -> AuthSession

    /// 发送邮箱验证码
    func sendVerificationCode(email: String) async throws

    /// 邮箱 + 验证码登录
    func loginWithCode(email: String, code: String) async throws -> AuthSession

    /// 注册新账户
    func register(email: String, code: String, password: String) async throws -> AuthSession

    /// 退出登录
    func logout() async throws

    /// 获取当前会话状态
    func getCurrentSession() -> AuthSession?
}
