package butvan.agent.network.automation.model;

import java.util.Set;
import java.util.regex.Pattern;

/** 受控邮件模板；自定义内容为纯文本及占位符，不执行 HTML 或脚本。 */
public record MailTemplate(String style, String accent, String heading, String introduction, String footer, String body) {
    public MailTemplate {
        style = switch (style == null ? "" : style) {
            case "", "CARDS" -> "DIGEST";
            case "MINIMAL" -> "EDITORIAL";
            case "LETTER" -> "LEDGER";
            default -> style;
        };
        accent = accent == null ? "#39756b" : accent;
        heading = heading == null ? "生活简报" : heading;
        introduction = introduction == null ? "记录进展，看见变化。" : introduction;
        footer = footer == null ? "来自 ButvanAgent · 为每段日常留下一份记录" : footer;
        body = body == null ? "{{expense}}\n\n{{todo}}\n\n{{study}}" : body;
    }
    public static MailTemplate defaults() { return new MailTemplate(null, null, null, null, null, null); }
    /** 限制布局、颜色、长度和占位符，保证安全且可预期的渲染。 */
    public void validate() {
        if (!Set.of("DIGEST", "EDITORIAL", "LEDGER", "CUSTOM").contains(style)) throw new IllegalArgumentException("邮件模板不支持");
        if (!accent.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("主题色须为六位十六进制颜色");
        if (heading.length() > 100 || introduction.length() > 500 || footer.length() > 200 || body.length() > 8000)
            throw new IllegalArgumentException("邮件模板内容过长");
        if ("CUSTOM".equals(style) && body.isBlank()) throw new IllegalArgumentException("自定义邮件正文不能为空");
        var matcher = Pattern.compile("\\{\\{([^{}]+)}}").matcher(body);
        while (matcher.find()) if (!Set.of("title", "date", "generatedAt", "expense", "todo", "study", "content").contains(matcher.group(1)))
            throw new IllegalArgumentException("未知模板占位符：" + matcher.group());
    }
}
