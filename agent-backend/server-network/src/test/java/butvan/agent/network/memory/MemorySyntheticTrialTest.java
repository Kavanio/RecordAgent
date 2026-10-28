package butvan.agent.network.memory;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 试跑入口的本机隔离回归；不读取外部配置或连接真实服务。 */
class MemorySyntheticTrialTest {
    @TempDir Path directory;

    @Test void preparationAndReopeningKeepTheSameThreeSyntheticSourcesWithoutNetwork() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { calls.incrementAndGet(); exchange.sendResponseHeaders(503, -1); exchange.close(); });
        server.start();
        Path root = directory.resolve("recordagent-synthetic-trial-test");
        URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
        try {
            MemorySyntheticTrial.Report prepared;
            try (var trial = new MemorySyntheticTrial(root, endpoint, "", "synthetic-chain")) {
                prepared = trial.run(MemorySyntheticTrial.Action.PREPARE);
                assertEquals("PREPARED", prepared.status()); assertEquals(3, prepared.operations().size());
                assertTrue(prepared.operations().stream().allMatch(operation -> operation.state().equals("PENDING")));
                assertFalse(prepared.remoteErasureVerified());
            }
            try (var trial = new MemorySyntheticTrial(root, endpoint, "", "synthetic-chain")) {
                assertEquals(prepared.operations(), trial.run(MemorySyntheticTrial.Action.PREPARE).operations());
                assertEquals(prepared.bankId(), trial.run(MemorySyntheticTrial.Action.STATUS).bankId());
                assertEquals(0, calls.get());
                assertEquals("UNAVAILABLE", trial.run(MemorySyntheticTrial.Action.SYNC).status());
                assertEquals(1, calls.get(), "连接检查失败后不得提交合成正文");
                assertEquals(0, trial.run(MemorySyntheticTrial.Action.STATUS).recallUsed());
                var withdrawn = trial.run(MemorySyntheticTrial.Action.WITHDRAW);
                assertEquals("WITHDRAWN_REMOTE_UNVERIFIED", withdrawn.status(), "上游不可用也必须先完成本地撤回");
                assertEquals(3, withdrawn.deletions().size());
                assertTrue(withdrawn.deletions().stream().allMatch(operation -> operation.state().equals("SUCCEEDED")));
            }
        } finally { server.stop(0); }
    }

    @Test void refusesUnmarkedDirectoriesConcurrentUseAndChangedRecipient() throws Exception {
        Path existing = Files.createDirectory(directory.resolve("recordagent-synthetic-trial-existing"));
        Files.writeString(existing.resolve("keep.txt"), "不得改动");
        assertThrows(IllegalArgumentException.class, () -> new MemorySyntheticTrial(existing, URI.create("http://127.0.0.1:9/"), "", "chain"));
        assertEquals("不得改动", Files.readString(existing.resolve("keep.txt")));
        Path root = directory.resolve("recordagent-synthetic-trial-new");
        try (var trial = new MemorySyntheticTrial(root, URI.create("http://127.0.0.1:9/"), "", "chain")) {
            trial.run(MemorySyntheticTrial.Action.PREPARE);
            assertThrows(IllegalStateException.class, () -> new MemorySyntheticTrial(root, URI.create("http://127.0.0.1:9/"), "", "chain"));
        }
        assertThrows(IllegalArgumentException.class, () -> new MemorySyntheticTrial(root, URI.create("http://127.0.0.1:9/"), "", "different-chain"));
    }

    @Test void missingOriginalTextBlocksSendingButDoesNotBlockLocalWithdrawal() throws Exception {
        Path root = directory.resolve("recordagent-synthetic-trial-missing-original");
        try (var trial = new MemorySyntheticTrial(root, URI.create("http://127.0.0.1:9/"), "", "chain")) {
            trial.run(MemorySyntheticTrial.Action.PREPARE);
            try (var files = Files.walk(root)) {
                for (var file : files.filter(path -> path.toString().endsWith(".jsonl")).toList()) Files.delete(file);
            }
            assertThrows(IllegalStateException.class, () -> trial.run(MemorySyntheticTrial.Action.SYNC));
            var withdrawn = trial.run(MemorySyntheticTrial.Action.WITHDRAW);
            assertEquals("WITHDRAWN_REMOTE_UNVERIFIED", withdrawn.status()); assertEquals(3, withdrawn.deletions().size());
            assertTrue(withdrawn.deletions().stream().allMatch(operation -> operation.state().equals("SUCCEEDED")));
        }
    }

    @Test void refusesPublicPlaintextConnectionAndNonTrialDirectoryNames() {
        assertThrows(IllegalArgumentException.class, () -> new MemorySyntheticTrial(directory.resolve("ordinary-data"), URI.create("http://127.0.0.1:9/"), "", "chain"));
        assertThrows(IllegalArgumentException.class, () -> new MemorySyntheticTrial(directory.resolve("recordagent-synthetic-trial-unsafe"), URI.create("http://remote.example.invalid/"), "", "chain"));
    }
}
