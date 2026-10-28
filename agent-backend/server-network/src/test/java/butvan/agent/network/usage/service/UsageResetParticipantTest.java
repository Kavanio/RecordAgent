package butvan.agent.network.usage.service;

import butvan.agent.network.config.database.*;
import butvan.agent.network.usage.repository.TokenUsageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用真实迁移后的临时 SQLite，确认清聊天投影同时保留系统统计与其他所有者。 */
class UsageResetParticipantTest {
    @TempDir Path directory;
    @Test void clearsOnlyCurrentUsersChatProjectionAndKeepsSystemUsage() {
        var properties = new LocalDatabaseProperties();
        properties.setPath(directory.resolve("test.db"));
        var configuration = new LocalDatabaseConfiguration();
        var source = configuration.localDataSource(properties);
        configuration.flyway(source).migrate();
        var jdbc = new JdbcTemplate(source);
        var repository = new TokenUsageRepository(jdbc, new NamedParameterJdbcTemplate(source));
        for (String owner : new String[]{"local-default", "other-user"})
            jdbc.update("INSERT INTO token_usage_turn(message_id,owner_id,session_id,turn_id,occurred_at,usage_status) VALUES(?,?,?,'turn','2026-10-27T00:00:00Z','UNAVAILABLE')",
                    owner, owner, "session");
        jdbc.update("INSERT INTO token_usage_invocation(id,owner_id,usage_kind,purpose,occurred_at,usage_status,input_tokens,output_tokens,total_tokens) VALUES('chat','local-default','CHAT','CHAT','2026-10-27T00:00:00Z','COMPLETE',4,6,10)");
        jdbc.update("INSERT INTO token_usage_invocation(id,owner_id,usage_kind,purpose,occurred_at,usage_status,input_tokens,output_tokens,total_tokens) VALUES('system','local-default','SYSTEM','CONNECTION_TEST','2026-10-27T00:00:00Z','COMPLETE',2,3,5)");
        var scope = new TokenUsageRepository.QueryScope("local-default", null, null, null);
        var other = new TokenUsageRepository.QueryScope("other-user", null, null, null);
        assertEquals(15, repository.summarizeInvocations(scope).totalTokens());
        var reset = new UsageResetParticipant(repository);
        reset.clean("local-default"); reset.clean("local-default");
        assertEquals(0, repository.summarizeTurns(scope).turnCount());
        assertEquals(5, repository.summarizeInvocations(scope).totalTokens());
        assertEquals(1, repository.summarizeTurns(other).turnCount());
        assertTrue(Files.isRegularFile(properties.getPath()));
    }
}
