package com.butvan.recordagent.repository.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL 固定窗口计数器；原子 UPSERT 保护跨实例并发。 */
@Repository
@RequiredArgsConstructor
public class RateLimitRepository {
    private final JdbcTemplate jdbc;

    /** 已过期窗口从一次重新计数；达到上限后饱和，不因持续请求溢出整数。 */
    public int consume(String scope, String hash, int seconds, int limit) {
        return jdbc.queryForObject(
                """
                INSERT INTO record.auth_rate_bucket(scope,key_hash,window_start,attempts) VALUES(?,?,statement_timestamp(),1)
                ON CONFLICT(scope,key_hash) DO UPDATE SET
                 attempts=CASE WHEN record.auth_rate_bucket.window_start<=statement_timestamp()-make_interval(secs=>?)
                   THEN 1 ELSE LEAST(record.auth_rate_bucket.attempts+1,?+1) END,
                 window_start=CASE WHEN record.auth_rate_bucket.window_start<=statement_timestamp()-make_interval(secs=>?)
                   THEN statement_timestamp() ELSE record.auth_rate_bucket.window_start END
                RETURNING attempts
                """,
                Integer.class,
                scope,
                hash,
                seconds,
                limit,
                seconds);
    }

    /** 每轮限流顺便清理过期桶；所有策略窗口最多一小时，保留一天避免删除有效窗口。 */
    public void cleanExpired() {
        jdbc.update(
                "DELETE FROM record.auth_rate_bucket WHERE window_start<statement_timestamp()-interval '1 day'");
    }
}
