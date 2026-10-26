package butvan.agent.feishu.service;

import com.lark.oapi.core.utils.Jsons;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 有界 JSON 负载分段；只插入分段序号，不删字、不 trim，也不切断 UTF-16 代理对。 */
public final class FeishuTextChunks {
    private static final int MAX_JSON_BYTES = 8_000;
    private static final int LABEL_RESERVE_BYTES = 128;
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
        List<String> labeled = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) labeled.add("【" + (i + 1) + "/" + pieces.size() + "】\n" + pieces.get(i));
        return List.copyOf(labeled);
    }

    static int bytes(String text) {
        return Jsons.DEFAULT.toJson(Map.of("text", text)).getBytes(StandardCharsets.UTF_8).length;
    }
}
