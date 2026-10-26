package butvan.agent.feishu.service;

import butvan.agent.agents.agent.AgentService;
import butvan.agent.feishu.config.FeishuConfigData;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class FeishuConfigurationTest {
    @Test void configFailsClosedAndDoesNotPrintCredentials() {
        FeishuConfigData config = new FeishuConfigData(true, "app", "private-secret", List.of(), 60, 600, 600, 5, 2);
        assertFalse(config.isReady()); assertFalse(config.toString().contains("private-secret"));
        assertThrows(IllegalArgumentException.class, () -> new FeishuConfigData(true, "app", "secret", List.of("a", "b"), 60, 600, 600, 5, 2));
    }
    @Test void runnerHasAnUnambiguousSpringConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(AgentService.class, () -> mock(AgentService.class));
            context.register(FeishuAgentRunner.class); context.refresh();
            assertNotNull(context.getBean(FeishuAgentRunner.class));
        }
    }
}
