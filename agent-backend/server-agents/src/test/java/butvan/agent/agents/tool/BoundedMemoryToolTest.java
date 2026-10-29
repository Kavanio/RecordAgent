package butvan.agent.agents.tool;

import butvan.agent.agents.usage.ApproximateTokenCounter;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import org.junit.jupiter.api.Test;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.ToolCallParam;
import static org.junit.jupiter.api.Assertions.*;

class BoundedMemoryToolTest {
    @Test void largeLegacyResultIsBoundedBeforeItEntersAgentHistory() {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new Legacy());
        var wrapper = new BoundedMemoryTool(toolkit.getTool("memory_search"));
        var result = wrapper.callAsync(ToolCallParam.builder().build()).block();
        String text = ((TextBlock) result.getOutput().getFirst()).getText();
        assertTrue(new ApproximateTokenCounter().count(text) <= 800);
        assertTrue(text.contains("旧记忆可能含模型摘要"));
        assertTrue(text.contains("截断"));
    }
    static class Legacy {
        @Tool(name = "memory_search", description = "旧记忆", readOnly = true)
        public String search() { return "用户信息".repeat(10_000); }
    }
}
