package com.butvan.recordagent.service.auth;

import com.butvan.recordagent.dto.auth.AuthDtos;
import java.util.Optional;
import java.util.UUID;

/** 邮箱身份、验证码和可撤销的随机令牌会话服务接口。 */
public interface AuthService {

    /** 按规范化邮箱和用途串行申请；冷却期返回 429，邮件失败返回 503，不登记未发送验证码。 */
    AuthDtos.CodeResponse sendCode(AuthDtos.CodeRequest request);

    /** 消费一次性注册码后创建账户和会话；校验失败次数独立于错误响应提交，密码受 BCrypt 字节上限约束。 */
    AuthDtos.Session register(AuthDtos.RegisterRequest request);

    /** 密码登录。 */
    AuthDtos.Session passwordLogin(AuthDtos.PasswordLoginRequest request);

    /** 验证码登录。 */
    AuthDtos.Session codeLogin(AuthDtos.CodeLoginRequest request);

    /** 锁定有效刷新会话并轮换凭据，旧会话立即撤销；无效或重放返回 401。 */
    AuthDtos.Session refresh(String refreshToken);

    /** 验证重置码后更换密码。 */   
    void resetPassword(AuthDtos.ResetPasswordRequest request);

    /** 退出当前会话。 */
    void logout(UUID sessionId);

    /** 查询指定用户资料。 */
    AuthDtos.User user(UUID userId);

    /** 服务端已验证的会话身份；不携带令牌摘要或持久层类型。 */
    record VerifiedSession(UUID id, UUID userId) {}

    /** 验证凭据摘要、有效期、撤销状态及账户 active 状态；失败返回空身份，不泄露令牌。 */
    Optional<VerifiedSession> accessSession(String token);
}
