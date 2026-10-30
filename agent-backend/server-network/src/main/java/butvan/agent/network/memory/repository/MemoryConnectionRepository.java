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
            // 取得 SQLite 写保留锁，避免核对和文件替换之间并发登记旧目标；切换接收方后由 MemoryService 自动撤销旧授权。
            jdbc.update("UPDATE memory_settings SET version=version");
            return action.get();
        });
    }
}
