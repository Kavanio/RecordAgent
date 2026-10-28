package butvan.agent.network.controller;

import butvan.agent.network.chat.dto.AgentChatRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实 Jackson 反序列化前的 JSON 请求体上限；不经过模型或磁盘。 */
class ChatRequestSizeAdviceTest {
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Endpoint())
            .setControllerAdvice(new ChatRequestSizeAdvice()).build();

    @RestController
    static class Endpoint {
        @PostMapping("/chat") String chat(@RequestBody AgentChatRequest request) { return "ok"; }
        @PostMapping("/other") String other(@RequestBody String request) { return "other"; }
    }

    @Test void acceptsOrdinaryJson() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content("{\"sessionId\":\"s\"}"))
                .andExpect(status().isOk()).andExpect(content().string("ok"));
    }

    @Test void rejectsDeclaredOversizeBeforeReading() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON)
                        .header("Content-Length", ChatRequestSizeAdvice.MAX_REQUEST_BYTES + 1L).content("{}"))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value(413));
    }

    @Test void enforcesLimitOnActualBytesEvenWithMisleadingContentLength() throws Exception {
        String json = "{\"context\":\"" + "x".repeat(ChatRequestSizeAdvice.MAX_REQUEST_BYTES) + "\"}";
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).header("Content-Length", 1).content(json))
                .andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.message").value(
                        "聊天请求过大，每轮图片总大小不能超过 12 MiB"));
    }

    @Test void doesNotChangeOtherEndpoints() throws Exception {
        mvc.perform(post("/other").contentType(MediaType.TEXT_PLAIN).content("value"))
                .andExpect(status().isOk()).andExpect(content().string("other"));
    }
}
