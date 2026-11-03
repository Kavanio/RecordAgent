package butvan.agent.network.controller;

import butvan.agent.agents.session.SessionLifecycleService;
import butvan.agent.agents.session.dto.SessionDetailDto;
import butvan.agent.agents.session.dto.SessionSummaryDto;
import butvan.agent.agents.session.dto.SessionKind;
import butvan.agent.agents.session.dto.SessionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Instant;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 独立飞书 GET 路由不落入桌面详情及写入入口。 */
class SessionControllerReadOnlyTest {
    @Test void exposesSeparateReadOnlyListAndDetail() throws Exception {
        var service = mock(SessionLifecycleService.class);
        var now = Instant.parse("2026-11-03T00:00:00Z");
        var summary = new SessionSummaryDto("feishu-1", SessionKind.GENERAL, null,
                "飞书历史", "", now, now, SessionStatus.ACTIVE);
        when(service.listFeishuSessions()).thenReturn(List.of(summary));
        when(service.getFeishuDetail("feishu-1")).thenReturn(new SessionDetailDto(summary, List.of(), null));
        var mvc = MockMvcBuilders.standaloneSetup(new SessionController(service)).build();
        mvc.perform(get("/agent/sessions/feishu")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value("feishu-1"));
        mvc.perform(get("/agent/sessions/feishu/feishu-1")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.id").value("feishu-1"));
        mvc.perform(delete("/agent/sessions/feishu/feishu-1")).andExpect(status().isMethodNotAllowed());
        verify(service, never()).getDetail(anyString());
        verify(service, never()).deleteSession(anyString());
    }
}
