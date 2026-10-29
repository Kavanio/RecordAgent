package butvan.agent.network.memory.client;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 仅用于显式只读探测的服务端配置；不产生保存／查询用途授权。 */
@Getter
@Setter
@ConfigurationProperties(prefix = "butvan.memory.hindsight")
public class HindsightConnectionProperties {
    /** 默认禁用，不在启动或设置读取时访问远端。 */
    private boolean probeEnabled;
    /** 受保护环境提供服务地址，Controller 不接受任意 URL。 */
    private String endpoint = "";
    /** 凭据只在后端请求头内使用，不进入配置响应、日志或 SQLite。 */
    private String token = "";

    @Override public String toString() { return "HindsightConnectionProperties{endpoint=redacted,token=redacted}"; }
}
