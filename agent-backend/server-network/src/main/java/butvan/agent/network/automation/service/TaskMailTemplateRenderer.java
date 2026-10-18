package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.MailTemplate;
import butvan.agent.network.automation.model.TaskSpec;
import org.springframework.web.util.HtmlUtils;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 使用附件参考稿的纸张布局与内容层级渲染唯一邮件模板。 */
public final class TaskMailTemplateRenderer {
    private static final String INK = "#20211f";
    private static final String MUTED = "#999995";
    private static final String LINE = "#ededeb";
    private static final String GREEN = "#467460";
    private static final String RED = "#ac685c";

    private TaskMailTemplateRenderer() {}

    /** 按资产、收支、轨迹、待办与学习的顺序生成邮件安全 HTML。 */
    public static String render(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean isReport = spec.isReport();
        MailTemplate template = isReport ? spec.mailTemplate() : MailTemplate.defaults();
        template.validate();

        TaskReportBuilder.Section expense = find(report, "expense");
        TaskReportBuilder.Section todo = find(report, "todo");
        TaskReportBuilder.Section study = find(report, "study");
        boolean dailyAssets = "DAILY_REPORT".equals(spec.kind()) && expense != null;
        StringBuilder body = new StringBuilder();
        int number = 1;
        if (dailyAssets) {
            body.append(assetOverview(report, expense));
            body.append(accountSection(report.accounts(), expense, number++));
        }
        if (expense != null) body.append(cashflowSection(expense, number++, dailyAssets));
        if ("DAILY_REPORT".equals(spec.kind()) && !report.timeline().isEmpty())
            body.append(timelineSection(report.timeline(), number++));
        if (todo != null || study != null) body.append(progressSection(todo, study, number));
        if (!isReport) body.append(paragraph(report.text(), INK));

        String currentDate = isReport ? currentPeriod(report.date()) : "任务提醒";
        String dateLabel = formattedDate(currentDate);
        String heading = !isReport ? spec.title() : switch (spec.kind()) {
            case "DAILY_REPORT" -> "A day, recorded.";
            case "WEEKLY_REPORT" -> "A week, recorded.";
            case "MONTHLY_REPORT" -> "A month, recorded.";
            default -> "A day, recorded.";
        };
        String subtitle = !isReport ? "" : switch (spec.kind()) {
            case "DAILY_REPORT" -> "把今天，留在这里。";
            case "WEEKLY_REPORT" -> "把这一周，留在这里。";
            case "MONTHLY_REPORT" -> "把这个月，留在这里。";
            default -> "把今天，留在这里。";
        };
        String reportKind = !isReport ? "TASK REMINDER" : "DAILY_REPORT".equals(spec.kind()) ? "PRIVATE DAILY REPORT" : "PRIVATE PERIOD REPORT";
        String generatedLine = report.generatedAt().isBlank() ? "" : "<div style='margin-top:8px;font-size:10px;color:" + MUTED + "'>生成于 " + escape(report.generatedAt()) + "</div>";
        String partial = report.partial()
                ? "<p style='margin:24px 0 0;padding:12px 14px;background:#fff4e5;color:#8c5b18;font-size:12px;line-height:1.8'>部分来源暂不可用，已在对应栏目标注。</p>"
                : "";

        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'>"
                + "<style>@media(max-width:500px){.outer-cell{padding:0!important}.paper-cell{padding:30px 22px!important}.paper{box-shadow:none!important}.account-table{font-size:10px!important}.hero-title{font-size:43px!important}}@media print{body{background:#fff!important}.outer-cell{padding:0!important}.paper{max-width:none!important;box-shadow:none!important}.section-block{break-inside:avoid}}</style></head>"
                + "<body style='margin:0;background:#ebebe9;color:" + INK + ";font-family:-apple-system,BlinkMacSystemFont,\"Helvetica Neue\",\"PingFang SC\",\"Microsoft YaHei\",sans-serif'>"
                + "<table class='outer' role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td class='outer-cell' align='center' style='padding:22px 10px'>"
                + "<table class='paper' role='presentation' width='100%' cellspacing='0' cellpadding='0' style='max-width:680px;background:#ffffff;box-shadow:0 12px 42px #00000007'><tr><td class='paper-cell' style='padding:44px 48px 32px'>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='font-family:Georgia,\"Songti SC\",serif;font-size:24px;font-weight:700;letter-spacing:-1.4px'>butvan.</td>"
                + "<td align='right' style='font-size:9px;letter-spacing:1.4px;color:" + MUTED + "'>" + reportKind + "</td></tr></table>"
                + "<div style='margin-top:61px;font-size:10px;color:" + MUTED + ";letter-spacing:.6px'>" + escape(dateLabel) + "</div>"
                + "<h1 class='hero-title' style='margin:16px 0 0;font-family:Georgia,\"Songti SC\",serif;font-size:54px;font-size:clamp(43px,8.5vw,62px);font-weight:400;letter-spacing:-2.6px;line-height:1.17;color:" + INK + "'>" + escape(heading) + "</h1>"
                + (subtitle.isBlank() ? "" : "<div style='margin-top:19px;font-size:13px;color:#666'>" + escape(subtitle) + "</div>")
                + generatedLine
                + "<div style='margin-top:38px'>" + body + partial + "</div>"
                + "<div style='margin-top:52px;padding-top:25px;border-top:1px solid #222'><div style='font-family:Georgia,\"Songti SC\",serif;font-size:24px;letter-spacing:-.7px'>Until tomorrow.</div>"
                + "<p style='font-size:11px;color:#888;line-height:2;max-width:440px;margin:14px 0 0'>记录不只是关于数字，也关于那些认真度过的时刻。今天已经写下，明天继续。</p></div>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:20px'><tr><td style='font-size:10px;color:" + MUTED + "'>butvan. / PERSONAL ARCHIVE</td>"
                + "<td align='right' style='font-size:10px;color:" + MUTED + "'>" + escape(footerDate(currentDate)) + "</td></tr></table>"
                + "</td></tr></table></td></tr></table></body></html>";
    }

