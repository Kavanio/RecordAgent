package butvan.agent.network.memory.client;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 没有生产 Adapter 时失败保护，不从配置地址创建隐式外发通道。 */
@Configuration
@EnableConfigurationProperties(HindsightConnectionProperties.class)
public class MemoryRemoteConfiguration {
    @Bean @ConditionalOnMissingBean(MemoryRemoteGateway.class)
    MemoryRemoteGateway memoryRemoteGateway() { return MemoryRemoteGateway.disabled(); }
    @Bean @ConditionalOnMissingBean(MemoryWriteGateway.class)
    MemoryWriteGateway memoryWriteGateway() { return MemoryWriteGateway.disabled(); }
    @Bean @ConditionalOnMissingBean(MemoryRecallGateway.class)
    MemoryRecallGateway memoryRecallGateway() { return MemoryRecallGateway.disabled(); }
}
