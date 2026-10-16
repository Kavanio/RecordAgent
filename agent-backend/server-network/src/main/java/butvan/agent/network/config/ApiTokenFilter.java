package butvan.agent.network.config;

import jakarta.annotation.PostConstruct;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** 远程单用户 API 令牌边界；本地开发模式可保持兼容，远程部署必须显式要求令牌。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class ApiTokenFilter extends OncePerRequestFilter {

    @Value("${butvan.api.access-token:}")
    private String configuredToken;

    @Value("${butvan.api.require-token:false}")
    private boolean requireToken;

    /** 强制模式下拒绝空令牌配置，避免误以为服务器已受保护。 */
    @PostConstruct
    public void validateConfiguration() {
        if (requireToken && (configuredToken == null || configuredToken.trim().length() < 32)) {
            throw new IllegalStateException("BUTVAN_API_REQUIRE_TOKEN=true 时必须配置至少 32 个字符的 BUTVAN_API_TOKEN");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                || !(path.startsWith("/agent/") || path.startsWith("/api/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String expected = configuredToken == null ? "" : configuredToken.trim();
        if (expected.isEmpty() && !requireToken) {
            chain.doFilter(request, response);
            return;
        }

        String presented = bearerToken(request);
        if (presented == null && isNativeResourceRequest(request)) {
            // EventSource 和原生 img/download 不能设置 Authorization 头，只允许这些只读 GET 使用查询令牌。
            presented = request.getParameter("access_token");
        }
        if (presented == null || !MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"API Token 无效或缺失\",\"data\":null}");
            return;
        }
        chain.doFilter(request, response);
    }

    private String bearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return null;
        return authorization.substring(7).trim();
    }

    private boolean isNativeResourceRequest(HttpServletRequest request) {
        if (!"GET".equalsIgnoreCase(request.getMethod())) return false;
        String path = request.getRequestURI();
        return path.equals("/agent/automations/stream")
                || path.equals("/agent/study-sessions/stream")
                || path.equals("/api/tasks/stream")
                || path.equals("/agent/account/avatar/content")
                || (path.startsWith("/agent/records/") && path.contains("/attachments/") && path.endsWith("/content"));
    }
}
