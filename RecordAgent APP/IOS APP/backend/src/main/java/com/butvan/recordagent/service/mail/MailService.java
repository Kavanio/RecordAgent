package com.butvan.recordagent.service.mail;

/** 账户服务只依赖发送能力，不依赖具体邮件供应商。 */
public interface MailService {
    void send(String email, String code);
}
