package butvan.agent.agents.memory;

import butvan.agent.agents.agent.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 筛选只依据真实原文与可信来源，不从 context、附件或未知入口猜资格。 */
class MemorySourcePolicyTest {
    @Test void onlyMarkedPlainUserTextIsEligible() {
        assertFalse(MemorySourcePolicy.eligible(new AgentUserCall("session", "raw", "raw", List.of(), "run")));
        assertTrue(MemorySourcePolicy.eligible(new AgentUserCall("session", "经历", "经历", List.of(), "run", null,
                List.of(), MemorySourceRegistry.Channel.DESKTOP)));
        assertFalse(MemorySourcePolicy.eligible(new AgentUserCall("session", "", "expanded private text", List.of(), "run", null,
                List.of(), MemorySourceRegistry.Channel.DESKTOP)));
    }
    @Test void imagesCommandsFencedCodeAndSensitiveTextAreExcluded() {
        assertFalse(MemorySourcePolicy.eligible(new AgentUserCall("session", "说明", "说明", List.of(), "run", null,
                List.of(new AgentImageAttachment("image/png", new byte[32])), MemorySourceRegistry.Channel.FEISHU)));
        for (String text : List.of("/finance-review", "```java\ncode\n```", "api_key=synthetic", "密码只用于测试", "a".repeat(4001)))
            assertFalse(MemorySourcePolicy.eligibleText(text));
    }
}