    private static String assetOverview(TaskReportBuilder.Report report, TaskReportBuilder.Section expense) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        report.accounts().forEach(account -> totals.merge(account.currency(), decimal(account.balance()), BigDecimal::add));
        String unavailable = expense.detail().contains("资产余额与账户流水暂不可用") ? "资产数据暂不可用" : "尚未设置资产账户";
        StringBuilder amount = new StringBuilder();
        if (totals.isEmpty()) {
            amount.append("<div style='font-size:24px;color:#999;margin-top:14px'>").append(unavailable).append("</div>");
        } else {
            for (var total : totals.entrySet()) {
                amount.append("<div style='margin-top:8px;font-size:clamp(38px,8vw,54px);letter-spacing:-2px;font-weight:450;font-variant-numeric:tabular-nums'>")
                        .append("<span style='font-size:23px;vertical-align:9%;margin-right:6px'>").append(currencySymbol(total.getKey())).append("</span>")
                        .append(format(total.getValue())).append("</div>");
            }
        }
        String currencies = totals.isEmpty() ? "" : String.join("、", totals.keySet());
        return "<div style='border-top:1px solid #222;margin-top:48px;padding-top:24px'><div style='font-size:10px;color:" + MUTED + ";letter-spacing:.4px'>TOTAL ASSETS / 资产余额</div>"
                + amount + "<div style='margin-top:14px;font-size:11px;color:#929292'>" + report.accounts().size() + " 个账户"
                + (currencies.isBlank() ? "" : "　" + escape(currencies)) + "　截至报告日</div></div>";
    }

    private static String accountSection(List<TaskReportBuilder.AccountSummary> accounts,
                                         TaskReportBuilder.Section expense, int number) {
        StringBuilder html = sectionHeading(number, "资产明细", "ACCOUNTS");
        if (accounts.isEmpty()) {
            String message = expense.detail().contains("资产余额与账户流水暂不可用") ? "资产与账户流水暂不可用。" : "尚未建立资产账户。";
            return html.append(paragraph(message, MUTED)).toString();
        }
        html.append("<table class='account-table' role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:8px;table-layout:fixed'>")
                .append("<tr style='color:#929292;font-size:10px'><td width='43%' style='padding:10px 0;border-bottom:1px solid #333'>账户</td><td align='right' width='19%' style='padding:10px 0;border-bottom:1px solid #333'>余额</td><td align='right' width='19%' style='padding:10px 0;border-bottom:1px solid #333'>收入</td><td align='right' width='19%' style='padding:10px 0;border-bottom:1px solid #333'>支出</td></tr>");
        for (TaskReportBuilder.AccountSummary account : accounts) {
            html.append("<tr style='font-size:11px'><td style='padding:12px 4px 12px 0;border-bottom:1px solid ").append(LINE).append(";font-weight:560'>")
                    .append(escape(account.name())).append("<div style='margin-top:4px;font-size:9px;color:#aaa'>").append(escape(account.currency())).append("</div></td>")
                    .append("<td align='right' style='padding:12px 0;border-bottom:1px solid ").append(LINE).append(";font-variant-numeric:tabular-nums'>").append(escape(account.balance())).append("</td>")
                    .append("<td align='right' style='padding:12px 0;border-bottom:1px solid ").append(LINE).append(";font-variant-numeric:tabular-nums;color:").append(zero(account.income()) ? MUTED : GREEN).append("'>")
                    .append(zero(account.income()) ? "—" : escape(account.income())).append("</td>")
                    .append("<td align='right' style='padding:12px 0;border-bottom:1px solid ").append(LINE).append(";font-variant-numeric:tabular-nums;color:").append(zero(account.expense()) ? MUTED : RED).append("'>")
                    .append(zero(account.expense()) ? "—" : escape(account.expense())).append("</td></tr>");
        }
        html.append("</table><p style='margin:14px 0 0;font-size:10px;color:#999;line-height:1.8'>— 表示该账户本期无收支。不同币种分别统计，不跨币种折算。</p>");
        return html.toString();
    }

    private static String cashflowSection(TaskReportBuilder.Section expense, int number, boolean dailyAssets) {
        String title = dailyAssets ? "收支概况" : expense.title();
        StringBuilder html = sectionHeading(number, title, "CASH FLOW");
        if (expense.metrics().isEmpty()) return html.append(detail(expense.detail())).toString();
        for (var metric : expense.metrics()) {
            html.append("<div style='margin-top:17px;font-size:9px;letter-spacing:.5px;color:").append(MUTED).append("'>")
                    .append(escape(metric.label())).append("</div>")
                    .append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:7px'><tr>")
                    .append(stat("本期支出", metric.current(), metric.current().equals("不可用") ? MUTED : RED))
                    .append(stat("上期支出", metric.previous(), INK))
                    .append(stat("支出变化", percentChange(metric.change()), metric.change().startsWith("+") ? RED : metric.change().startsWith("-") ? GREEN : INK))
                    .append("</tr></table>");
            if (!metric.change().equals("持平") && !metric.change().equals("无法比较")) {
                html.append("<div style='margin-top:15px;border-top:1px solid ").append(LINE).append(";padding-top:14px;display:block;font-size:11px;color:#888'>对比上期变化　<span style='color:")
                        .append(metric.change().startsWith("+") ? RED : GREEN).append("'>").append(escape(absoluteChange(metric))).append("</span></div>");
            }
        }
        if (!dailyAssets && expense.detail().contains("按币种分别统计"))
            html.append(paragraph("按币种分别统计，不跨币种折算。", MUTED));
        if (!dailyAssets && expense.detail().contains("支出：无记录"))
            html.append(paragraph("本期支出无记录。", MUTED));
        return html.toString();
    }

    private static String stat(String label, String value, String color) {
        return "<td valign='top' width='33.33%'><div style='font-size:10px;color:#929292'>" + label + "</div>"
                + "<div style='margin-top:10px;font-size:24px;letter-spacing:-1px;font-weight:520;font-variant-numeric:tabular-nums;color:" + color + "'>"
                + escape(value) + "</div></td>";
    }

    private static String timelineSection(List<TaskReportBuilder.TimelineItem> items, int number) {
        StringBuilder html = sectionHeading(number, "一天的轨迹", "TIMELINE");
        html.append("<div style='margin:0 0 16px;font-size:10px;color:#888'>")
                .append("<span style='color:#303430'>●</span> 学习　 <span style='color:").append(RED).append("'>●</span> 支出　 <span style='color:").append(GREEN).append("'>●</span> 收入</div>")
                .append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0'>");
        if (items.stream().anyMatch(item -> "other".equals(item.type()))) {
            int legendEnd = html.indexOf("</div>");
            html.insert(legendEnd, "　 <span style='color:#8b8d88'>●</span> 其他变动");
        }
        for (var item : items) {
            String color = switch (item.type()) { case "expense" -> RED; case "income" -> GREEN; case "study" -> "#303430"; default -> "#8b8d88"; };
            html.append("<tr><td width='50' valign='top' align='right' style='padding:3px 0 21px;font-size:10px;color:#898989;font-variant-numeric:tabular-nums'>")
                    .append(escape(item.time())).append("</td><td width='33' valign='top' align='center' style='padding:5px 0 0;border-left:1px solid #e2e3df'>")
                    .append("<span style='display:inline-block;width:8px;height:8px;border-radius:50%;background:").append(color).append(";border:1px solid ").append(color).append("'></span></td>")
                    .append("<td valign='top' style='padding:0 0 21px 2px'>");
            if ("study".equals(item.type())) {
                html.append("<div style='border-left:2px solid #444;padding:3px 0 4px 12px'>");
            }
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='font-size:12px;font-weight:570;line-height:1.5;color:")
                    .append(INK).append("'>").append(escape(item.title())).append("</td><td align='right' valign='top' style='padding-left:8px;white-space:nowrap;font-size:12px;font-weight:600;color:")
                    .append("study".equals(item.type()) ? "#555" : color).append("'>").append(escape(item.amount())).append("</td></tr></table>")
                    .append("<div style='margin-top:4px;font-size:10px;color:#9a9a98;line-height:1.75'>").append(escape(item.detail())).append("</div>");
            if ("study".equals(item.type())) html.append("</div>");
            html.append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    private static String progressSection(TaskReportBuilder.Section todo, TaskReportBuilder.Section study, int number) {
        StringBuilder html = sectionHeading(number, "待办与学习", "PROGRESS");
        if (todo != null) {
            Map<String, String> values = new HashMap<>();
            todo.metrics().forEach(metric -> values.put(metric.label(), metric.current()));
            String totalText = values.getOrDefault("应办（项）", "0");
            String doneText = values.getOrDefault("已完成（项）", "0");
            String rateText = values.getOrDefault("完成率", "不适用");
            int total = integer(totalText), done = integer(doneText), remaining = Math.max(0, total - done);
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:17px'><tr>")
                    .append(stat("完成率", rateText.equals("不适用") ? rateText : rateText + "%", INK))
                    .append(stat("已完成", done + " 项", INK)).append(stat("进行中", remaining + " 项", INK)).append("</tr></table>");
            if (total > 0) {
                int percent = Math.min(100, Math.max(0, done * 100 / total));
                html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='height:3px;margin-top:20px;background:#ececeb'><tr><td width='")
                        .append(percent).append("%' style='height:3px;background:").append(GREEN).append("'></td><td></td></tr></table>");
            }
            String pending = pendingDetails(todo.detail());
            if (!pending.isBlank()) html.append("<div style='margin-top:28px;font-size:11px;color:#999'>尚未完成</div>").append(pending);
        }
        if (study != null) {
            String duration = value(study, "学习时长（分钟）");
            String sessions = value(study, "学习记录（条）");
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='padding-top:21px;margin-top:15px'><tr><td style='font-size:12px'>已记录学习时长</td>")
                    .append("<td align='right' style='font-size:19px;font-variant-numeric:tabular-nums'>").append(escape(duration)).append(" <span style='font-size:11px;color:#aaa'>分钟</span></td></tr>")
                    .append("<tr><td style='padding-top:6px;font-size:10px;color:#999'>学习记录</td><td align='right' style='padding-top:6px;font-size:11px;color:#888'>").append(escape(sessions)).append(" 条</td></tr></table>");
        }
        return html.toString();
    }

    private static String pendingDetails(String content) {
        StringBuilder html = new StringBuilder();
        for (String line : content.split("\\R")) {
            if (line.isBlank() || "尚未完成".equals(line)) continue;
            if (!line.startsWith("• ")) {
                html.append(paragraph(line, MUTED));
                continue;
            }
            String item = line.substring(2);
            int separator = item.indexOf(" · ");
            String title = separator >= 0 ? item.substring(separator + 3) : item;
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='font-size:12px'><tr><td width='26' style='padding:12px 0;border-bottom:1px solid ")
                    .append(LINE).append(";color:").append(RED).append("'>○</td><td style='padding:12px 0;border-bottom:1px solid ").append(LINE).append("'>")
                    .append(escape(title)).append("</td></tr></table>");
        }
        return html.toString();
    }

    private static StringBuilder sectionHeading(int number, String title, String label) {
        return new StringBuilder("<div class='section-block' style='margin-top:45px'><table role='presentation' width='100%' cellspacing='0' cellpadding='0'><tr><td style='font-size:15px;font-weight:600;letter-spacing:-.3px;color:")
                .append(INK).append("'><span style='font-size:10px;color:#a6a6a6;margin-right:12px'>")
                .append(String.format(Locale.ROOT, "%02d /", number)).append("</span>").append(escape(title))
                .append("</td><td align='right' style='font-size:10px;color:#a6a6a6;letter-spacing:.6px'>").append(label).append("</td></tr></table></div>");
    }

    private static String detail(String content) {
        StringBuilder html = new StringBuilder("<div style='margin-top:12px'>");
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            html.append(paragraph(line, line.startsWith("•") ? INK : MUTED));
        }
        return html.append("</div>").toString();
    }

    private static String paragraph(String value, String color) {
        return "<p style='margin:7px 0;font-size:11px;line-height:1.8;color:" + color + "'>" + escape(value) + "</p>";
    }

    private static TaskReportBuilder.Section find(TaskReportBuilder.Report report, String key) {
        return report.sections().stream().filter(section -> key.equals(section.key())).findFirst().orElse(null);
    }

    private static String currentPeriod(String value) {
        if (value == null || value.isBlank()) return "";
        return value.split(" · 对比 ", 2)[0];
    }

    private static String formattedDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return value == null ? "" : value;
        return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("EEEE / MMMM d, yyyy", Locale.ENGLISH)).toUpperCase(Locale.ROOT);
    }

    private static String footerDate(String value) {
        if (value == null || !value.matches("\\d{4}-\\d{2}-\\d{2}")) return value == null ? "" : value;
        return LocalDate.parse(value).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)).toUpperCase(Locale.ROOT);
    }

    private static String value(TaskReportBuilder.Section section, String label) {
        return section.metrics().stream().filter(metric -> label.equals(metric.label())).map(TaskReportBuilder.Metric::current).findFirst().orElse("0");
    }

    private static String currencySymbol(String currency) { return "CNY".equalsIgnoreCase(currency) ? "¥" : currency + " "; }
    private static BigDecimal decimal(String value) { return value != null && value.matches("[-+]?\\d+(?:\\.\\d+)?") ? new BigDecimal(value) : BigDecimal.ZERO; }
    private static String format(BigDecimal value) { return value.setScale(2, RoundingMode.HALF_UP).toPlainString(); }
    private static boolean zero(String value) { return decimal(value).signum() == 0; }
    private static int integer(String value) { return decimal(value).intValue(); }
    private static String absoluteChange(TaskReportBuilder.Metric metric) {
        String change = metric.change();
        int percent = change.indexOf('（');
        String delta = percent >= 0 ? change.substring(0, percent) : change;
        return (delta.startsWith("+") ? "+ " : delta.startsWith("-") ? "− " : "")
                + currencySymbol(metric.label()) + delta.replaceFirst("^[+-]", "");
    }
    private static String percentChange(String value) {
        int start = value.indexOf('（'), end = value.indexOf('）');
        return start >= 0 && end > start ? value.substring(start + 1, end) : value;
    }
    private static String escape(String text) { return HtmlUtils.htmlEscape(text == null ? "" : text); }
}
