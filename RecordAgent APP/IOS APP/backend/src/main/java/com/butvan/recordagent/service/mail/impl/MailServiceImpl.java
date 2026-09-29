package com.butvan.recordagent.service.mail.impl;

import com.butvan.recordagent.service.mail.MailService;

import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** SMTP 发送适配器；邮件凭据仅由部署环境注入。 */
@Component
@RequiredArgsConstructor
public class MailServiceImpl implements MailService {
    private final JavaMailSender sender;

    @Value("${recordagent.mail.from:}")
    private String from;

    @Override
    public void send(String email, String code) {
        if (from.isBlank()) throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "mail unavailable");
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("RecordAgent 验证码");
        message.setText("验证码：" + code + "，10 分钟内有效。请勿转发。");
        try {
            sender.send(message);
        } catch (MailException exception) {
            throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "mail unavailable");
        }
    }
}
