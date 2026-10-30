package butvan.agent.network.review.life;

import butvan.agent.network.review.life.LifeReviewModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** 生活复盘 SQLite Adapter；所有可筛选状态与版本均使用正式列。 */
@Repository
@RequiredArgsConstructor
public class LifeReviewRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 读取当前用户设置；未保存时返回关闭的默认值。 */
    public Settings settings(String owner) {
        return jdbc.query("SELECT settings_json FROM life_review_settings WHERE owner_id=?",
                (r, n) -> read(r.getString(1), Settings.class), owner).stream().findFirst().orElse(Settings.defaults(owner));
    }
    /** 首次插入与后续 CAS 更新使用相同 revision 约束。 */
    public void settings(Settings next, int expected) {
        int rows = expected == 0 ? jdbc.update("INSERT INTO life_review_settings(owner_id,revision,enabled,grant_id,settings_json) VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
                next.owner(), next.revision(), next.enabled(), next.grantId(), json(next)) : 0;
        if (expected != 0) rows = jdbc.update("UPDATE life_review_settings SET revision=?,enabled=?,grant_id=?,settings_json=? WHERE owner_id=? AND revision=?",
                next.revision(), next.enabled(), next.grantId(), json(next), next.owner(), expected);
        if (rows != 1) throw new IllegalArgumentException("生活复盘设置已变化，请刷新");
    }
    /** 历史授权范围独立审计，不含原文或凭据。 */
    public void grant(Settings s) {
        revokeGrants(s.owner(), s.authorizedAt());
        jdbc.update("INSERT INTO life_review_grant VALUES(?,?,?,?,NULL)", s.grantId(), s.owner(), json(s), s.authorizedAt().toString());
    }
    /** 使本用户尚未撤销的历史用途审计失效，不改写原范围。 */
    public void revokeGrants(String owner, Instant now) {
        jdbc.update("UPDATE life_review_grant SET revoked_at=? WHERE owner_id=? AND revoked_at IS NULL", now.toString(), owner);
    }
    /** 按用户和授权ID读取原接收方与来源范围，仅供版本追溯。 */
    public Optional<Settings> grantSettings(String owner, String grantId) {
        return jdbc.query("SELECT settings_json FROM life_review_grant WHERE owner_id=? AND id=?",
                (r, n) -> read(r.getString(1), Settings.class), owner, grantId).stream().findFirst();
    }
    /** 后台最多读取二十个已启用且有授权的用户，不推断运行许可。 */
    public List<Settings> enabled() {
        return jdbc.query("SELECT settings_json FROM life_review_settings WHERE enabled=1 AND grant_id<>'' LIMIT 20",
                (r, n) -> read(r.getString(1), Settings.class));
    }
    /** 按用户和内容ID读取一个版本，隔离其他用户。 */
    public Optional<Content> content(String owner, String id) {
        return jdbc.query("SELECT content_json FROM life_review_content WHERE owner_id=? AND id=?",
                (r, n) -> read(r.getString(1), Content.class), owner, id).stream().findFirst();
    }
    /** 读取某个归属日的最新版本，不创建工作项。 */
    public Optional<Content> latest(String owner, LocalDate day) {
        return jdbc.query("SELECT content_json FROM life_review_content WHERE owner_id=? AND review_day=? ORDER BY version DESC LIMIT 1",
                (r, n) -> read(r.getString(1), Content.class), owner, day.toString()).stream().findFirst();
    }
    /** 返回最近三十个有限版本，历史读取不调用模型。 */
    public List<Content> history(String owner) {
        return jdbc.query("SELECT content_json FROM life_review_content WHERE owner_id=? ORDER BY created_at DESC,version DESC LIMIT 30",
                (r, n) -> read(r.getString(1), Content.class), owner);
    }
    /** 只插入新版本，唯一约束阻止同日并发覆盖。 */
    public void insert(Content value) {
        jdbc.update("INSERT INTO life_review_content VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", value.id(), value.owner(),
                value.day().toString(), value.version(), value.grantId(), value.settingsRevision(), value.state(),
                value.sourceAsOf().toString(), value.availableAt().toString(), value.createdAt().toString(),
                value.generation(), json(value));
    }
    /** 只推进尚未终结版本，不改写已经发布或冻结的结果。 */
    public boolean finish(Content value, String expected) {
        return jdbc.update("UPDATE life_review_content SET state=?,content_json=? WHERE id=? AND owner_id=? AND state=?",
                value.state(), json(value), value.id(), value.owner(), expected) == 1;
    }
    /** 每次只领取候选列表中的前五个工作项。 */
    public List<Content> pending() {
        return jdbc.query("SELECT content_json FROM life_review_content WHERE state='PENDING' ORDER BY created_at LIMIT 5",
                (r, n) -> read(r.getString(1), Content.class));
    }
    /** 重启时未知模型调用不重放；调用前领取状态已持久化。 */
    public void recover() {
        var running = jdbc.query("SELECT content_json FROM life_review_content WHERE state='RUNNING'",
                (r, n) -> read(r.getString(1), Content.class));
        running.forEach(c -> finish(c.finish("UNKNOWN", null, "生成中断，未自动重试"), "RUNNING"));
        jdbc.update("UPDATE life_review_delivery SET state='UNKNOWN' WHERE state='SENDING'");
    }
    public boolean reserve(String owner, LocalDate day, int limit) {
        return jdbc.update("""
                INSERT INTO life_review_budget VALUES(?,?,1)
                ON CONFLICT(owner_id,usage_day) DO UPDATE SET calls=calls+1
                WHERE calls<? OR (
                    calls < 10 AND (
                        SELECT COUNT(*) FROM life_review_content
                        WHERE owner_id=? AND review_day=? AND state='READY'
                    ) < ?
                )
                """, owner, day.toString(), limit, owner, day.toString(), limit) == 1;
    }
    /** 按用户和内容身份读取各渠道独立状态。 */
    public List<Delivery> deliveries(String owner, String contentId) {
        return jdbc.query("SELECT * FROM life_review_delivery WHERE owner_id=? AND content_id=?", (r, n) ->
                new Delivery(r.getString("id"), owner, contentId, r.getString("channel"), r.getString("state"),
                        r.getString("target_fingerprint"), r.getString("message_id"), r.getInt("attempts"),
                        Instant.parse(r.getString("updated_at"))), owner, contentId);
    }
    /** 在外部副作用之前建立唯一渠道意图。 */
    public void delivery(Delivery d) {
        jdbc.update("INSERT INTO life_review_delivery VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT(content_id,channel) DO NOTHING",
                d.id(), d.owner(), d.contentId(), d.channel(), d.state(), d.targetFingerprint(), d.messageId(), d.attempts(), d.updatedAt().toString());
    }
    /** 按旧状态CAS推进投递，成功或未知状态不能被重复请求覆盖。 */
    public boolean deliveryState(String id, String expected, String state, String messageId, Instant now) {
        return jdbc.update("UPDATE life_review_delivery SET state=?,message_id=?,attempts=attempts+CASE WHEN ?='SENDING' THEN 1 ELSE 0 END,updated_at=? WHERE id=? AND state=?",
                state, messageId, state, now.toString(), id, expected) == 1;
    }
    /** 同主题同新颖性去重；尚未呈现时可改用更新版本。 */
    public void candidate(Candidate c) {
        jdbc.update("""
                INSERT INTO life_outreach_candidate(id,owner_id,topic,novelty,source_id,content_id,state,due_at,expires_at,created_at,sent_at,feedback,event_kind) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(owner_id,topic,novelty) DO UPDATE SET content_id=excluded.content_id,
                source_id=excluded.source_id,due_at=excluded.due_at,expires_at=excluded.expires_at
                WHERE life_outreach_candidate.state='PENDING'
                  AND (SELECT version FROM life_review_content WHERE id=excluded.content_id)
                    >= (SELECT version FROM life_review_content WHERE id=life_outreach_candidate.content_id)
                """,
                c.id(), c.owner(), c.topic(), c.novelty(), c.sourceId(), c.contentId(), c.state(),
                c.dueAt().toString(), c.expiresAt().toString(), c.createdAt().toString(),
                c.sentAt() == null ? null : c.sentAt().toString(), c.feedback(), c.eventKind());
    }
    /** 一次性终结本用户所有已到期候选，不受展示分页限制。 */
    public void expire(String owner, Instant now) {
        jdbc.update("UPDATE life_outreach_candidate SET state='EXPIRED' WHERE owner_id=? AND state='PENDING' AND julianday(expires_at)<=julianday(?)",
                owner, now.toString());
    }
    /** 活动积压按完整账本计数，不用展示分页代替容量判断。 */
    public int pendingCount(String owner) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM life_outreach_candidate WHERE owner_id=? AND state='PENDING'", Integer.class, owner);
    }
    /** 幂等身份查全账本，即使记录不在最近一百条展示范围仍可去重。 */
    public boolean candidateExists(String owner, String topic, String novelty) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM life_outreach_candidate WHERE owner_id=? AND topic=? AND novelty=?",
                Integer.class, owner, topic, novelty) > 0;
    }
    /** 全域最近呈现时间不受候选展示分页影响。 */
    public Optional<Instant> lastPresentation(String owner) {
        return Optional.ofNullable(jdbc.queryForObject("SELECT MAX(sent_at) FROM life_outreach_candidate WHERE owner_id=?",
                String.class, owner)).map(Instant::parse);
    }
    /** 读取最近一百条候选及原始反馈，未回复保持空反馈。 */
    public List<Candidate> candidates(String owner) {
        return jdbc.query("SELECT * FROM life_outreach_candidate WHERE owner_id=? ORDER BY created_at DESC LIMIT 100", (r, n) ->
                new Candidate(r.getString("id"), owner, r.getString("topic"), r.getString("novelty"), r.getString("source_id"),
                        r.getString("content_id"), r.getString("state"), Instant.parse(r.getString("due_at")),
                        Instant.parse(r.getString("expires_at")), Instant.parse(r.getString("created_at")),
                        r.getString("sent_at") == null ? null : Instant.parse(r.getString("sent_at")), r.getString("feedback"), r.getString("event_kind")), owner);
    }
    public boolean candidateState(String owner, String id, String expected, String state, Instant sentAt, String feedback) {
        return jdbc.update("UPDATE life_outreach_candidate SET state=?,sent_at=?,feedback=? WHERE owner_id=? AND id=? AND state=?",
                state, sentAt == null ? null : sentAt.toString(), feedback, owner, id, expected) == 1;
    }
    /** 用户反馈的原始提交时间独立保存，不以候选创建时间冒充。 */
    public void feedback(String owner, Candidate c, String nextState, String feedback, Instant now) {
        if (jdbc.update("UPDATE life_outreach_candidate SET state=?,feedback=?,feedback_at=? WHERE owner_id=? AND id=? AND state=?",
                nextState, feedback, now.toString(), owner, c.id(), c.state()) != 1)
            throw new IllegalArgumentException("主动候选已变化，请刷新");
    }
    public List<Material> feedbackMaterials(String owner, Instant from, Instant cutoff) {
        return jdbc.query("""
                SELECT id,topic,feedback,feedback_at FROM life_outreach_candidate
                WHERE owner_id=? AND feedback<>'' AND julianday(feedback_at)>=julianday(?) AND julianday(feedback_at)<=julianday(?)
                ORDER BY feedback_at DESC LIMIT 10
                """, (r, n) -> new Material("feedback-" + r.getString("id"), "USER_FEEDBACK", r.getString("id"),
                    r.getString("feedback_at"), "用户对主动主题 " + r.getString("topic") + " 的明确反馈：" + r.getString("feedback"), false),
                    owner, from.toString(), cutoff.toString());
    }
    /** 读取本用户的作息候选，不把候选升级为稳定画像。 */
    public Optional<Habit> habit(String owner) {
        return jdbc.query("SELECT * FROM life_habit_candidate WHERE owner_id=? AND topic='review'", (r, n) ->
                new Habit(owner, "review", r.getInt("revision"), r.getInt("available_hour"), r.getString("source"),
                        r.getString("uncertainty"), Instant.parse(r.getString("observed_at")), Instant.parse(r.getString("expires_at")), r.getBoolean("active")), owner).stream().findFirst();
    }
    /** 读取本用户的作息候选，不把候选升级为稳定画像。 */
    public void habit(Habit h, int expected) {
        int rows = expected == 0 ? jdbc.update("INSERT INTO life_habit_candidate VALUES(?,?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING",
                h.owner(), h.topic(), h.revision(), h.availableHour(), h.source(), h.uncertainty(), h.observedAt().toString(), h.expiresAt().toString(), h.active())
                : jdbc.update("UPDATE life_habit_candidate SET revision=?,available_hour=?,source=?,uncertainty=?,observed_at=?,expires_at=?,active=? WHERE owner_id=? AND topic='review' AND revision=?",
                h.revision(), h.availableHour(), h.source(), h.uncertainty(), h.observedAt().toString(), h.expiresAt().toString(), h.active(), h.owner(), expected);
        if (rows != 1) throw new IllegalArgumentException("作息候选已变化，请刷新");
    }
    /** 补充请求ID与原正文及主题绑定，重放不创建第二条记录。 */
    public boolean reply(String owner, String id, String contentId, String text, Instant now) {
        int inserted = jdbc.update("INSERT INTO life_review_reply VALUES(?,?,?,?,?) ON CONFLICT(id) DO NOTHING",
                id, owner, contentId, text, now.toString());
        if (inserted == 0) {
            int matched = jdbc.queryForObject("SELECT COUNT(*) FROM life_review_reply WHERE id=? AND owner_id=? AND content_id=? AND text=?",
                    Integer.class, id, owner, contentId, text);
            if (matched != 1) throw new IllegalArgumentException("补充请求标识已被其他内容使用");
        }
        return inserted == 1;
    }
    /** 读取同一归属日且属于原授权的最近三条补充。 */
    public List<Material> replies(String owner, java.time.LocalDate day, String grantId) {
        return jdbc.query("""
                SELECT r.* FROM life_review_reply r JOIN life_review_content c ON c.id=r.content_id
                WHERE r.owner_id=? AND c.review_day=? AND c.grant_id=? ORDER BY r.created_at DESC LIMIT 3
                """, (r, n) -> new Material("reply-" + r.getString("id"), "SUPPLEMENT", r.getString("content_id"),
                        r.getString("created_at"), r.getString("text"), false), owner, day.toString(), grantId);
    }
    /** 只读读取重置保留的最近三十份冻结副本，不用于生成。 */
    public List<String> archives(String owner) {
        return jdbc.query("SELECT frozen_text FROM life_review_archive WHERE owner_id=? ORDER BY archived_at DESC LIMIT 30",
                (r, n) -> r.getString(1), owner);
    }
    /** 撤权只取消未发候选；已发副本保留原始状态。 */
    public void cancel(String owner) {
        jdbc.update("UPDATE life_outreach_candidate SET state='CANCELLED' WHERE owner_id=? AND state='PENDING'", owner);
    }
    /** 重置清除派生材料、候选与正文，保留调用计数防止重置退还额度。 */
    public void reset(String owner) {
        revokeGrants(owner, Instant.now());
        for (var c : jdbc.query("SELECT content_json FROM life_review_content WHERE owner_id=?",
                (r, n) -> read(r.getString(1), Content.class), owner)) {
            if (deliveries(owner, c.id()).stream().anyMatch(d -> d.state().equals("SENT") || d.state().equals("UNKNOWN") || d.state().equals("SENDING")))
                jdbc.update("INSERT INTO life_review_archive VALUES(?,?,?,?) ON CONFLICT DO NOTHING",
                        c.id(), owner, LifeDeliveryService.render(c), Instant.now().toString());
        }
        jdbc.update("DELETE FROM life_review_reply WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM life_outreach_candidate WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM life_habit_candidate WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM life_review_delivery WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM life_review_content WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM life_review_settings WHERE owner_id=?", owner);
    }
    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("生活复盘持久化失败");
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("生活复盘记录不可读取");
        }
    }
}
