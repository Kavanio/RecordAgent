package butvan.agent.network.push;

import butvan.agent.network.push.IosPushModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 零外部依赖的 Apple APNs HTTP/2 客户端（使用 Java 21 原生 HttpClient 与 ECDSA P-256 ES256 签名）。
 */
@Slf4j
@Component
public class ApnsClient {
    private static final String SANDBOX_HOST = "https://api.sandbox.push.apple.com";
    private static final String PRODUCTION_HOST = "https://api.push.apple.com";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(45);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<String, CachedJwt> jwtCache = new ConcurrentHashMap<>();

    public ApnsClient() {
        this(
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_2)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build(),
                new ObjectMapper().findAndRegisterModules(),
                Clock.systemUTC()
        );
    }

    ApnsClient(HttpClient httpClient, ObjectMapper objectMapper, Clock clock) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public record DeliveryOutcome(boolean success, boolean invalidToken, int statusCode, String summary) {}

    /** 向指定 iOS 设备推送一条告警通知，绝不记录或回显私钥与完整设备令牌。 */
    public DeliveryOutcome send(ApnsConfig config, DeviceRegistration device, IosNotice notice) {
        if (config == null || !config.enabled() || !config.configured()) {
            return new DeliveryOutcome(false, false, 0, "APNs 未启用或凭据不完整");
        }
        if (device == null || !device.hasApnsToken()) {
            return new DeliveryOutcome(false, false, 0, "设备缺少 APNs Token");
        }
        try {
            String jwt = resolveJwt(config);
            String host = resolveHost(config, device);
            URI uri = URI.create(host + "/3/device/" + device.deviceToken());
            String payload = buildPayload(notice);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("authorization", "bearer " + jwt)
                    .header("apns-topic", config.bundleId())
                    .header("apns-push-type", "alert")
                    .header("apns-priority", "10")
                    .header("apns-collapse-id", notice.id())
                    .header("content-type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 200) {
                return new DeliveryOutcome(true, false, 200, "SENT");
            }
            String reason = extractReason(response.body());
            boolean invalid = status == 410
                    || (status == 400 && ("BadDeviceToken".equals(reason) || "DeviceTokenNotForTopic".equals(reason)));
            log.warn("APNs 推送未成功：status={}, reason={}, deviceId={}", status, reason, device.deviceId());
            return new DeliveryOutcome(false, invalid, status, reason.isBlank() ? "HTTP_" + status : reason);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new DeliveryOutcome(false, false, 0, "INTERRUPTED");
        } catch (Exception e) {
            log.warn("APNs 推送异常：type={}, deviceId={}", e.getClass().getSimpleName(), device.deviceId());
            return new DeliveryOutcome(false, false, 0, e.getClass().getSimpleName());
        }
    }

    /** 生成符合 Apple APNs 规范的 ES256 Provider Authentication JWT（供测试与内部复用）。 */
    String generateJwt(ApnsConfig config, Instant issuedAt) throws Exception {
        String headerJson = objectMapper.writeValueAsString(Map.of("alg", "ES256", "kid", config.keyId()));
        String claimsJson = objectMapper.writeValueAsString(Map.of(
                "iss", config.teamId(),
                "iat", issuedAt.getEpochSecond()
        ));
        Base64.Encoder urlEncoder = Base64.getUrlEncoder().withoutPadding();
        String unsigned = urlEncoder.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8))
                + "."
                + urlEncoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));

        PrivateKey privateKey = parseEcPrivateKey(config.privateKeyPem());
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(privateKey);
        signer.update(unsigned.getBytes(StandardCharsets.UTF_8));
        byte[] rawSignature = signer.sign();
        return unsigned + "." + urlEncoder.encodeToString(rawSignature);
    }

    String buildPayload(IosNotice notice) throws Exception {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("title", truncate(notice.title(), 80));
        alert.put("body", truncate(notice.body(), 240));

        Map<String, Object> aps = new LinkedHashMap<>();
        aps.put("alert", alert);
        aps.put("sound", "default");
        aps.put("interruption-level", "active");

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("noticeId", notice.id());
        meta.put("category", notice.category().name());
        meta.put("kind", notice.kind());
        meta.put("sessionId", notice.sessionId());
        meta.put("routeTarget", notice.routeTarget());

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("aps", aps);
        root.put("recordAgent", meta);
        return objectMapper.writeValueAsString(root);
    }

    private String resolveJwt(ApnsConfig config) throws Exception {
        String cacheKey = config.teamId() + "|" + config.keyId() + "|" + config.privateKeyPem().hashCode();
        Instant now = clock.instant();
        CachedJwt cached = jwtCache.get(cacheKey);
        if (cached != null && now.isBefore(cached.issuedAt().plus(TOKEN_TTL))) {
            return cached.token();
        }
        String fresh = generateJwt(config, now);
        jwtCache.put(cacheKey, new CachedJwt(fresh, now));
        return fresh;
    }

    private String resolveHost(ApnsConfig config, DeviceRegistration device) {
        if (device.environment() == ApnsEnvironment.PRODUCTION && !config.sandbox()) {
            return PRODUCTION_HOST;
        }
        return SANDBOX_HOST;
    }

    private PrivateKey parseEcPrivateKey(String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.getDecoder().decode(normalized);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        return KeyFactory.getInstance("EC").generatePrivate(spec);
    }

    private String extractReason(String body) {
        if (body == null || body.isBlank()) return "";
        try {
            var node = objectMapper.readTree(body);
            var reasonNode = node.get("reason");
            return reasonNode == null ? "" : reasonNode.asText("");
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String trimmed = text.strip();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max - 1) + "…";
    }

    private record CachedJwt(String token, Instant issuedAt) {}
}
