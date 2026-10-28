package butvan.agent.agents.tool;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCapabilityCatalogTest {

    private final ToolCapabilityCatalog catalog = new ToolCapabilityCatalog();

    @Test
    void classifiesKnownToolsAndLeavesUnknownToolsVisible() {
        assertEquals("calendar", catalog.groupFor("calendar_query").orElseThrow().name());
        assertEquals("workspace", catalog.groupFor("read_file").orElseThrow().name());
        assertEquals("methods", catalog.groupFor("method_search").orElseThrow().name());
        assertTrue(catalog.groupFor("diagnostic_ping").isEmpty());
    }

    @Test
    void exposesUniqueGroupNamesAndTheRealMetaToolName() {
        assertEquals(catalog.capabilities().size(), catalog.groupNames().size());
        assertEquals("reset_equipped_tools", ToolCapabilityCatalog.META_TOOL_NAME);
    }
}
