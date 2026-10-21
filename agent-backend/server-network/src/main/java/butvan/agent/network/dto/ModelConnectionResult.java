package butvan.agent.network.dto;

/** 连接测试仅返回稳定结果，不向客户端透传供应商错误正文或密钥。 */
public record ModelConnectionResult(boolean success, String message) {}
