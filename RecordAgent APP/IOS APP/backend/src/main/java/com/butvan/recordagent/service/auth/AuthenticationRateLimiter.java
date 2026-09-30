package com.butvan.recordagent.service.auth;

import com.butvan.recordagent.repository.auth.RateLimitRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 按认证入口来源及规范化邮箱限制请求，独立事务提交次数，认证失败不能回滚计数。 */
@Service
@RequiredArgsConstructor
public class AuthenticationRateLimiter {
    private final RateLimitRepository repository;

    /** 原子领取一次额度，超过限制返回稳定 429；键仅保存摘要。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean consume(String scope, String key, int seconds, int limit) {
        repository.cleanExpired();
        return repository.consume(scope, digest(key), seconds, limit) <= limit;
    }

    private static String digest(String key) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("限流摘要失败");
        }
    }
}
