package butvan.agent.agents.prompts;

import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeDateMiddlewareTest {
    @Test void cachedPromptUsesCurrentLocalDayRatherThanItsCreationDay() {
        var middleware = new RuntimeDateMiddleware(Clock.fixed(
                Instant.parse("2026-10-28T17:00:00Z"), ZoneId.of("Asia/Shanghai")));
        String result = middleware.onSystemPrompt(null, null,
                "# 环境\n - 当前日期: 2026-10-28\n - 项目: example").block();
        assertTrue(result.contains("2026-10-29（Asia/Shanghai）"));
        assertTrue(result.contains("项目: example"));
        assertFalse(result.contains("2026-10-28"));
    }
}
