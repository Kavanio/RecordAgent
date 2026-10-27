package butvan.agent.network.review.repository;

import butvan.agent.network.review.dto.ReviewMemoryDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import java.time.*;
import java.util.*;

/** 记忆的受控读模型与乐观锁写入；任何操作同时约束当前用户。 */
@Repository
@RequiredArgsConstructor
public class ReviewMemoryRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<ActionView> ACTION = (r,n) -> new ActionView(r.getString("id"), r.getString("job_id"),
            r.getString("topic_key"), r.getString("goal_id"), r.getString("title"), r.getString("rationale"),
            r.getString("criterion"), r.getString("method_id"), r.getString("state"), r.getInt("revision"),
            r.getString("check_on")==null?null:LocalDate.parse(r.getString("check_on")),r.getString("feedback"),
            Instant.parse(r.getString("created_at")),Instant.parse(r.getString("updated_at")));
    private static final RowMapper<ObservationView> OBSERVATION = (r,n) -> new ObservationView(r.getString("id"),
            r.getString("job_id"),r.getString("topic_key"),r.getString("content"),r.getString("uncertainty"),r.getString("state"),
            r.getInt("revision"),r.getString("correction"),Instant.parse(r.getString("created_at")),Instant.parse(r.getString("expires_at")));

    /** 只加载有界动作列表，用户主观反馈不会被系统自行判定成败。 */
    public List<ActionView> actions(String owner) {
        return jdbc.query("SELECT * FROM review_action WHERE owner_id=? ORDER BY updated_at DESC,id DESC LIMIT 100", ACTION, owner);
    }
    /** 返回未过期观察及其状态，便于用户查看已纠正／否定的历史；召回另只取 CANDIDATE。 */
    public List<ObservationView> observations(String owner, Instant now) {
        return jdbc.query("SELECT * FROM review_observation WHERE owner_id=? AND julianday(expires_at)>julianday(?) ORDER BY created_at DESC,id DESC LIMIT 100",
                OBSERVATION, owner, now.toString());
    }
    /** 读取当前用户动作，其他用户 ID 与不存在语义一致。 */
    public ActionView action(String owner,String id) {
        return jdbc.query("SELECT * FROM review_action WHERE owner_id=? AND id=?", ACTION, owner,id).stream()
                .findFirst().orElseThrow(()->new IllegalArgumentException("建议不存在"));
    }
    /** 读取当前用户观察；过期观察不可继续纠正或作为证据。 */
    public ObservationView observation(String owner,String id,Instant now) {
        return observations(owner,now).stream().filter(v->v.id().equals(id)).findFirst()
                .orElseThrow(()->new IllegalArgumentException("观察不存在或已过期"));
    }
    /** 使用版本号原子提交完整反馈。 */
    public void feedback(String owner,ActionView old,String state,String title,String criterion,LocalDate checkOn,
                         String feedback,Instant now,Instant cooldown) {
        if(jdbc.update("UPDATE review_action SET state=?,title=?,criterion=?,check_on=?,feedback=?,updated_at=?,cooldown_until=?,revision=revision+1 WHERE owner_id=? AND id=? AND revision=?",
                state,title,criterion,checkOn==null?null:checkOn.toString(),feedback,now.toString(),cooldown==null?null:cooldown.toString(),owner,old.id(),old.revision())!=1)
            throw new IllegalArgumentException("建议已变化，请刷新后重试");
    }
    /** 否定或纠正保留来源，不静默覆盖原始观察。 */
    public void correct(String owner,ObservationView old,String state,String correction) {
        if(jdbc.update("UPDATE review_observation SET state=?,correction=?,revision=revision+1 WHERE owner_id=? AND id=? AND revision=?",
                state,correction,owner,old.id(),old.revision())!=1) throw new IllegalArgumentException("观察已变化，请刷新后重试");
    }
    /** 幂等请求只重放相同正文，禁止同键不同操作。 */
    public Optional<String> response(String owner,String requestId,String target,String fingerprint) {
        return jdbc.query("SELECT target_id,payload_fingerprint,response_json FROM review_feedback_request WHERE owner_id=? AND request_id=?",
                (r,n)->{if(!target.equals(r.getString(1)) || !fingerprint.equals(r.getString(2)))
                    throw new IllegalArgumentException("请求标识已用于其他操作"); return r.getString(3);},owner,requestId).stream().findFirst();
    }
    /** 与反馈处于同一事务，网络重试不会重复应用。 */
    public void remember(String owner,String requestId,String target,String fingerprint,String json,Instant now) {
        jdbc.update("INSERT INTO review_feedback_request(owner_id,request_id,target_id,payload_fingerprint,response_json,created_at) VALUES(?,?,?,?,?,?)",
                owner,requestId,target,fingerprint,json,now.toString());
    }
}
