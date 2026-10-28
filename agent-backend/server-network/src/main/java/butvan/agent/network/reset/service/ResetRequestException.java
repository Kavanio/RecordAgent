package butvan.agent.network.reset.service;

/** 可区分的预览失效／冲突／未找到错误，不携带敏感请求。 */
public class ResetRequestException extends RuntimeException {
    private final int code;
    public ResetRequestException(int code, String message) { super(message); this.code = code; }
    public int code() { return code; }
}
