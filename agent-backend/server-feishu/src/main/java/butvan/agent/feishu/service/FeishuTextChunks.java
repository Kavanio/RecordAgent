package butvan.agent.feishu.service;

import com.lark.oapi.core.utils.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有界 JSON 负载分段；只插入分段序号与必要的围栏代码块闭合／重开，不删字、不 trim，
 * 也不切断 UTF-16 代理对。
 */
public final class FeishuTextChunks {
    private static final int MAX_JSON_BYTES = 8_000;
    private static final int LABEL_RESERVE_BYTES = 128;
    /** 行首最多三个空格的围栏标记；第二组是可选语言标记或闭合用的纯围栏。 */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private FeishuTextChunks() { }

    /** 空白回复给出明确反馈，其余正文分段后可以去除序号无损重组。 */
    public static List<String> split(String text) {
        if (text == null || text.isBlank()) return List.of("任务已结束，但没有返回可展示的文本。");
        if (bytes(text) <= MAX_JSON_BYTES) return List.of(text);
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int low = 1;
            int high = Math.min(7_000, text.codePointCount(start, Math.min(text.length(), start + 8_000)));
            int best = 0;
            while (low <= high) {
                int count = (low + high) >>> 1;
                int end = text.offsetByCodePoints(start, count);
                if (bytes(text.substring(start, end)) <= MAX_JSON_BYTES - LABEL_RESERVE_BYTES) {
                    best = end; low = count + 1;
                } else high = count - 1;
            }
            if (best <= start) throw new IllegalStateException("文本分段失败");
            int newline = text.lastIndexOf('\n', best - 1);
            if (newline > start + (best - start) / 2) best = newline + 1;
            pieces.add(text.substring(start, best));
            start = best;
        }
        balanceFences(pieces);
        List<String> labeled = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) labeled.add("【" + (i + 1) + "/" + pieces.size() + "】\n" + pieces.get(i));
        return List.copyOf(labeled);
    }

    /**
     * 分段可能把围栏代码块切成两半：上一段补闭合围栏，下一段用同一语言标记重开。
     * 否则每段都会把后续行渲染成代码或把闭合围栏当正文。仅在正文确实跨段时生效。
     */
    private static void balanceFences(List<String> pieces) {
        String reopen = null;
        for (int i = 0; i < pieces.size(); i++) {
            String piece = pieces.get(i);
            if (reopen != null) piece = reopen + "\n" + piece;
            String open = unclosedFence(piece);
            if (open != null) piece = piece + (piece.endsWith("\n") ? "" : "\n") + fenceRun(open);
            pieces.set(i, piece);
            reopen = open;
        }
    }

    /** 返回文末仍未闭合的围栏起始行（含语言标记），全部闭合时返回 null。 */
    private static String unclosedFence(String piece) {
        String openLine = null;
        char openChar = 0;
        int openLength = 0;
        for (String line : piece.split("\n", -1)) {
            Matcher matcher = FENCE.matcher(line);
            if (!matcher.matches()) continue;
            String fence = matcher.group(1);
            boolean closing = matcher.group(2).isBlank();
            if (openLine == null) {
                openLine = line; openChar = fence.charAt(0); openLength = fence.length();
            } else if (closing && fence.charAt(0) == openChar && fence.length() >= openLength) {
                openLine = null;
            }
        }
        return openLine;
    }

    private static String fenceRun(String openLine) {
        Matcher matcher = FENCE.matcher(openLine);
        return matcher.matches() ? matcher.group(1) : "```";
    }

    static int bytes(String text) {
        return Jsons.DEFAULT.toJson(Map.of("text", text)).getBytes(StandardCharsets.UTF_8).length;
    }
}
