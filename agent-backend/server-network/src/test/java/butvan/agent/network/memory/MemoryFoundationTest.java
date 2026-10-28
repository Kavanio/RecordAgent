package butvan.agent.network.memory;

import butvan.agent.agents.identity.CurrentUserProvider;
import butvan.agent.agents.memory.MemorySourceRegistry;
import butvan.agent.agents.memory.MemorySourceRegistry.*;
import butvan.agent.network.memory.client.MemoryRemoteGateway;
import butvan.agent.network.memory.service.MemoryRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** 经公开应用入口验证持久化、资格冻结及隔离，不以私有 SQL 作为测试契约。 */
class MemoryFoundationTest {
    @TempDir Path directory;

    @Test void defaultDisabledCannotAuthorizeAnUnintegratedRecipient() {
        var f = new MemoryFixture(directory);
        var disabled = f.service(MemoryRemoteGateway.disabled(), f.user, "test");
        assertFalse(disabled.settings().autoSave());
        assertFalse(disabled.settings().autoRecall());
        assertFalse(disabled.grant().active());
        assertTrue(disabled.capture(f.source()).isEmpty());
        assertEquals(409, assertThrows(MemoryRequestException.class,
                () -> disabled.authorize(f.authorization(0, "not-integrated"))).getCode());
        assertTrue(MemorySourceRegistry.disabled().capture(f.source()).isEmpty());
        assertFalse(MemorySourceRegistry.disabled().isCurrent(null));
        assertEquals(0, f.remote.deletions.get());
    }

    @Test void receiptIsStableAcrossRestartAndSeparateFromAnotherOwnerEnvironmentOrBackend() {
        var f = new MemoryFixture(directory);
        f.enabled();
        var source = f.source();
        Receipt receipt = f.service.capture(source).orElseThrow();
        assertEquals(receipt, f.service.capture(source).orElseThrow());
        assertTrue(f.service(f.remote, f.user, "test").isCurrent(receipt));
        assertFalse(f.service(f.remote, f.user, "production").isCurrent(receipt));
        var another = new CurrentUserProvider() { public String currentUserId() { return "another"; } };
        assertFalse(f.service(f.remote, another, "test").isCurrent(receipt));
        assertThrows(MemoryRequestException.class, () -> f.service(f.remote, another, "test").capture(source));
        var secondBackend = new MemoryFixture(directory.resolve("other-backend"));
        secondBackend.enabled();
        assertNotEquals(receipt.spaceId(), secondBackend.service.capture(secondBackend.source()).orElseThrow().spaceId());
    }

    @Test void currentGrantCannotBackfillEarlierOrUnapprovedSourcesAndChangedPayloadCannotReuseReceipt() {
        var f = new MemoryFixture(directory);
        f.enabled();
        Source source = f.source();
        assertTrue(f.service.capture(new Source(source.owner(), source.sessionId(), source.turnId(), source.messageId(),
                source.occurredAt().minusSeconds(1), source.channel(), source.contentHash(), 0)).isEmpty());
        assertTrue(f.service.capture(new Source(source.owner(), source.sessionId(), source.turnId(), source.messageId(),
                source.occurredAt(), Channel.FEISHU, source.contentHash(), 0)).isEmpty());
        f.service.capture(source).orElseThrow();
        assertTrue(f.service.capture(new Source(source.owner(), source.sessionId(), source.turnId(), source.messageId(),
                source.occurredAt(), source.channel(), "b".repeat(64), 0)).isEmpty());
    }

    @Test void revokeReauthorizeAndRecipientChangesCannotRestoreOldSources() {
        var f = new MemoryFixture(directory);
        f.enabled();
        var source = f.source();
        var receipt = f.service.capture(source).orElseThrow();
        var revoked = f.service.revoke(1, "revoke");
        assertEquals(revoked, f.service.revoke(1, "revoke"));
        assertFalse(f.service.isCurrent(receipt));
        f.service.authorize(f.authorization(2, "grant-again"));
        f.service.saveSettings(f.service.settings().version(), true, true);
        assertFalse(f.service.isCurrent(receipt));
        assertTrue(f.service.capture(source).isEmpty());
        var fresh = f.service.capture(f.source()).orElseThrow();
        assertTrue(f.service.isCurrent(fresh));
        f.remote.fingerprint = "different-processing-chain";
        assertFalse(f.service.isCurrent(fresh));
        assertTrue(f.service.capture(f.source()).isEmpty());
    }

    @Test void optimisticVersionsAndIdempotentRequestsDoNotOverrideLaterDecisions() {
        var f = new MemoryFixture(directory);
        var input = f.authorization(0, "grant");
        var result = f.service.authorize(input);
        assertEquals(result, f.service.authorize(input));
        assertEquals(409, assertThrows(MemoryRequestException.class,
                () -> f.service.authorize(f.authorization(0, "other"))).getCode());
        assertEquals(409, assertThrows(MemoryRequestException.class, () -> f.service.revoke(1, "grant")).getCode());
        f.service.saveSettings(0, true, false);
        assertEquals(409, assertThrows(MemoryRequestException.class, () -> f.service.saveSettings(0, false, true)).getCode());
        f.service.revoke(1, "revoke");
        assertEquals(result, f.service.authorize(input));
        assertFalse(f.service.grant().active());
    }
}
