package butvan.agent.network.memory.repository;

import butvan.agent.network.memory.service.MemoryRequestException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

/** 连接更换的短事务屏障；未清理目标存在时保留原接收方及凭据。 */
@Repository
public class MemoryConnectionRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public MemoryConnectionRepository(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc; this.transaction = new TransactionTemplate(manager);
    }
    public <T> T change(String recipient, Supplier<T> action) {
        return transaction.execute(status -> {
            // 即使尚无设置行也先取得 SQLite 写保留锁，避免核对和文件替换之间登记旧目标。
            jdbc.update("UPDATE memory_settings SET version=version");
            boolean pinned = Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM memory_deletion WHERE state<>'VERIFIED' AND recipient_fingerprint<>?)
                    """, Boolean.class, recipient));
            if (pinned) throw new MemoryRequestException(409, "旧接收方仍有未核实的远端目标，不能更换记忆库、地址、凭据或处理链；请保留原连接继续清理");
            return action.get();
        });
    }
}
