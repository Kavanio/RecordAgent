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
    @Test void optOutIsSeparatedFromShortCommitmentsAndOtherRejections() {
        assertEquals(MemorySourcePolicy.Decision.OPT_OUT, MemorySourcePolicy.decideText("这次的具体内容不要记住。"));
        assertEquals(MemorySourcePolicy.Decision.OPT_OUT, MemorySourcePolicy.decideText("别保存刚才那句。"));
        assertEquals(MemorySourcePolicy.Decision.OPT_OUT, MemorySourcePolicy.decideText("不用记录这个。"));
        // 短句不等于没有价值：明确采纳仍是候选来源。
        assertEquals(MemorySourcePolicy.Decision.ELIGIBLE, MemorySourcePolicy.decideText("好，我以后每天练两分钟。"));
        assertEquals(MemorySourcePolicy.Decision.SENSITIVE_OR_CODE, MemorySourcePolicy.decideText("我的密码是 synthetic"));
        assertEquals(MemorySourcePolicy.Decision.TOO_LONG, MemorySourcePolicy.decideText("a".repeat(4001)));
        assertEquals(MemorySourcePolicy.Decision.EMPTY, MemorySourcePolicy.decideText("   "));
        assertEquals(MemorySourcePolicy.Decision.UNTRUSTED_CONTEXT,
                MemorySourcePolicy.decide(new AgentUserCall("session", "说明", "说明", List.of(), "run")));
    }
    @Test void imagesCommandsFencedCodeAndSensitiveTextAreExcluded() {
        assertFalse(MemorySourcePolicy.eligible(new AgentUserCall("session", "说明", "说明", List.of(), "run", null,
                List.of(new AgentImageAttachment("image/png", new byte[32])), MemorySourceRegistry.Channel.FEISHU)));
        for (String text : List.of("/finance-review", "```java\ncode\n```", "api_key=synthetic", "密码只用于测试", "a".repeat(4001)))
            assertFalse(MemorySourcePolicy.eligibleText(text));
    }
}
