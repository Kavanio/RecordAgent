package butvan.agent.network.controller;

import butvan.agent.network.chat.dto.AgentChatRequest;
import butvan.agent.network.common.Result;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

/** 仅限制聊天 JSON：在 Jackson 分配大字符串前拦截，multipart 配置不适用于此入口。 */
@RestControllerAdvice
public class ChatRequestSizeAdvice extends RequestBodyAdviceAdapter {
    /** 12 MiB 原始图片对应 16 MiB Base64，另留 1 MiB 给文本和 JSON 包装。 */
    public static final int MAX_REQUEST_BYTES = 17 * 1024 * 1024;

    @Override
    public boolean supports(MethodParameter parameter, Type targetType,
                            Class<? extends HttpMessageConverter<?>> converterType) {
        return targetType == AgentChatRequest.class;
    }

    @Override
    public HttpInputMessage beforeBodyRead(HttpInputMessage message, MethodParameter parameter,
                                          Type targetType, Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        if (message.getHeaders().getContentLength() > MAX_REQUEST_BYTES) throw new ChatRequestTooLarge();
        InputStream limited = new FilterInputStream(message.getBody()) {
            private int consumed;

            private void count(int size) {
                if (size > 0 && (consumed += size) > MAX_REQUEST_BYTES) throw new ChatRequestTooLarge();
            }

            @Override public int read() throws IOException {
                int value = in.read();
                count(value < 0 ? 0 : 1);
                return value;
            }

            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                int size = in.read(bytes, offset, Math.min(length, MAX_REQUEST_BYTES - consumed + 1));
                count(size);
                return size;
            }

            @Override public long skip(long count) throws IOException {
                long skipped = in.skip(Math.min(count, MAX_REQUEST_BYTES - consumed + 1L));
                count((int) skipped);
                return skipped;
            }
        };
        return new HttpInputMessage() {
            @Override public InputStream getBody() { return limited; }
            @Override public HttpHeaders getHeaders() { return message.getHeaders(); }
        };
    }

    /** 不输出原始请求或图片，使用明确 HTTP 413 而不是通用模型失败。 */
    @ExceptionHandler(ChatRequestTooLarge.class)
    public ResponseEntity<Result<Void>> tooLarge() {
        return ResponseEntity.status(413).body(Result.error(413, "聊天请求过大，每轮图片总大小不能超过 12 MiB"));
    }

    /** 仅用于请求体限流，避免把输入流或 JSON 内容带入错误日志。 */
    public static final class ChatRequestTooLarge extends RuntimeException {
        public ChatRequestTooLarge() { super("聊天请求超过 17 MiB 上限"); }
    }
}
