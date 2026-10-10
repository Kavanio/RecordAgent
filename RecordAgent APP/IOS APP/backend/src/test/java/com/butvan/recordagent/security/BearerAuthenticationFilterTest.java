package com.butvan.recordagent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.butvan.recordagent.filter.RequestIdFilter;
import com.butvan.recordagent.handler.HttpErrorWriter;
import com.butvan.recordagent.service.auth.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 认证数据库故障发生在 MVC 之外，也应返回安全错误及请求关联 ID。 */
class BearerAuthenticationFilterTest {
    @Test
    void failedVerificationUsesUnifiedSafeError() throws Exception {
        var auth = mock(AuthService.class);
        when(auth.accessSession("test-token"))
                .thenThrow(new DataAccessResourceFailureException("private database details"));
        var filter = new BearerAuthenticationFilter(auth, new HttpErrorWriter(new ObjectMapper()));
        var request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer test-token");
        var response = new MockHttpServletResponse();
        new RequestIdFilter()
                .doFilter(
                        request,
                        response,
                        (req, res) ->
                                filter.doFilter(
                                        req,
                                        res,
                                        (r, s) -> {
                                            throw new AssertionError("故障时不应进入业务链");
                                        }));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentAsString())
                .contains("SERVICE_UNAVAILABLE", response.getHeader("X-Request-Id"))
                .doesNotContain("test-token", "private database details");
    }
}
