package butvan.agent.network.memory.service;

import lombok.Getter;

/** 只携带稳定业务码与安全中文说明，不接收供应商异常或来源正文。 */
@Getter
public class MemoryRequestException extends RuntimeException {
    private final int code;
    public MemoryRequestException(int code, String message) { super(message); this.code = code; }
}
