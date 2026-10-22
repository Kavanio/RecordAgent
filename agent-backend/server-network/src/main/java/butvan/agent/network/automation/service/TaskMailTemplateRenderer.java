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
    private static final String BALANCE = "#365f8b";
    /** 时间轴每小时映射 96px；密集事件至少 80px，长时间空档最多 360px。 */
    private static final int TIMELINE_PIXELS_PER_HOUR = 96;
    private static final int TIMELINE_MIN_ROW_HEIGHT = 80;
    private static final int TIMELINE_MAX_ROW_HEIGHT = 360;

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
        boolean periodReview = "WEEKLY_REPORT".equals(spec.kind()) || "MONTHLY_REPORT".equals(spec.kind());
        if (periodReview) {
            body.append(periodReview(spec, report));
        } else {
            if (expense != null) body.append(cashflowSection(expense, number++, dailyAssets));
            if ("DAILY_REPORT".equals(spec.kind()) && !report.timeline().isEmpty())
                body.append(timelineSection(report.timeline(), number++));
            if (todo != null || study != null) body.append(progressSection(todo, study, report.timeline(), number));
        }
        if (!isReport) body.append(paragraph(report.text(), INK));

        String currentDate = isReport ? currentPeriod(report.date()) : "任务提醒";
        String dateLabel = formattedDate(currentDate);
        String heading = !isReport ? spec.title() : switch (spec.kind()) {
            case "DAILY_REPORT" -> "A day, recorded.";
            case "WEEKLY_REPORT" -> "A week, reflected.";
            case "MONTHLY_REPORT" -> "A month, reimagined.";
            default -> "A day, recorded.";
        };
        String subtitle = !isReport ? "" : switch (spec.kind()) {
            case "DAILY_REPORT" -> "把今天，留在这里。";
            case "WEEKLY_REPORT" -> "看见进展，理解阻碍，让下周更有重点。";
            case "MONTHLY_REPORT" -> "回望投入与收获，为下个月做一次清醒的取舍。";
            default -> "把今天，留在这里。";
        };
        String reportKind = !isReport ? "TASK REMINDER" : "DAILY_REPORT".equals(spec.kind()) ? "PRIVATE DAILY REPORT" : "PRIVATE PERIOD REPORT";
        String generatedLine = report.generatedAt().isBlank() ? "" : "<div style='margin-top:8px;font-size:10px;color:" + MUTED + "'>生成于 " + escape(report.generatedAt()) + "</div>";
        String partial = report.partial()
                ? "<p style='margin:24px 0 0;padding:12px 14px;background:#fff4e5;color:#8c5b18;font-size:12px;line-height:1.8'>部分来源暂不可用，已在对应栏目标注。</p>"
                : "";

        return "<!doctype html><html lang='zh-CN'><head><meta charset='UTF-8'><meta name='viewport' content='width=device-width,initial-scale=1.0'>"
                + "<style>@media(max-width:500px){.outer-cell{padding:0!important}.paper-cell{padding:30px 22px!important}.paper{box-shadow:none!important}.account-table{font-size:10px!important}.hero-title{font-size:43px!important}}"
                + "@media(max-width:420px){.timeline-time{width:38px!important;padding-right:10px!important;font-size:10px!important}.timeline-content{padding-left:12px!important}.timeline-title,.timeline-amount{display:block!important;width:auto!important}.timeline-title{padding-right:0!important}.timeline-amount{padding-top:4px!important;text-align:left!important}}"
                + "@media print{body{background:#fff!important}.outer-cell{padding:0!important}.paper{max-width:none!important;box-shadow:none!important}.section-block{break-inside:avoid}}</style></head>"
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
                + "<div style='margin-top:52px;padding-top:25px;border-top:1px solid #222'><div style='font-family:Georgia,\"Songti SC\",serif;font-size:24px;letter-spacing:-.7px'>" + (periodReview ? "Make room for what matters." : "Until tomorrow.") + "</div>"
                + "<p style='font-size:11px;color:#888;line-height:2;max-width:440px;margin:14px 0 0'>" + (periodReview ? "复盘不是给自己打分，而是找到下一步更值得投入的事。邮件中的问题供你思考，不代表系统对原因的判断。" : "记录不只是关于数字，也关于那些认真度过的时刻。今天已经写下，明天继续。") + "</p></div>"
                + "<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:20px'><tr><td style='font-size:10px;color:" + MUTED + "'>butvan. / PERSONAL ARCHIVE</td>"
                + "<td align='right' style='font-size:10px;color:" + MUTED + "'>" + escape(footerDate(currentDate)) + "</td></tr></table>"
                + "</td></tr></table></td></tr></table></body></html>";
    }

    /** 周月报共享事实表，分别提供执行复盘与方向复盘；不凭汇总数值推断原因。 */
    private static String periodReview(TaskSpec spec, TaskReportBuilder.Report report) {
        boolean monthly = "MONTHLY_REPORT".equals(spec.kind());
        StringBuilder html = new StringBuilder();
        html.append("<div style='padding:20px;background:#f3f6f8;border-left:3px solid ").append(BALANCE).append("'>")
                .append(paragraph(monthly ? "月度复盘 · 留出 20 分钟，先看事实，再做取舍。" : "每周复盘 · 留出 10 分钟，先肯定进展，再安排下一步。", INK))
                .append(paragraph("统计范围：" + report.date(), MUTED))
                .append(paragraph("仅展示已启用栏目的已记录数据；未记录不等于没有发生。", MUTED)).append("</div>");
        int number = 1;
        for (String key : List.of("todo", "study", "expense")) {
            TaskReportBuilder.Section section = find(report, key);
            if (section == null) continue;
            String title = switch (key) {
                case "todo" -> "执行 · 从计划到成果";
                case "study" -> "成长 · 时间投向了哪里";
                default -> "支出 · 钱是否花在值得的地方";
            };
            html.append(sectionHeading(number++, title, "REVIEW"));
            if (!section.metrics().isEmpty()) {
                html.append("<table width='100%' cellspacing='0' cellpadding='0' style='margin-top:16px;font-size:11px;border-collapse:collapse;table-layout:fixed'><caption style='text-align:left;padding-bottom:10px;color:#777'>本期与上期对比</caption><thead><tr>");
                for (String label : List.of("指标", "本期", "上期", "变化"))
                    html.append("<th scope='col' style='text-align:left;padding:10px 4px;border-bottom:1px solid #ddd'>").append(label).append("</th>");
                html.append("</tr></thead><tbody>");
                for (var metric : section.metrics()) {
                    html.append("<tr>");
                    for (String cell : List.of(metric.label(), metric.current(), metric.previous(), metric.change()))
                        html.append("<td style='vertical-align:top;padding:12px 4px;border-bottom:1px solid #eee;overflow-wrap:anywhere;line-height:1.8'>").append(escape(cell)).append("</td>");
                    html.append("</tr>");
                }
                html.append("</tbody></table>");
            }
            html.append("todo".equals(key) ? todoDetails(section.detail()) : detail(section.detail()));
            String prompt = switch (key) {
                case "todo" -> monthly ? "哪些完成项真正推动了月度目标？哪些反复搁置的事项应当拆分、委托或放弃？" : "挑一件最有价值的完成项：什么方法值得重复？再挑一件未完成项：下一步能否缩小到 15 分钟内开始？";
                case "study" -> monthly ? "这些学习投入产出了什么作品、能力或解决方案？下月保留一个主攻方向，并安排一次可检验的输出。" : "本周学到的内容，有哪一条能立即用起来？为下周安排一个具体学习时段和一个小输出。";
                default -> monthly ? "哪些支出持续改善生活，哪些只是惯性消费？检查订阅和一次性大额支出，再决定下月的一个调整。" : "支出变化来自必要投入、一次性购买还是习惯变化？选一笔最值得和一笔可避免的支出，说明原因。";
            };
            html.append(paragraph("复盘问题 · " + prompt, BALANCE));
            if ("expense".equals(key)) html.append(paragraph("不同币种分别统计；支出增减不等于好坏，也不代表收入或结余变化。", MUTED));
            if (monthly) html.append(paragraph("自然月天数不同，总量变化不等于日均投入或效率变化。", MUTED));
        }
        html.append(sectionHeading(number, monthly ? "下月 · 做更少，但更重要的事" : "下周 · 把复盘变成行动", "NEXT STEP"));
        html.append(paragraph("以下是填写提示，不是系统生成的目标；请在资料或待办中记录你的答案。", MUTED));
        for (String question : monthly
                ? List.of("继续：一个已验证有效的方法，以及支持它的事实。", "停止：一项低价值投入，把释放的时间留给什么？", "开始：下月最重要的一个结果，写清验收标准与检查日期。", "第一步：未来 48 小时能完成的动作，以及预留的时间。")
                : List.of("保留：本周最有效的一个做法。", "调整：一个阻碍，以及下次遇到时的应对方式。", "聚焦：下周最多三件重要事项，分别写下第一步与完成标准。", "留白：给意外和休息预留空间，不把未完成事项全部照搬。")) {
            html.append("<div style='margin-top:14px;padding:14px 16px;border:1px solid #e5e9ed;border-radius:8px'>")
                    .append(paragraph(question, INK)).append("</div>");
        }
        return html.toString();
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
                amount.append("<div style='margin-top:8px;font-size:clamp(38px,8vw,54px);letter-spacing:-2px;font-weight:450;font-variant-numeric:tabular-nums;color:").append(BALANCE).append("'>")
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
            html.append("<tr style='font-size:11px'><td style='padding:12px 4px 12px 0;font-weight:560'>")
                    .append(escape(account.name())).append("<div style='margin-top:4px;font-size:9px;color:#aaa'>").append(escape(account.currency())).append("</div></td>")
                    .append("<td align='right' style='padding:12px 0;font-variant-numeric:tabular-nums'>").append(escape(account.balance())).append("</td>")
                    .append("<td align='right' style='padding:12px 0;font-variant-numeric:tabular-nums;font-weight:650;color:").append(zero(account.income()) ? MUTED : GREEN).append("'>")
                    .append(zero(account.income()) ? "—" : escape(account.income())).append("</td>")
                    .append("<td align='right' style='padding:12px 0;font-variant-numeric:tabular-nums;font-weight:650;color:").append(zero(account.expense()) ? MUTED : RED).append("'>")
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

    /** 单一浅色时间轴；学习时段放在标题下方，仅收支等非学习数值占用右侧金额列。 */
    private static String timelineSection(List<TaskReportBuilder.TimelineItem> items, int number) {
        StringBuilder html = sectionHeading(number, "一天的轨迹", "TIMELINE");
        html.append("<table class='timeline-table' role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:20px;table-layout:fixed;border-collapse:collapse'>");
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            String color = switch (item.type()) { case "expense" -> RED; case "income" -> "#407a65"; case "study" -> "#303430"; case "todo" -> GREEN; default -> "#8b8d88"; };
            String typeLabel = switch (item.type()) {
                case "expense" -> "支出";
                case "income" -> "收入";
                case "study" -> "学习";
                case "todo" -> "待办完成";
                default -> "其他变动";
            };
            boolean study = "study".equals(item.type());
            boolean hasValue = item.amount() != null && !item.amount().isBlank();
            boolean amountColumn = hasValue && !study;
            boolean last = index == items.size() - 1;
            int bottom = last ? 0 : 24;
            int rowHeight = last ? 0 : timelineRowHeight(item.time(), items.get(index + 1).time());
            // 单元格高度是下限，长正文仍可自然撑开；HTML 属性兼容不完整支持 CSS 的邮件客户端。
            html.append("<tr class='timeline-item'><td class='timeline-time' width='44'")
                    .append(last ? "" : " height='" + rowHeight + "'")
                    .append(" valign='top' align='right' style='")
                    .append(last ? "" : "height:" + rowHeight + "px;")
                    .append("width:44px;padding:0 14px ")
                    .append(bottom).append("px 0;border-right:1px solid #e2e3df;white-space:nowrap;font-size:11px;line-height:18px;color:#858782;font-weight:500;font-variant-numeric:tabular-nums'>")
                    .append(escape(item.time() == null || item.time().isBlank() ? "—" : item.time()))
                    .append("</td><td class='timeline-content' valign='top' style='padding:0 0 ").append(bottom).append("px 18px'>")
                    .append("<div style='margin-bottom:5px;font-size:10px;line-height:18px;color:").append(color).append("'>").append(typeLabel).append("</div>")
                    .append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='table-layout:fixed;border-collapse:collapse'><tr><td class='timeline-title' valign='top' style='padding:0 ")
                    .append(amountColumn ? "12" : "0").append("px 0 0;font-size:13px;font-weight:600;line-height:1.6;overflow-wrap:anywhere;word-wrap:break-word;word-break:break-word;color:")
                    .append(INK).append("'>").append(escape(item.title())).append("</td>");
            if (amountColumn) {
                html.append("<td class='timeline-amount' width='96' align='right' valign='top' style='width:96px;padding:0;font-size:13px;font-weight:600;line-height:1.6;font-variant-numeric:tabular-nums;overflow-wrap:anywhere;word-wrap:break-word;color:")
                        .append(color).append("'>").append(escape(item.amount())).append("</td>");
            }
            html.append("</tr></table>");
            if (study && hasValue) {
                html.append("<div class='timeline-meta' style='margin-top:5px;font-size:11px;line-height:1.6;color:#747772;overflow-wrap:anywhere'>")
                        .append(escape(item.amount())).append("</div>");
            }
            if (item.detail() != null && !item.detail().isBlank()) {
                html.append("<div class='timeline-detail' style='margin-top:4px;font-size:11px;line-height:1.6;color:#8d8f8b;overflow-wrap:anywhere'>")
                        .append(escape(item.detail())).append("</div>");
            }
            html.append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    /** 相邻事件的开始时刻决定最小行高；未知、同刻或倒序时间不推算跨日间隔。 */
    private static int timelineRowHeight(String current, String next) {
        int start = timelineMinute(current), end = timelineMinute(next);
        if (start < 0 || end <= start) return TIMELINE_MIN_ROW_HEIGHT;
        int height = ((end - start) * TIMELINE_PIXELS_PER_HOUR + 30) / 60;
        return Math.max(TIMELINE_MIN_ROW_HEIGHT, Math.min(TIMELINE_MAX_ROW_HEIGHT, height));
    }

    /** 仅解析报告约定的有效 HH:mm，避免用不完整时间伪造时间差。 */
    private static int timelineMinute(String value) {
        if (value == null || !value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) return -1;
        return Integer.parseInt(value.substring(0, 2)) * 60 + Integer.parseInt(value.substring(3, 5));
    }

    private static String progressSection(TaskReportBuilder.Section todo, TaskReportBuilder.Section study,
                                          List<TaskReportBuilder.TimelineItem> timeline, int number) {
        StringBuilder html = sectionHeading(number, "待办与学习", "PROGRESS");
        if (todo != null) {
            Map<String, String> values = new HashMap<>();
            todo.metrics().forEach(metric -> values.put(metric.label(), metric.current()));
            String totalText = values.getOrDefault("应办（项）", "0");
            String doneText = values.getOrDefault("已完成（项）", "0");
            String rateText = values.getOrDefault("完成率", "不适用");
            int total = integer(totalText), done = integer(doneText), remaining = Math.max(0, total - done);
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='margin-top:17px'><tr>")
                    .append(stat("完成率", rateText, INK))
                    .append(stat("已完成", done + " 项", INK)).append(stat("进行中", remaining + " 项", INK)).append("</tr></table>");
            if (total > 0) {
                int percent = Math.min(100, Math.max(0, done * 100 / total));
                html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='height:3px;margin-top:20px;background:#ececeb'><tr><td width='")
                        .append(percent).append("%' style='height:3px;background:").append(GREEN).append("'></td><td></td></tr></table>");
            }
            String details = todoDetails(todo.detail());
            if (!details.isBlank()) html.append(details);
        }
        if (study != null) {
            String duration = value(study, "学习时长（分钟）");
            String sessions = value(study, "学习记录（条）");
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='padding-top:21px;margin-top:15px'><tr><td style='font-size:12px'>已记录学习时长</td>")
                    .append("<td align='right' style='font-size:19px;font-variant-numeric:tabular-nums'>").append(escape(duration)).append(" <span style='font-size:11px;color:#aaa'>分钟</span></td></tr>")
                    .append("<tr><td style='padding-top:6px;font-size:10px;color:#999'>学习记录</td><td align='right' style='padding-top:6px;font-size:11px;color:#888'>").append(escape(sessions)).append(" 条</td></tr></table>");
            List<TaskReportBuilder.TimelineItem> studyItems = timeline.stream().filter(item -> "study".equals(item.type())).toList();
            if (!studyItems.isEmpty()) {
                html.append("<div style='margin-top:20px;font-size:10px;color:#929292'>学习记录明细</div>");
                for (var item : studyItems) {
                    html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='table-layout:fixed;margin-top:8px'><tr><td style='font-size:11px;font-weight:600;line-height:1.5;word-break:break-word'>")
                            .append(escape(item.title())).append("</td><td width='132' align='right' valign='top' style='padding-left:8px;white-space:nowrap;font-size:10px;color:#666'>")
                            .append(escape(item.amount())).append("</td></tr><tr><td colspan='2' style='padding-top:2px;font-size:10px;color:#999'>")
                            .append(escape(item.detail())).append("</td></tr></table>");
                }
            }
        }
        return html.toString();
    }

    private static String todoDetails(String content) {
        StringBuilder html = new StringBuilder();
        String group = "";
        for (String line : content.split("\\R")) {
            if (line.isBlank()) continue;
            if ("已完成".equals(line) || "尚未完成".equals(line)) {
                group = line;
                html.append("<div style='margin-top:24px;font-size:11px;color:#999'>").append(line).append("</div>");
                continue;
            }
            if (!line.startsWith("• ")) {
                html.append(paragraph(line, MUTED));
                continue;
            }
            String item = line.substring(2);
            int separator = item.indexOf(" · ");
            String date = separator >= 0 ? item.substring(0, separator) : "";
            String title = separator >= 0 ? item.substring(separator + 3) : item;
            String marker = "已完成".equals(group) ? "✓" : "○";
            String color = "已完成".equals(group) ? GREEN : RED;
            html.append("<table role='presentation' width='100%' cellspacing='0' cellpadding='0' style='font-size:12px'><tr><td width='26' valign='top' style='padding:8px 0;color:")
                    .append(color).append("'>").append(marker).append("</td><td style='padding:8px 0;line-height:1.6'>")
                    .append(escape(title));
            if (!date.isBlank()) html.append("<span style='margin-left:8px;font-size:10px;color:#999;white-space:nowrap'>").append(escape(date)).append("</span>");
            html.append("</td></tr></table>");
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
