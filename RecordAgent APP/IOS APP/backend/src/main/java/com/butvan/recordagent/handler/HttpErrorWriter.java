package com.butvan.recordagent.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Servlet 过滤器和安全链共用的 HTTP 错误输出，契约与 ControllerAdvice 相同。 */
@Component
@RequiredArgsConstructor
public class HttpErrorWriter {
    private final ObjectMapper mapper;

    /** 使用错误码绑定状态写出安全 JSON，不回显异常或认证头。 */
    public void write(HttpServletResponse response, ApiErrorCode error) throws IOException {
        response.setStatus(error.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        mapper.writeValue(response.getWriter(), error.body());
    }
}
