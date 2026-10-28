package butvan.agent.agents.reset;

import butvan.agent.agents.storage.AgentStorageProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class ResetBarrierTest {
    @TempDir Path directory;

    @Test void persistedBarrierRejectsNewWritersAndDoesNotReleaseWhileOldWriterLives() {
        var storage = new AgentStorageProperties(directory);
        var barrier = new ResetBarrier(storage);
        var old = barrier.enter();
        barrier.begin("reset-1", "request-1", "local-default");
        assertThrows(ResetBlockedException.class, barrier::enter);
        assertFalse(barrier.awaitIdle(Duration.ofMillis(1)));
        assertThrows(IllegalStateException.class, barrier::complete);
        assertTrue(new ResetBarrier(storage).blocked());
        old.close();
        assertTrue(barrier.awaitIdle(Duration.ofMillis(10)));
        barrier.complete();
        assertFalse(new ResetBarrier(storage).blocked());
        assertFalse(barrier.isCurrent(old.generation()));
    }
}
