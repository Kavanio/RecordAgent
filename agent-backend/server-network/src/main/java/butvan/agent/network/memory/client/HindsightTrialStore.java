package butvan.agent.network.memory.client;

import butvan.agent.agents.storage.AgentStorageProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/** 只管理受保护的连接文件；不保存聊天或授权，不在读取时访问网络。 */
@Component
public class HindsightTrialStore {
    private final Path file;
    private final ObjectMapper mapper;
    public HindsightTrialStore(AgentStorageProperties storage, ObjectMapper mapper) {
        this.file = storage.getHindsightConnectionFile(); this.mapper = mapper;
    }
    /** 凭据禁止使用 record 的默认 toString。 */
    public record Configuration(long version, String endpoint, String token, String processingChain, boolean allowPlaintext,
                                boolean trialRisksAccepted, String requestId, String bankId) {
        public Configuration { bankId = bankId == null ? "" : bankId; }
        public Configuration(long version, String endpoint, String token, String processingChain, boolean allowPlaintext,
                             boolean trialRisksAccepted, String requestId) {
            this(version, endpoint, token, processingChain, allowPlaintext, trialRisksAccepted, requestId, "");
        }
        @Override public String toString() { return "HindsightTrialConfiguration{REDACTED}"; }
    }
    public Optional<Configuration> read() throws IOException {
        protect();
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(16 * 1024 + 1);
            if (bytes.length > 16 * 1024) throw new IOException("记忆连接配置过大");
            return Optional.of(mapper.readValue(bytes, Configuration.class));
        }
    }
    /** 临时文件先设为仅属主可读写，再原子替换；Windows 沿用用户目录 ACL，不声称具备 POSIX 权限。 */
    public void write(Configuration value) throws IOException {
        protect();
        byte[] bytes = mapper.writeValueAsBytes(value);
        if (bytes.length > 16 * 1024) throw new IOException("记忆连接配置过大");
        Path temporary = Files.createTempFile(file.getParent(), ".hindsight-", ".tmp");
        try {
            if (Files.getFileStore(temporary).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            Files.write(temporary, bytes);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
    private void protect() throws IOException {
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(file.getParent()))
            throw new IOException("记忆连接配置不能使用符号链接");
    }
}
