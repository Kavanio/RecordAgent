package butvan.agent.agents.reset;

import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

/** 白名单调用方使用的路径防护；绝不沿符号链接进入真实项目或附件目录。 */
public final class ResetFiles {
    private ResetFiles() { }
    public static void requireSafe(Path root, Path target) throws IOException {
        Path base = root.toAbsolutePath().normalize();
        Path path = target.toAbsolutePath().normalize();
        if (!path.startsWith(base)) throw new IOException("清理目标越界");
        for (Path cursor = path; cursor != null; cursor = cursor.getParent()) {
            if (Files.isSymbolicLink(cursor)) throw new IOException("清理目标含符号链接");
            if (cursor.equals(base)) break;
        }
    }
    /** 先完整检查子树，再删除；遇到混入的符号链接保持失败屏障。 */
    public static void delete(Path root, Path target) throws IOException {
        requireSafe(root, target);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try (var paths = Files.walk(target)) {
            var ordered = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : ordered) requireSafe(root, path);
            for (Path path : ordered) Files.deleteIfExists(path);
        }
    }
}
