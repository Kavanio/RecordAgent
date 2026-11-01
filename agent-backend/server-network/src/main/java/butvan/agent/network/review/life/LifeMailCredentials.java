package butvan.agent.network.review.life;

import butvan.agent.network.config.database.LocalDatabaseProperties;
import butvan.agent.network.review.life.LifeReviewModels.Settings;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** 邮箱凭据独立保存在数据库同级受保护文件，不进入业务配置或授权审计。 */
@Component
public class LifeMailCredentials {
    private final Path file;
    private final ObjectMapper mapper;

    public LifeMailCredentials(LocalDatabaseProperties database, ObjectMapper mapper) {
        this.file = database.getPath().toAbsolutePath().resolveSibling("life-mail-credentials.json");
        this.mapper = mapper;
    }

    /** 密码绑定完整连接身份，禁止换邮箱后沿用旧凭据。 */
    public synchronized String read(Settings s) {
        Entry value = load().get(s.owner());
        return value != null && value.identity().equals(identity(s)) ? value.password() : "";
    }

    /** 同一用户只保存当前连接；空密码删除凭据，Windows 沿用用户目录 ACL。 */
    public synchronized void save(Settings s) {
        var values = load();
        if (s.imapPassword().isBlank()) values.remove(s.owner());
        else values.put(s.owner(), new Entry(identity(s), s.imapPassword()));
        Path temporary = null;
        try {
            protect();
            if (values.isEmpty()) { Files.deleteIfExists(file); return; }
            temporary = Files.createTempFile(file.getParent(), ".life-mail-", ".tmp");
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, mapper.writeValueAsBytes(values));
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw new IllegalStateException("邮箱凭据保存失败", e); }
        finally {
            if (temporary != null) try { Files.deleteIfExists(temporary); }
            catch (IOException e) { throw new IllegalStateException("邮箱凭据临时文件清理失败", e); }
        }
    }

    private Map<String, Entry> load() {
        try {
            protect();
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new HashMap<>();
            try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(64 * 1024 + 1);
                if (bytes.length > 64 * 1024) throw new IOException("凭据文件过大");
                return mapper.readValue(bytes, new TypeReference<HashMap<String, Entry>>() {});
            }
        } catch (IOException e) { throw new IllegalStateException("邮箱凭据读取失败", e); }
    }

    private void protect() throws IOException {
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()))
            throw new IOException("邮箱凭据文件不能使用符号链接");
    }

    /** 主机名忽略大小写；账号、端口必须完全一致。 */
    static String identity(Settings s) {
        return s.imapHost().toLowerCase(Locale.ROOT) + ":" + s.imapPort() + ":" + s.imapUsername();
    }

    private record Entry(String identity, String password) {
        @Override public String toString() { return "LifeMailCredential{REDACTED}"; }
    }
}
