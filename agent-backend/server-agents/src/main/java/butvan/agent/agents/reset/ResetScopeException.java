package butvan.agent.agents.reset;

import java.io.IOException;

/** 已知清理边界错误；文案仅含逻辑类别，允许展示，不含真实路径、正文或凭据。 */
public class ResetScopeException extends IOException {
    public ResetScopeException(String message) { super(message); }
}
