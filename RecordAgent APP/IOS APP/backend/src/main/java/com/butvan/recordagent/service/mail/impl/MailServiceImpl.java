package com.butvan.recordagent.service.mail.impl;

import com.butvan.recordagent.config.BusinessProperties;
import com.butvan.recordagent.filter.RequestIdFilter;
import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import com.butvan.recordagent.service.mail.MailService;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

/** SMTP 发送适配器；邮件凭据仅由部署环境注入。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MailServiceImpl implements MailService {
    private final JavaMailSender sender;

    private final BusinessProperties properties;

    @Override
    public void send(String email, String code) {
        String from = properties.getMail().getFrom();
        if (from.isBlank()) {
            log.error(
                    "Mail sender is not configured: requestId={}",
                    RequestIdFilter.currentRequestId());
            throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "mail unavailable");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(email);
        message.setSubject("RecordAgent 验证码");
        message.setText(
                "验证码："
                        + code
                        + "，"
                        + properties.getAuth().getCodeValidityMinutes()
                        + " 分钟内有效。请勿转发。");
        try {
            sender.send(message);
        } catch (MailException exception) {
            log.error(
                    "Mail delivery failed: requestId={} errorType={} smtpError={} authEnabled={} starttlsEnabled={}",
                    RequestIdFilter.currentRequestId(),
                    exception.getClass().getSimpleName(),
                    smtpError(exception),
                    mailProperty("mail.smtp.auth"),
                    mailProperty("mail.smtp.starttls.enable"));
            throw new ApiException(ApiErrorCode.SERVICE_UNAVAILABLE, "mail unavailable");
        }
    }

    /** 仅记录异常类型，避免 SMTP 异常文本泄露邮箱、验证码或凭据。 */
    private String smtpError(MailException exception) {
        Throwable failure = exception;
        if (exception instanceof MailSendException sendException
                && !sendException.getFailedMessages().isEmpty()) {
            failure = sendException.getFailedMessages().values().iterator().next();
        }
        for (int depth = 0; depth < 8; depth++) {
            if (failure instanceof MessagingException messagingException
                    && messagingException.getNextException() != null) {
                failure = messagingException.getNextException();
            } else if (failure.getCause() != null) {
                failure = failure.getCause();
            } else {
                break;
            }
        }
        if (failure instanceof SMTPAddressFailedException addressFailure) {
            return failure.getClass().getSimpleName() + ":" + addressFailure.getReturnCode();
        }
        if (failure instanceof SMTPSendFailedException sendFailure) {
            return failure.getClass().getSimpleName() + ":" + sendFailure.getReturnCode();
        }
        return failure.getClass().getSimpleName();
    }

    private String mailProperty(String key) {
        if (sender instanceof JavaMailSenderImpl mailSender) {
            return mailSender.getJavaMailProperties().getProperty(key, "false");
        }
        return "unknown";
    }
}
