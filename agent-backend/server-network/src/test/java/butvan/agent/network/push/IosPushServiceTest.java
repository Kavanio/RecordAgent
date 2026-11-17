package butvan.agent.network.push;

import butvan.agent.agents.reset.ResetBarrier;
import butvan.agent.agents.storage.AgentStorageProperties;
import butvan.agent.network.push.IosPushDtos.*;
import butvan.agent.network.push.IosPushModels.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IosPushServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void storePreservesPrivateKeyWhenBlankAndMasksDtos() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(tempDir);
        IosPushStore store = new IosPushStore(storage);
        IosPushStreamService stream = new IosPushStreamService();
        ApnsClient apns = mock(ApnsClient.class);
        Clock fixed = Clock.fixed(Instant.parse("2026-11-17T10:00:00Z"), ZoneOffset.UTC);
        IosPushService service = new IosPushService(store, apns, stream, fixed, new ResetBarrier(storage),
                false, "", "", "com.butvan.recordagent", "", true);

        String pem = generateSyntheticEcPem();
        IosPushStatusResponse saved = service.saveApnsConfig("owner", new SaveApnsConfigRequest(
                true, "7JWU292AKK", "KEY12345", "com.butvan.recordagent", pem, true));
        assertTrue(saved.apnsConfigured());
        assertTrue(saved.apnsEnabled());

        String rawFile = Files.readString(storage.getIosPushFile());
        assertTrue(rawFile.contains("BEGIN PRIVATE KEY"), "持久化文件必须保存私钥以供重启恢复");

        // 第二次更新私钥留空应保留旧私钥，且 status DTO 不泄漏私钥与完整 Token
        IosPushStatusResponse updated = service.saveApnsConfig("owner", new SaveApnsConfigRequest(
                true, "7JWU292AKK", "KEY12345", "com.butvan.recordagent", "", false));
        assertTrue(updated.apnsConfigured());
        assertFalse(updated.sandbox());

        IosPushStatusResponse reg = service.registerDevice("owner", new RegisterDeviceRequest(
                "dev-1", "AABBCCDDEEFF00112233445566778899", "sandbox", "1.0", true, false, true));
        assertEquals(1, reg.registeredDeviceCount());
        assertEquals(1, reg.apnsTokenDeviceCount());
        assertEquals("aabbccdd…", reg.devices().getFirst().tokenPrefix());
    }

    @Test
    void apnsClientGeneratesValidEs256JwtAndPayload() throws Exception {
        String pem = generateSyntheticEcPem();
        ApnsClient client = new ApnsClient();
        ApnsConfig config = new ApnsConfig(true, "7JWU292AKK", "ABC123DEFG", "com.butvan.recordagent", pem, true);
        Instant now = Instant.parse("2026-11-17T10:00:00Z");

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        // 验证生成的 JWT 格式、三段结构与签名可解
        String jwt = client.generateJwt(config, now);
        String[] parts = jwt.split("\\.");
        assertEquals(3, parts.length);
        byte[] sig = Base64.getUrlDecoder().decode(parts[2]);
        assertEquals(64, sig.length, "ES256 P1363 签名固定为 64 字节");

        String header = new String(Base64.getUrlDecoder().decode(parts[0]));
        assertTrue(header.contains("\"alg\":\"ES256\""));
        assertTrue(header.contains("\"kid\":\"ABC123DEFG\""));

        IosNotice notice = new IosNotice("n-1", "owner", PushCategory.COMPANION, "COMPANION_OUTREACH",
                "Agent 主动问候", "今天过得怎么样？", "sess-1", "chat", now, false, "PENDING");
        var json = new ObjectMapper().readTree(client.buildPayload(notice));
        assertEquals("Agent 主动问候", json.get("aps").get("alert").get("title").asText());
        assertEquals("sess-1", json.get("recordAgent").get("sessionId").asText());
        assertEquals("chat", json.get("recordAgent").get("routeTarget").asText());
    }

    @Test
    void categorySwitchesFilterNoticesAndInvalidTokenIsCleared() throws Exception {
        AgentStorageProperties storage = new AgentStorageProperties(tempDir);
        IosPushStore store = new IosPushStore(storage);
        IosPushStreamService stream = new IosPushStreamService();
        ApnsClient apns = mock(ApnsClient.class);
        when(apns.send(any(), any(), any()))
                .thenReturn(new ApnsClient.DeliveryOutcome(false, true, 410, "Unregistered"));

        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-17T10:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        ResetBarrier barrier = new ResetBarrier(storage);
        IosPushService service = new IosPushService(store, apns, stream, clock, barrier,
                true, "7JWU292AKK", "KEY1", "com.butvan.recordagent", generateSyntheticEcPem(), true);

        // 只开启 COMPANION 与 AUTOMATION，关闭 SIGNAL
        service.registerDevice("owner", new RegisterDeviceRequest(
                "dev-1", "00112233445566778899aabbccddeeff", "sandbox", "1.0", true, false, true));

        assertNull(service.publishNotice("owner", PushCategory.SIGNAL, "WEATHER_ALERT",
                "天气提醒", "下午有雨", "sess-1", "chat"), "设备关闭信号推送时应跳过");

        TestPushResponse test = service.sendTestNotice("owner", new TestPushRequest("测试", "内容", "COMPANION"));
        assertEquals(1, test.attemptedDevices());
        assertEquals(0, test.apnsSentCount());
        // 410 Unregistered 后应自动清理失效 APNs Token，但保留设备偏好记录
        IosPushStatusResponse after = service.status("owner");
        assertEquals(1, after.registeredDeviceCount());
        assertEquals(0, after.apnsTokenDeviceCount());

        // 确认通知与重置清理
        service.acknowledgeNotice("owner", test.notice().id());
        assertEquals(0, service.notices("owner", false).size());

        service.publishNotice("owner", PushCategory.AUTOMATION, "AUTOMATION_RUN",
                "日报", "日报已生成", null, "tasks");
        assertEquals(1, service.notices("owner", false).size());
        new IosPushResetParticipant(store, stream).clean("owner");
        assertEquals(0, service.notices("owner", true).size());
        assertEquals(1, service.status("owner").registeredDeviceCount());
    }

    private static String generateSyntheticEcPem() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair kp = kpg.generateKeyPair();
        // 验证私钥可用于 SHA256withECDSA
        Signature.getInstance("SHA256withECDSA").initSign(kp.getPrivate());
        String b64 = Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
        return "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----";
    }
}
