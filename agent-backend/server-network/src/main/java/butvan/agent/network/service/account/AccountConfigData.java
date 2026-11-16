package butvan.agent.network.service.account;

import lombok.Data;

/** config.json 的 account 节点结构，仅表示当前设备上的已绑定账户。 */
@Data
public class AccountConfigData {
    private String email;
    private String passwordHash;
    private boolean emailVerified;
    private String boundAt;
    private boolean emailNotificationsEnabled = true;
    private String nickname;
    private long appearanceVersion;
}
