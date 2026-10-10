package com.butvan.recordagent.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.butvan.recordagent.annotation.ApiLog;
import com.butvan.recordagent.filter.RequestIdFilter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.sql.SQLException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 验证错误响应使用真实 HTTP 状态且不会回显请求正文。 */
class ApiExceptionHandlerTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc =
                MockMvcBuilders.standaloneSetup(new ErrorProbeController())
                        .setControllerAdvice(new ApiExceptionHandler())
                        .addFilters(new RequestIdFilter())
                        .build();
    }

    @Test
    void invalidInputReturnsStableSafeError() throws Exception {
        mvc.perform(
                        post("/test/error")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(header().exists(RequestIdFilter.HEADER_NAME))
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(jsonPath("$.message").value("请求参数无效"));
    }

    @Test
    void conflictKeepsInternalDetailsPrivate() throws Exception {
        mvc.perform(
                        post("/test/error")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"value\":\"conflict\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.message").value("资源已更新，请刷新后重试"));
    }

    @Test
    void missingRequiredParameterIsBadRequest() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/test/query"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void unsupportedMethodRetainsProtocolStatus() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/test/error"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", "POST"))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedMediaTypeIsNotServerFailure() throws Exception {
        mvc.perform(post("/test/error").contentType(MediaType.TEXT_PLAIN).content("secret"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void constraintViolationIsConflictAndSqlBugIsInternalFailure() throws Exception {
        mvc.perform(post("/test/database").param("kind", "constraint"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mvc.perform(post("/test/database").param("kind", "sql"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class ErrorProbeController {
        @ApiLog("验证必填参数")
        @GetMapping("/test/query")
        public void query(@RequestParam String value) {}

        @ApiLog("验证数据库错误分类")
        @PostMapping("/test/database")
        public void database(@RequestParam String kind) {
            if (kind.equals("constraint")) throw new DataIntegrityViolationException("private");
            throw new BadSqlGrammarException("private", "private", new SQLException());
        }

        @ApiLog("验证错误处理")
        @PostMapping("/test/error")
        public void error(@Valid @RequestBody ProbeRequest request) {
            throw new ApiException(ApiErrorCode.VERSION_CONFLICT, "内部版本号和业务正文不应出现在响应中");
        }
    }

    record ProbeRequest(@NotBlank String value) {}
}
