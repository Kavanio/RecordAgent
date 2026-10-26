package butvan.agent.feishu.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.List;
import static butvan.agent.feishu.service.FeishuTaskStore.*;
import static butvan.agent.feishu.service.FeishuTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

class FeishuTaskStoreTest {
    @TempDir Path directory;

    @Test void acceptanceIsDurableAndDuplicateDoesNotExecuteAgain() throws Exception {
        Task original = task("task", "chat", Execution.QUEUED, 100);
        try (FeishuTaskStore store = new FeishuTaskStore(directory)) {
            assertEquals(0, store.accept(original, 5, 100));
            store.bind("chat", "session");
            assertEquals(-1, store.accept(original, 5, 100));
        }
        try (FeishuTaskStore reopened = new FeishuTaskStore(directory)) {
            assertEquals(original, reopened.task("task"));
            assertEquals("session", reopened.snapshot().bindings().get("chat"));
            assertEquals(-1, reopened.accept(original, 5, 100));
        }
    }

    @Test void sameStateDirectoryHasOnlyOneWriter() throws Exception {
        try (FeishuTaskStore first = new FeishuTaskStore(directory)) {
            assertThrows(IOException.class, () -> new FeishuTaskStore(directory));
            assertTrue(first.healthy());
        }
        try (FeishuTaskStore second = new FeishuTaskStore(directory)) { assertTrue(second.healthy()); }
    }

    @Test void corruptStateIsNotSilentlyReset() throws Exception {
        Files.writeString(directory.resolve("state.json"), "{broken");
        assertThrows(IOException.class, () -> new FeishuTaskStore(directory));
        assertEquals("{broken", Files.readString(directory.resolve("state.json")));
    }

    @Test void failedWriteFreezesStoreAndRetainsPriorAcceptedSnapshot() throws Exception {
        try (FeishuTaskStore store = new FeishuTaskStore(directory)) {
            store.accept(task("first", "chat", Execution.QUEUED, 100), 5, 100);
            Files.createDirectory(directory.resolve("state.json.tmp"));
            assertThrows(IllegalStateException.class, () -> store.accept(task("second", "chat", Execution.QUEUED, 100), 5, 100));
            assertFalse(store.healthy());
            assertThrows(IllegalStateException.class, store::snapshot);
        }
        try (FeishuTaskStore store = new FeishuTaskStore(directory)) {
            assertNotNull(store.task("first")); assertNull(store.task("second"));
        }
    }

    @Test void capacityDoesNotPreventControlCommands() throws Exception {
        try (FeishuTaskStore store = new FeishuTaskStore(directory)) {
            store.accept(task("first", "chat", Execution.QUEUED, 100), 1, 100);
            assertThrows(IllegalStateException.class, () -> store.accept(task("second", "chat", Execution.QUEUED, 100), 1, 100));
            assertDoesNotThrow(() -> store.accept(task("stop", "chat", Execution.SUCCEEDED, 100), 1, 100));
        }
    }

    @Test void pendingUnknownRepliesAreNotPruned() throws Exception {
        try (FeishuTaskStore store = new FeishuTaskStore(directory)) {
            Task unknown = task("old", "chat", Execution.SUCCEEDED, 100).toBuilder().finishedAt(200)
                    .parts(List.of(Part.builder().id("part").text("result").status(Delivery.UNKNOWN).build())).build();
            store.accept(unknown, 5, 100);
            store.accept(task("new", "chat", Execution.QUEUED, 100), 5, 10L * 24 * 60 * 60 * 1000);
            assertNotNull(store.task("old"));
        }
    }
}
