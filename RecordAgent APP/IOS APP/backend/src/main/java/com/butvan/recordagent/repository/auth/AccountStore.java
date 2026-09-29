package com.butvan.recordagent.repository.auth;

import com.butvan.recordagent.dto.auth.AuthDtos;

import com.butvan.recordagent.dto.auth.AuthDtos.User;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 账户表的 SQL 适配器，凭据字段只以摘要形式持久化。 */
@Repository
@RequiredArgsConstructor
public class AccountStore {
    private final JdbcTemplate jdbc;

    public record LoginRow(UUID id, String email, String passwordHash, String nickname,
            OffsetDateTime createdAt, long version) {
        public User user() { return new User(id, email, nickname, createdAt, version); }
    }
    public record Challenge(UUID id, String hash, OffsetDateTime expiresAt, int attempts) {}
    public record SessionRow(UUID id, UUID userId, OffsetDateTime expiresAt) {}

    /** 按规范化邮箱查找账户。 */
    public Optional<LoginRow> findByEmail(String email) {
        return first(jdbc.query("SELECT id,email_normalized,password_hash,display_name,created_at,version "
                + "FROM record.app_user WHERE email_normalized=? AND status='active'", (rs, n) ->
                new LoginRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getObject(5, OffsetDateTime.class), rs.getLong(6)), email));
    }

    /** 按稳定 ID 查询当前用户。 */
    public Optional<User> user(UUID id) {
        return first(jdbc.query("SELECT id,email_normalized,display_name,created_at,version "
                + "FROM record.app_user WHERE id=? AND status='active'", (rs, n) ->
                new User(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getObject(4, OffsetDateTime.class), rs.getLong(5)), id));
    }

    public void createUser(UUID id, String email, String passwordHash, String nickname) {
        jdbc.update("INSERT INTO record.app_user(id,email_normalized,password_hash,display_name,display_name_normalized) "
                + "VALUES(?,?,?,?,?)", id, email, passwordHash, nickname, nickname.toLowerCase(java.util.Locale.ROOT));
    }

    public Optional<Challenge> latestChallenge(String email, String purpose) {
        return first(jdbc.query("SELECT id,code_hash,expires_at,attempt_count FROM record.email_challenge "
                + "WHERE email_normalized=? AND purpose=? AND consumed_at IS NULL "
                + "ORDER BY created_at DESC LIMIT 1 FOR UPDATE", (rs, n) ->
                new Challenge(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getInt(4)), email, purpose));
    }

    public Optional<OffsetDateTime> lastChallengeAt(String email, String purpose) {
        return first(jdbc.query("SELECT created_at FROM record.email_challenge "
                + "WHERE email_normalized=? AND purpose=? ORDER BY created_at DESC LIMIT 1", (rs, n) ->
                rs.getObject(1, OffsetDateTime.class), email, purpose));
    }

    public void createChallenge(UUID id, String email, String purpose, String hash, OffsetDateTime expires) {
        jdbc.update("INSERT INTO record.email_challenge(id,email_normalized,purpose,code_hash,expires_at) "
                + "VALUES(?,?,?,?,?)", id, email, purpose, hash, expires);
    }

    public void failedChallenge(UUID id) {
        jdbc.update("UPDATE record.email_challenge SET attempt_count=attempt_count+1 WHERE id=?", id);
    }

    public void consumeChallenge(UUID id) {
        jdbc.update("UPDATE record.email_challenge SET consumed_at=now() WHERE id=? AND consumed_at IS NULL", id);
    }

    public void createSession(UUID id, UUID userId, String accessHash, String refreshHash,
            OffsetDateTime accessExpires, OffsetDateTime refreshExpires) {
        jdbc.update("INSERT INTO record.auth_session(id,user_id,access_token_hash,refresh_token_hash,access_expires_at,expires_at) "
                + "VALUES(?,?,?,?,?,?)", id, userId, accessHash, refreshHash, accessExpires, refreshExpires);
    }

    public Optional<SessionRow> accessSession(String hash) {
        return first(jdbc.query("SELECT id,user_id,access_expires_at FROM record.auth_session "
                + "WHERE access_token_hash=? AND revoked_at IS NULL AND access_expires_at>now() AND expires_at>now()",
                (rs, n) -> new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, OffsetDateTime.class)), hash));
    }

    public Optional<SessionRow> refreshSession(String hash) {
        return first(jdbc.query("SELECT id,user_id,expires_at FROM record.auth_session "
                + "WHERE refresh_token_hash=? AND revoked_at IS NULL AND expires_at>now() FOR UPDATE",
                (rs, n) -> new SessionRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, OffsetDateTime.class)), hash));
    }

    public void revokeSession(UUID id) {
        jdbc.update("UPDATE record.auth_session SET revoked_at=now() WHERE id=? AND revoked_at IS NULL", id);
    }

    public void changePassword(UUID userId, String passwordHash) {
        jdbc.update("UPDATE record.app_user SET password_hash=?,version=version+1,updated_at=now() WHERE id=?",
                passwordHash, userId);
        jdbc.update("UPDATE record.auth_session SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", userId);
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }
}
