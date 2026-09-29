package com.butvan.recordagent.service.auth;

/** 账户服务只依赖发送能力，不依赖具体邮件供应商。 */
public interface VerificationMailPort {
    void send(String email, String code);
}
