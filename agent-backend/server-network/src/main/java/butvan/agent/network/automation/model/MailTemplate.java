package butvan.agent.network.automation.model;

/** 报告邮件模板。当前仅提供蓝调摘要，旧配置字段保留用于读取既有数据库记录。 */
public record MailTemplate(String style, String accent, String heading, String introduction, String footer, String body) {
    public MailTemplate {
        style = switch (style == null ? "" : style) {
            case "", "CARDS", "DIGEST", "MINIMAL", "EDITORIAL", "LETTER", "LEDGER", "RECEIPT", "CUSTOM" -> "DIGEST";
            default -> style;
        };
        // 旧版可编辑字段不再影响输出，并在下次保存时归一化为默认值。
        accent = "#2458a6";
        heading = "";
        introduction = "";
        footer = "";
        body = "";
    }
    public static MailTemplate defaults() { return new MailTemplate(null, null, null, null, null, null); }
    /** 只接受当前可用的样式标识；未知值不静默降级。 */
    public void validate() {
        if (!"DIGEST".equals(style)) throw new IllegalArgumentException("邮件模板不支持");
    }
}
