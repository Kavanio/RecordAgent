package butvan.agent.network.review.repository;

import butvan.agent.network.review.model.ReviewModels.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.List;
import java.util.Optional;

/** 复盘工作项、证据与邮件冻结副本的 SQLite Adapter；所有查询约束 owner。 */
@Repository
@RequiredArgsConstructor
public class ReviewCoachRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<Job> JOB = (r,n) -> new Job(r.getString("id"),r.getString("owner_id"),r.getString("period"),
            LocalDate.parse(r.getString("period_from")),LocalDate.parse(r.getString("period_to")),r.getString("timezone"),
            r.getString("grant_id"),r.getInt("grant_revision"),r.getInt("settings_revision"),
            r.getString("connection_fingerprint"),r.getString("goals_fingerprint"),r.getString("state"),
            r.getBoolean("preview"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("deadline")),
            r.getString("finished_at")==null?null:Instant.parse(r.getString("finished_at")),r.getString("result_json"),
            r.getString("message"),r.getInt("revision"));

    /** 相同去重键复用既有工作项，不重复触发付费调用。 */
    public Optional<Job> existing(String owner,String period,LocalDate from,LocalDate to,String timezone,String grantId,
                                  int settingsRevision,String goalsFingerprint,boolean preview) {
        return jdbc.query("""
                SELECT * FROM review_job WHERE owner_id=? AND period=? AND period_from=? AND period_to=? AND timezone=?
                  AND grant_id=? AND settings_revision=? AND goals_fingerprint=? AND preview=?
                """,JOB,owner,period,from.toString(),to.toString(),timezone,grantId,settingsRevision,goalsFingerprint,preview?1:0)
                .stream().findFirst();
    }
    /** 预占当日付费调用额度；达到上限即拒绝，UNKNOWN 不释放。 */
    public boolean reserveBudget(String owner,String day,int limit) {
        jdbc.update("INSERT INTO review_daily_budget(owner_id,budget_day,attempts) VALUES(?,?,0) ON CONFLICT(owner_id,budget_day) DO NOTHING",owner,day);
        return jdbc.update("UPDATE review_daily_budget SET attempts=attempts+1 WHERE owner_id=? AND budget_day=? AND attempts<?",owner,day,limit)==1;
    }
    /** 写入待处理工作项；唯一键冲突说明并发已创建，由调用方回读。 */
    public void insert(Job value) {
        jdbc.update("""
                INSERT INTO review_job(id,owner_id,period,period_from,period_to,timezone,grant_id,grant_revision,
                  settings_revision,connection_fingerprint,goals_fingerprint,state,preview,created_at,deadline,expires_at)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,value.id(),value.owner(),value.period(),value.from().toString(),value.to().toString(),value.timezone(),
                value.grantId(),value.grantRevision(),value.settingsRevision(),value.fingerprint(),value.goalsFingerprint(),
                value.state(),value.preview()?1:0,value.createdAt().toString(),value.deadline().toString(),
                value.createdAt().plus(Duration.ofDays(180)).toString());
    }
    /** 原子领取，同一用户只允许一个进行中的模型调用。 */
    public boolean claim(String id) { return jdbc.update("UPDATE review_job SET state='RUNNING',submitted_at=? WHERE id=? AND state='PENDING'",Instant.now().toString(),id)==1; }
    /** 领取前再次确认授权与期限仍有效。 */
    public boolean claimValid(String id,Instant now) {
        return jdbc.update("UPDATE review_job SET state='RUNNING',submitted_at=?,revision=revision+1 WHERE id=? AND state='PENDING' AND julianday(deadline)>julianday(?)",
                now.toString(),id,now.toString())==1;
    }
    public Optional<Job> find(String owner,String id) {
        return jdbc.query("SELECT * FROM review_job WHERE owner_id=? AND id=?",JOB,owner,id).stream().findFirst();
    }
    public Optional<Job> findById(String id) {
        return jdbc.query("SELECT * FROM review_job WHERE id=?",JOB,id).stream().findFirst();
    }
    /** 历史列表只返回有界摘要，不加载结果正文。 */
    public List<Job> history(String owner,int limit) {
        return jdbc.query("SELECT * FROM review_job WHERE owner_id=? ORDER BY created_at DESC,id DESC LIMIT ?",JOB,owner,limit);
    }
    public List<Job> pending() {
        return jdbc.query("SELECT * FROM review_job WHERE state='PENDING' AND julianday(expires_at)>julianday(?) ORDER BY created_at LIMIT 20",
                JOB,Instant.now().toString());
    }
    /** 只有进行中的工作项可以写入终态，迟到结果被拒绝。 */
    public boolean finish(String id,String state,String resultJson,String message,Instant now) {
        return jdbc.update("UPDATE review_job SET state=?,result_json=?,message=?,finished_at=?,revision=revision+1 WHERE id=? AND state='RUNNING'",
                state,resultJson,message,now.toString(),id)==1;
    }
    /** 撤销或预算不足时跳过，不产生模型调用。 */
    public void skip(String id,String message) {
        jdbc.update("UPDATE review_job SET state='SKIPPED',message=?,finished_at=? WHERE id=? AND state IN ('PENDING','RUNNING')",
                message,Instant.now().toString(),id);
    }
    public void evidence(String id,Evidence value) {
        jdbc.update("INSERT INTO review_evidence(job_id,evidence_id,snapshot_id,domain,metric_key,subject_id,currency,unit,value) VALUES(?,?,?,?,?,?,?,?,?)",
                id,value.id(),value.snapshotId(),value.domain(),value.metricKey(),value.subjectId(),value.currency(),value.unit(),value.value());
    }
    public void action(String jobId,Instant now,String id,String owner,String topicKey,String goalId,String title,
                       String rationale,String criterion,String methodId,LocalDate checkOn,Instant cooldown) {
        jdbc.update("""
                INSERT INTO review_action(id,owner_id,job_id,topic_key,goal_id,title,rationale,criterion,method_id,state,
                  revision,check_on,feedback,created_at,updated_at,cooldown_until)
                VALUES(?,?,?,?,?,?,?,?,?,'PROPOSED',1,?, '',?,?,?)
                """,id,owner,jobId,topicKey,goalId,title,rationale,criterion,methodId,checkOn==null?null:checkOn.toString(),
                now.toString(),now.toString(),cooldown==null?null:cooldown.toString());
    }
    public void observation(String jobId,String id,String owner,String topicKey,String content,String uncertainty,Instant now) {
        jdbc.update("INSERT INTO review_observation(id,owner_id,job_id,topic_key,content,uncertainty,state,revision,correction,created_at,expires_at) VALUES(?,?,?,?,?,?,'CANDIDATE',1,'',?,?)",
                id,owner,jobId,topicKey,content,uncertainty,now.toString(),now.plus(Duration.ofDays(30)).toString());
    }
    public void linkEvidence(String jobId,String memoryId,String evidenceId) {
        jdbc.update("INSERT OR IGNORE INTO review_memory_evidence(memory_id,job_id,evidence_id) VALUES(?,?,?)",memoryId,jobId,evidenceId);
    }
    public List<String> memoryEvidence(String jobId,String memoryId) {
        return jdbc.queryForList("SELECT evidence_id FROM review_memory_evidence WHERE job_id=? AND memory_id=?",String.class,jobId,memoryId);
    }
    /** 现有待检查行动用于避免重复建议。 */
    public List<Action> recallActions(String owner,Instant now) {
        return jdbc.query("""
                SELECT * FROM review_action WHERE owner_id=? AND (state IN ('ACCEPTED','DEFERRED','UNKNOWN')
                  OR (state='PROPOSED' AND (cooldown_until IS NULL OR julianday(cooldown_until)<=julianday(?))))
                ORDER BY updated_at DESC LIMIT 4
                """,(r,n)->new Action(r.getString("id"),r.getString("owner_id"),r.getString("job_id"),r.getString("topic_key"),
                r.getString("goal_id"),r.getString("title"),r.getString("rationale"),r.getString("criterion"),
                r.getString("method_id"),r.getString("state"),r.getInt("revision"),
                r.getString("check_on")==null?null:LocalDate.parse(r.getString("check_on")),r.getString("feedback"),
                Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at"))),owner,now.toString());
    }
    public List<Observation> recallObservations(String owner,Instant now) {
        return jdbc.query("SELECT * FROM review_observation WHERE owner_id=? AND state='CANDIDATE' AND julianday(expires_at)>julianday(?) ORDER BY created_at DESC LIMIT 4",
                (r,n)->new Observation(r.getString("id"),r.getString("owner_id"),r.getString("job_id"),r.getString("topic_key"),
                r.getString("content"),r.getString("uncertainty"),r.getString("state"),r.getInt("revision"),
                r.getString("correction"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("expires_at"))),owner,now.toString());
    }
    /** 运行前撤销全部未冻结 AI 副本，避免撤销后仍发送 AI 内容；同时将等待中的基础报告邮件释放为待发送。 */
    public int disableUnfrozenDeliveries(String owner) {
        int count = jdbc.update("UPDATE automation_review_delivery SET state='DISABLED',privacy_disabled=1 WHERE owner_id=? AND state='WAITING'",owner);
        if (count > 0) {
            jdbc.update("UPDATE automation_run SET email_status='PENDING',error='AI 复盘已停用，已发送基础报告' WHERE owner_id=? AND email_status='WAITING'", owner);
        }
        return count;
    }
    /** 出厂重置专用：保护人工目标及已冻结邮件副本，只清理个人化派生正文。 */
    public void resetPersonalization(String owner) {
        disableUnfrozenDeliveries(owner);
        jdbc.update("UPDATE automation_review_delivery SET final_text=NULL,final_html=NULL WHERE owner_id=? AND state<>'FROZEN'", owner);
        jdbc.update("DELETE FROM review_feedback_request WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM review_action WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM review_observation WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM review_job WHERE owner_id=?", owner);
        jdbc.update("DELETE FROM review_snapshot WHERE owner_id=?", owner);
        jdbc.update("UPDATE review_task_preference SET enabled=0,revision=revision+1 WHERE owner_id=? AND enabled<>0", owner);
        // 当天额度属于无正文防重复审计，保留以免重置被用于绕过调用上限。
    }

    public void registerDelivery(Delivery value) {
        jdbc.update("""
                INSERT INTO automation_review_delivery(run_id,owner_id,review_id,state,base_text,base_html,deadline)
                VALUES(?,?,?,?,?,?,?) ON CONFLICT(run_id) DO NOTHING
                """,value.runId(),value.owner(),value.reviewId(),value.state(),value.baseText(),value.baseHtml(),value.deadline().toString());
    }
    public Optional<Delivery> delivery(String runId) {
        return jdbc.query("SELECT * FROM automation_review_delivery WHERE run_id=?",(r,n)->new Delivery(r.getString("run_id"),
                r.getString("owner_id"),r.getString("review_id"),r.getString("state"),r.getString("base_text"),
                r.getString("base_html"),r.getString("final_text"),r.getString("final_html"),
                Instant.parse(r.getString("deadline")),r.getString("frozen_at")==null?null:Instant.parse(r.getString("frozen_at"))),runId)
                .stream().findFirst();
    }
    public List<Delivery> waitingDeliveries() {
        return jdbc.query("SELECT * FROM automation_review_delivery WHERE state='WAITING' ORDER BY deadline LIMIT 20",(r,n)->new Delivery(
                r.getString("run_id"),r.getString("owner_id"),r.getString("review_id"),r.getString("state"),r.getString("base_text"),
                r.getString("base_html"),r.getString("final_text"),r.getString("final_html"),Instant.parse(r.getString("deadline")),
                r.getString("frozen_at")==null?null:Instant.parse(r.getString("frozen_at"))));
    }
    /** 一次性冻结最终副本；晚到的结果不能再改写。 */
    public boolean freeze(String runId,String finalText,String finalHtml,boolean ai,Instant now) {
        return jdbc.update("""
                UPDATE automation_review_delivery SET state='FROZEN',final_text=?,final_html=?,frozen_at=?
                WHERE run_id=? AND state='WAITING'
                """,ai?finalText:null,ai?finalHtml:null,now.toString(),runId)==1;
    }
}
