package butvan.agent.network.automation.service;

import butvan.agent.network.automation.model.TaskSpec;
import butvan.agent.network.daily.service.DailyEventService;
import butvan.agent.network.daily.service.ExpenseAnalyticsService;
import butvan.agent.network.daily.model.DailyEventModels.TodoDetails;
import butvan.agent.network.study.service.StudyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;

/** 通过领域公开 Service 生成固定模板日报，每个栏目独立降级，不调用模型。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskReportBuilder {
    private final DailyEventService daily;
    private final ExpenseAnalyticsService expenses;
    private final StudyService studies;
    /** 栏目结构供邮件布局使用，纯文本仍供历史及系统通知展示。 */
    public record Section(String key, String title, String text) {}
    public record Report(String text, boolean partial, List<Section> sections, String date, String generatedAt) {
        public Report(String text, boolean partial) { this(text, partial, List.of(), "", ""); }
    }

    /** 读取当日已记录数据，保留真实生成时刻和统计范围。 */
    public Report build(String owner, TaskSpec s, Instant planned, Instant now) {
        if (!"DAILY_REPORT".equals(s.kind())) return new Report(s.content(), false);
        ZoneId zone = ZoneId.of(s.timezone());
        LocalDate date = planned.atZone(zone).toLocalDate();
        String generatedAt = now.atZone(zone).format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + "（" + s.timezone() + "）";
        List<Section> sections = new ArrayList<>();
        StringBuilder text = new StringBuilder("每日日报 · " + date + "\n截至 " + generatedAt
                + " 的当日已记录数据（非全天最终结算）\n");
        int ok = 0, failed = 0;
        if (s.expense()) {
            try {
                // 既有跨域投影已合并旧花销与财务支出，按币种分别统计。
                var entries = expenses.reportExpenses(owner, date);
                Map<String, BigDecimal> totals = new TreeMap<>();
                for (var e : entries) {
                    if (e.time() == null || !date.atTime(e.time()).atZone(zone).toInstant().isAfter(now))
                        totals.merge(e.currency(), e.amount(), BigDecimal::add);
                }
                String summary = totals.isEmpty() ? "无记录" : totals.entrySet().stream()
                        .map(e -> e.getKey() + " " + e.getValue().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString())
                        .collect(java.util.stream.Collectors.joining("\n"));
                sections.add(new Section("expense", "今日支出", summary));
                text.append("\n支出：").append(summary).append("\n");
                ok++;
            } catch (RuntimeException e) { log.warn("任务日报支出栏目读取失败：{}", e.getClass().getSimpleName()); sections.add(new Section("expense", "今日支出", "数据暂不可用")); text.append("\n支出：数据暂不可用\n"); failed++; }
        }
        if (s.todo()) {
            try {
                var todos = daily.getDay(owner, date).events().stream().filter(e -> e.details() instanceof TodoDetails).toList();
                long done = todos.stream().filter(e -> ((TodoDetails) e.details()).completed()).count();
                StringBuilder todoText = new StringBuilder();
                todoText.append("当日应办：").append(todos.size()).append(" 项，已完成 ").append(done).append(" 项\n");
                todos.stream().filter(e -> !((TodoDetails)e.details()).completed()).limit(100)
                        .forEach(e -> todoText.append("• ").append(e.title()).append("\n"));
                if (todos.size() - done > 100) todoText.append("列表仅显示前 100 项，请在日历查看全部。\n");
                sections.add(new Section("todo", "待办进度", todoText.toString()));
                text.append("\n").append(todoText);
                ok++;
            } catch (RuntimeException e) { log.warn("任务日报待办栏目读取失败：{}", e.getClass().getSimpleName()); sections.add(new Section("todo", "待办进度", "数据暂不可用")); text.append("\n待办：数据暂不可用\n"); failed++; }
        }
        if (s.study()) {
            try {
                var stat = studies.getStatistics(owner, date, date, zone);
                String summary = stat.sessionCount() + " 条记录，共 " + stat.totalDurationSeconds() / 60 + " 分钟";
                sections.add(new Section("study", "学习时光", summary));
                text.append("\n学习：").append(summary).append("\n");
                ok++;
            } catch (RuntimeException e) { log.warn("任务日报学习栏目读取失败：{}", e.getClass().getSimpleName()); sections.add(new Section("study", "学习时光", "数据暂不可用")); text.append("\n学习：数据暂不可用\n"); failed++; }
        }
        if (ok == 0) throw new IllegalStateException("日报所有栏目读取失败，请稍后重试");
        return new Report(text.toString(), failed > 0, List.copyOf(sections), date.toString(), generatedAt);
    }
}
