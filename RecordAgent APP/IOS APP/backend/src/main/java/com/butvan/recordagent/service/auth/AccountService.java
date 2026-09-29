package com.butvan.recordagent.service.auth;

import com.butvan.recordagent.dto.auth.AuthDtos;
import com.butvan.recordagent.repository.auth.AccountStore;
import java.util.Optional;
import java.util.UUID;

/** 邮箱身份、验证码和可撤销的随机令牌会话服务接口。 */
public interface AccountService {

    /** 发送邮箱验证码。 */
    AuthDtos.CodeResponse sendCode(AuthDtos.CodeRequest request);

    /** 邮箱验证后创建账户。 */
    AuthDtos.Session register(AuthDtos.RegisterRequest request);

    /** 密码登录。 */
    AuthDtos.Session passwordLogin(AuthDtos.PasswordLoginRequest request);

    /** 验证码登录。 */
    AuthDtos.Session codeLogin(AuthDtos.CodeLoginRequest request);

    /** 刷新会话凭据。 */
    AuthDtos.Session refresh(String refreshToken);

    /** 验证重置码后更换密码。 */
    void resetPassword(AuthDtos.ResetPasswordRequest request);

    /** 退出当前会话。 */
    void logout(UUID sessionId);

    /** 查询指定用户资料。 */
    AuthDtos.User user(UUID userId);

    /** 验证 Bearer Token 并读取会话。 */
    Optional<AccountStore.SessionRow> accessSession(String token);
}
