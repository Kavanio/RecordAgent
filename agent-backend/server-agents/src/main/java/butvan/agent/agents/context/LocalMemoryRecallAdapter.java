package butvan.agent.agents.context;

import butvan.agent.agents.memory.MemoryRecallPort;
import butvan.agent.agents.storage.AgentStorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.Stream;

/** 既有本地关键词检索 Adapter；保持文件范围、排序、去重及敏感过滤，预算留在组装器。 */
@Slf4j
@RequiredArgsConstructor
public class LocalMemoryRecallAdapter implements MemoryRecallPort {
    private final AgentStorageProperties storageProperties;
    private static final String MEMORY_FILE = "MEMORY.md";
    private static final int MAX_MEMORY_FILES = 128;
    private static final long MAX_FILE_BYTES = 1_000_000;
    private static final Pattern HEADING_PATTERN = Pattern.compile("(?m)^#{1,4}\\s+(.+?)\\s*$");
    private static final Pattern ASCII_WORD = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern HAN_SEQUENCE = Pattern.compile("[\\p{IsHan}]{2,}");

    @Override public Result recall(Request request) {
        Path workspace = storageProperties.userWorkspaceDirectory(request.owner());
        Set<String> queryTerms = terms(request.query());
        if (queryTerms.isEmpty()) return Result.empty(Status.EMPTY);
        List<ScoredChunk> candidates = new ArrayList<>();
        for (Path path : memoryFiles(workspace)) {
            if (request.cancelled().getAsBoolean()) return Result.empty(Status.CANCELLED);
            String original = readSmallFile(path);
            if (original == null || original.isBlank()) continue;
            String content = path.getFileName().toString().equals(MEMORY_FILE) ? removeSection(original, "User Profile") : original;
            // 旧框架自动摘要未经事实核对，不再当作用户经历自动注入；原文件保留供显式核查。
            content = content.replaceAll("(?ms)^## Memory Flush[^\\r\\n]*\\R.*?(?=^## |\\z)", "");
            for (String chunk : chunks(content)) {
                if (PersonalContextSafety.containsSensitive(chunk)) continue;
                double value = score(chunk, request.query(), queryTerms);
                if (value > 0) candidates.add(new ScoredChunk(path, relativeSource(workspace, path), chunk, original, value));
            }
        }
        candidates.sort(Comparator.comparingDouble(ScoredChunk::score).reversed().thenComparing(ScoredChunk::source));
        List<Fragment> fragments = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var candidate : candidates) {
            if (fragments.size() >= request.topK()) break;
            if (seen.add(normalize(candidate.content()))) fragments.add(new Fragment(candidate.source(), candidate.content(),
                    () -> candidate.original().equals(readSmallFile(candidate.path()))));
        }
        return new Result(fragments.isEmpty() ? Status.EMPTY : Status.SUCCESS, fragments);
    }
    private List<Path> memoryFiles(Path userWorkspace) {
        List<Path> paths = new ArrayList<>();
        Path summary = userWorkspace.resolve(MEMORY_FILE);
        if (Files.isRegularFile(summary)) paths.add(summary);
        Path dailyDirectory = userWorkspace.resolve("memory");
        if (!Files.isDirectory(dailyDirectory) || Files.isSymbolicLink(dailyDirectory)) return paths;
        try (Stream<Path> stream = Files.list(dailyDirectory)) {
            stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .limit(MAX_MEMORY_FILES - paths.size())
                    .forEach(paths::add);
        } catch (IOException exception) {
            log.warn("读取记忆目录失败：directory={}", dailyDirectory, exception);
        }
        return paths;
    }

    private List<String> chunks(String content) {
        List<String> result = new ArrayList<>();
        Matcher matcher = HEADING_PATTERN.matcher(content);
        int start = 0;
        while (matcher.find()) {
            if (matcher.start() > start) addParagraphChunks(result, content.substring(start, matcher.start()));
            start = matcher.start();
        }
        if (start < content.length()) addParagraphChunks(result, content.substring(start));
        return result;
    }

    private void addParagraphChunks(List<String> target, String section) {
        for (String paragraph : section.split("\\R\\s*\\R")) {
            String value = paragraph.strip();
            if (!value.isBlank() && !value.startsWith("## User Profile")) target.add(value);
        }
    }

    private double score(String chunk, String query, Set<String> queryTerms) {
        String normalizedChunk = normalize(chunk);
        double score = normalizedChunk.contains(normalize(query)) ? 8 : 0;
        for (String term : queryTerms) {
            if (normalizedChunk.contains(term)) score += Math.min(4, term.codePointCount(0, term.length()));
        }
        return score;
    }

    private Set<String> terms(String text) {
        String normalized = normalize(text);
        Set<String> result = new LinkedHashSet<>();
        Matcher ascii = ASCII_WORD.matcher(normalized);
        while (ascii.find()) {
            String word = ascii.group();
            if (word.length() >= 2) result.add(word);
        }
        Matcher han = HAN_SEQUENCE.matcher(normalized);
        while (han.find()) {
            String sequence = han.group();
            int[] points = sequence.codePoints().toArray();
            for (int size = 2; size <= Math.min(4, points.length); size++) {
                for (int index = 0; index + size <= points.length; index++) {
                    result.add(new String(points, index, size));
                }
            }
        }
        return result;
    }

    private String readSmallFile(Path path) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path)
                    || Files.size(path) > MAX_FILE_BYTES) return null;
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            log.warn("读取上下文文件失败：path={}", path, exception);
            return null;
        }
    }

    private String removeSection(String markdown, String heading) {
        Pattern sectionPattern = Pattern.compile(
                "(?ms)^##\\s+" + Pattern.quote(heading) + "\\s*$\\R.*?(?=^##\\s+|\\z)");
        return sectionPattern.matcher(markdown).replaceFirst("");
    }

    private String relativeSource(Path root, Path path) {
        return root.relativize(path).toString().replace('\\', '/');
    }
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }
    private record ScoredChunk(Path path, String source, String content, String original, double score) { }
}
