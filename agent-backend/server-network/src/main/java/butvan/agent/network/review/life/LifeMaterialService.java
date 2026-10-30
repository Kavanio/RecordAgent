package butvan.agent.network.review.life;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.session.*;
import butvan.agent.agents.session.dto.*;
import butvan.agent.agents.review.ReviewGenerationRequest.Evidence;
import butvan.agent.network.record.service.RecordService;
import butvan.agent.network.review.service.ReviewFactCollector;
import butvan.agent.network.review.life.LifeReviewModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

/** 授权之后按领域 Service 只读获取有限材料；不从记忆抽取结果重建生活。 */
@Service
@RequiredArgsConstructor
public class LifeMaterialService {
    private final RecordService records;
    private final SessionCatalogService sessions;
    private final TranscriptService transcripts;
    private final CurrentUserProvider user;
    private final ReviewFactCollector facts;
    public record Collected(List<Material> materials, List<Evidence> facts, String coverage) {}

    /** 发布前复核文字来源仍可引用；读取期间删除、归档或变更则丢弃迟到结果。 */
    public void verify(Settings settings, List<Material> materials) {
        Map<String, List<TranscriptMessageDto>> cached = new HashMap<>();
        for (var m : materials) {
            if (m.kind().equals("JOURNAL")) {
                var current = records.getReference(settings.owner(), m.sourceId());
                if (!current.recordDate().toString().equals(m.occurredAt()) || !current.contentText().startsWith(m.text()))
                    throw new IllegalArgumentException("复盘手记来源已变化");
            } else if (m.kind().equals("CHAT")) {
                String[] source = m.sourceId().split(":", 2);
                if (source.length != 2 || !settings.owner().equals(user.currentUserId())
                        || sessions.listActive().stream().noneMatch(s -> s.id().equals(source[0]) && s.kind() == SessionKind.GENERAL))
                    throw new IllegalArgumentException("复盘聊天范围已变化");
                boolean valid = cached.computeIfAbsent(source[0], id -> transcripts.recent(id, 24)).stream()
                        .anyMatch(message -> message.id().equals(source[1]) && message.role() == TranscriptMessageDto.MessageRole.USER
                            && message.content().startsWith(m.text()));
                if (!valid) throw new IllegalArgumentException("复盘聊天来源不可核对");
            }
        }
    }

    /** 返回有界原文与程序数值，逐来源保留覆盖和失败说明。 */
    public Collected collect(Settings settings, LocalDate day, Instant cutoff) {
        List<Material> text = new ArrayList<>();
        List<Evidence> metrics = new ArrayList<>();
        List<String> coverage = new ArrayList<>();
        ZoneId zone = ZoneId.of(settings.timezone());
        LocalDate from = day.minusDays(settings.historyDays() - 1L);
        if (settings.sources().contains("JOURNAL")) {
            try {
                for (var item : records.journalFragments(settings.owner(), from, day)) {
                    if (item.updatedAt().isAfter(cutoff)) continue;
                    text.add(new Material("journal-" + item.id(), "JOURNAL", item.id(), item.recordDate().toString(),
                            item.text(), item.truncated()));
                }
                coverage.add("手记：归属日范围内最多八条，每条六百字符；日期为用户指定归属日");
            } catch (RuntimeException e) { coverage.add("手记读取不可用，不能当作无记录"); }
        }
        if (settings.sources().contains("CHAT")) {
            // 当前产品单用户；后台 owner 与目录册 owner 不一致时失败保护，不能跨用户读取。
            if (!settings.owner().equals(user.currentUserId())) throw new IllegalArgumentException("聊天用户范围不匹配");
            try {
                for (var session : sessions.listActive().stream().filter(s -> s.kind() == SessionKind.GENERAL)
                        .sorted(Comparator.comparing(SessionSummaryDto::updatedAt).reversed()).limit(5).toList()) {
                    for (var message : transcripts.recent(session.id(), 24)) {
                        if (message.role() != TranscriptMessageDto.MessageRole.USER || message.createdAt() == null
                                || message.createdAt().isAfter(cutoff) || message.createdAt().atZone(zone).toLocalDate().isBefore(from)
                                || message.createdAt().atZone(zone).toLocalDate().isAfter(day) || text.size() >= 20) continue;
                        String raw = message.content();
                        text.add(new Material("chat-" + message.id(), "CHAT", session.id() + ":" + message.id(),
                                message.createdAt().atZone(zone).toString(), raw.substring(0, Math.min(raw.length(), 600)), raw.length() > 600));
                    }
                }
                coverage.add("聊天：最近五个普通会话，各扫描末尾2MiB及角色筛选前24条；仅用户原文，每条六百字符，合计最多二十条；仅部分近期记录");
            } catch (RuntimeException e) { coverage.add("聊天读取不可用，不能当作无互动"); }
        }
        Set<String> domains = new HashSet<>();
        if (settings.sources().contains("BUSINESS")) domains.addAll(Set.of("TODO", "STUDY"));
        if (settings.sources().contains("FINANCE")) domains.add("FINANCE");
        if (!domains.isEmpty()) {
            var collected = facts.collect(settings.owner(), day, day, zone, domains);
            int index = 0;
            for (var f : collected.facts().stream().limit(24).toList()) {
                metrics.add(new Evidence("fact-" + index++, f.domain(), f.metricKey(), Long.toString(f.value()),
                        f.unit(), f.currency(), day.toString(), "截至采集时已记录数值，不代表完整自然日", cutoff.toString()));
            }
            collected.sources().forEach(s -> coverage.add(s.domain() + ":" + s.state()));
        }
        coverage.add("窗口=" + from + "至" + day + "；时区=" + zone + "；截止=" + cutoff + "；无记录不代表没有发生；过去的表达不代表当前观点");
        return new Collected(List.copyOf(text), List.copyOf(metrics), String.join("；", coverage));
    }
}
