package butvan.agent.feishu.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FeishuTextChunksTest {
    @Test void shortReplyIsUntouchedIncludingTailAndNewline() {
        assertEquals(java.util.List.of("完整回答🙂\n"), FeishuTextChunks.split("完整回答🙂\n"));
    }
    @Test void longReplyIsLosslessAndEachSerializedPartFits() {
        String text = ("中文🙂\n<>&='\"\\\n".repeat(7_000)) + "最后一个字！\n";
        var pieces = FeishuTextChunks.split(text);
        assertTrue(pieces.size() > 1);
        String rebuilt = pieces.stream().map(part -> part.substring(part.indexOf('\n') + 1)).collect(java.util.stream.Collectors.joining());
        assertEquals(text, rebuilt);
        for (String part : pieces) {
            assertTrue(FeishuTextChunks.bytes(part) <= 8_000);
            assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
        }
    }
    @Test void unbrokenAsciiAndEmojiParagraphsAreAlsoLossless() {
        String text = "x".repeat(40_000) + "🙂".repeat(20_000) + "尾部";
        assertEquals(text, FeishuTextChunks.split(text).stream()
                .map(part -> part.substring(part.indexOf('\n') + 1)).collect(java.util.stream.Collectors.joining()));
    }
    @Test void fencedCodeBlockSplitAcrossPartsIsClosedAndReopened() {
        String text = "```java\n" + ("int value = 1;\n".repeat(700)) + "```\n结尾\n";
        var pieces = FeishuTextChunks.split(text);
        assertTrue(pieces.size() > 1);
        for (String piece : pieces) {
            long fences = piece.lines().filter(line -> line.startsWith("```")).count();
            assertEquals(0, fences % 2, "每段围栏必须成对，否则后半段会被渲染成代码或把闭合符当正文");
        }
        assertEquals("```java", pieces.get(1).lines().skip(1).findFirst().orElse(""));
        assertEquals("```", pieces.get(0).lines().toList().getLast());
    }
    @Test void emptySuccessIsExplicit() {
        assertFalse(FeishuTextChunks.split("").getFirst().isBlank());
        assertFalse(FeishuTextChunks.split(" \n\t").getFirst().isBlank());
    }
}
