package butvan.agent.network.automation.service;

import butvan.agent.agents.config.LocalConfigService;
import butvan.agent.network.automation.dto.TaskMailDtos.*;
import butvan.agent.network.service.account.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.io.IOException;
import java.util.Set;

/** 复用本机已绑定邮箱与 mail 配置，发信异常转换成不含敏感信息的状态。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskMailService {
    private final LocalConfigService config;
    private final ObjectMapper mapper;
    public record Outcome(String status, String message) {}

    private SmtpMailConfigData mail() {
        Object value = config.loadFullConfigData().getExtraFields().get("mail");
        return value == null ? SmtpMailConfigData.unset() : mapper.convertValue(value, SmtpMailConfigData.class);
    }
    private AccountConfigData account() {
        Object value = config.loadFullConfigData().getExtraFields().get("account");
        return value == null ? new AccountConfigData() : mapper.convertValue(value, AccountConfigData.class);
    }
    /** 当前授权收件地址，只供服务端内部使用。 */
    public String recipient() {
        var a = account();
        return a.isEmailVerified() && a.isEmailNotificationsEnabled() ? a.getEmail() : null;
    }
    /** 返回安全设置快照。 */
    public Settings settings() {
        var m = mail(); var a = account();
        String email = a.isEmailVerified() ? a.getEmail() : null;
        String masked = email == null ? null : email.replaceFirst("(^.).*(@.*$)", "$1***$2");
        return new Settings(m.host(), m.port(), m.username(), m.from(), m.auth(), m.starttls(),
                m.password() != null && !m.password().isBlank(), a.isEmailNotificationsEnabled(), masked,
                m.isReady() && m.starttls() && (!m.auth() || (m.password() != null && !m.password().isBlank())) && recipient() != null);
    }
    /** 保存邮件设置，空密码保持原值；不自动向外发信。 */
    public synchronized Settings save(Save input) {
        if (input.host() == null || !input.host().matches("[a-zA-Z0-9.-]{1,253}") || input.port() < 1 || input.port() > 65535)
            throw new IllegalArgumentException("SMTP 主机或端口不合法");
        if (input.from() == null || !input.from().matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new IllegalArgumentException("发件邮箱不合法");
        if (!input.starttls()) throw new IllegalArgumentException("任务邮件要求启用 STARTTLS 加密");
        var previous = mail();
        String password = input.password() == null || input.password().isBlank() ? previous.password() : input.password();
        var value = config.loadFullConfigData();
        value.setExtraField("mail", new SmtpMailConfigData(input.host(), input.port(), input.username(), password, input.from(), input.auth(), input.starttls()));
        var account = account(); account.setEmailNotificationsEnabled(input.enabled()); value.setExtraField("account", account);
        config.saveFullConfigData(value);
        var saved = mail();
        if (!saved.equals(new SmtpMailConfigData(input.host(), input.port(), input.username(), password, input.from(), input.auth(), input.starttls()))
                || account().isEmailNotificationsEnabled() != input.enabled())
            throw new IllegalStateException("邮件设置未能保存，请检查本地配置文件权限");
        try {
            Files.setPosixFilePermissions(config.getConfigPath(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException exception) {
            log.info("当前文件系统不支持 POSIX 权限，继续使用系统账户权限保护配置文件");
        } catch (IOException e) {
            throw new IllegalStateException("邮件设置已保存，但无法限制本地配置文件权限", e);
        }
        return settings();
    }
    /** 发送已保存结果，发送前再次检查收件地址与全局开关；不抛出底层 SMTP 消息。 */
    public Outcome send(String target, String id, String title, String text, String html) {
        if (target == null || !target.equals(recipient())) return new Outcome("SKIPPED", "收件地址或邮件通知授权已变更");
        var m = mail();
        if (!m.isReady() || !m.starttls() || (m.auth() && (m.password() == null || m.password().isBlank()))) return new Outcome("FAILED", "请完成启用 STARTTLS 的 SMTP 配置");
        try {
            var sender = new JavaMailSenderImpl();
            sender.setHost(m.host()); sender.setPort(m.port()); sender.setUsername(m.username()); sender.setPassword(m.password());
            var props = sender.getJavaMailProperties();
            props.setProperty("mail.smtp.auth", String.valueOf(m.auth()));
            props.setProperty("mail.smtp.starttls.enable", "true"); props.setProperty("mail.smtp.starttls.required", "true");
            props.setProperty("mail.smtp.connectiontimeout", "5000"); props.setProperty("mail.smtp.timeout", "10000"); props.setProperty("mail.smtp.writetimeout", "10000");
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(m.from()); helper.setTo(target); helper.setSubject(title);
            helper.setText(text, html != null && !html.isBlank() ? html : "<html><body><h2>" + HtmlUtils.htmlEscape(title) + "</h2><pre style='white-space:pre-wrap'>" + HtmlUtils.htmlEscape(text) + "</pre></body></html>");
            message.setHeader("X-Butvan-Run", id);
            sender.send(message);
            return new Outcome("SUBMITTED", "");
        } catch (MailAuthenticationException e) {
            log.warn("任务邮件认证失败：runId={}", id);
            return new Outcome("FAILED", "SMTP 认证失败，请检查配置");
        } catch (Exception e) {
            log.warn("任务邮件投递结果未知：runId={}, type={}", id, e.getClass().getSimpleName());
            return new Outcome("UNKNOWN", "邮件提交结果未知，请检查收件箱后决定是否重发");
        }
    }
    /** 仅在用户主动点击时向绑定邮箱发送无业务数据的测试内容。 */
    public Outcome test() { return send(recipient(), java.util.UUID.randomUUID().toString(), "ButvanAgent 测试邮件", "邮件通知配置测试。此邮件不包含业务数据。", ""); }
}
