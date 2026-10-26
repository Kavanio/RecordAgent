package butvan.agent.agents.session;

import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptTurnIdentityTest {
    @TempDir Path directory;
    @Test void suppliedTurnIsDurableAndCannotBeAppendedAgain() {
        var storage = new AgentStorageProperties(directory);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var transcript = new TranscriptService(storage, mapper);
        String turn = UUID.randomUUID().toString();
        assertEquals(turn, transcript.appendUserMessageWithTurnId("session", "input", turn));
        assertEquals(turn, new TranscriptService(storage, mapper).list("session").getFirst().turnId());
        assertThrows(IllegalArgumentException.class, () -> transcript.appendUserMessageWithTurnId("session", "input", turn));
        assertEquals(1, transcript.list("session").size());
    }
    @Test void desktopCallerStillGetsADistinctTurnForEveryMessage() {
        var transcript = new TranscriptService(new AgentStorageProperties(directory), new ObjectMapper().findAndRegisterModules());
        assertNotEquals(transcript.appendUserMessage("session", "one"), transcript.appendUserMessage("session", "two"));
    }
}
