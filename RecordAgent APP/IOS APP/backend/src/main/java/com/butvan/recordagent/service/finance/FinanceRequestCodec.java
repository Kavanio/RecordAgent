package com.butvan.recordagent.service.finance;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 幂等请求摘要与原始响应快照编解码；异常不携带正文或财务数据。 */
@Component
@RequiredArgsConstructor
public class FinanceRequestCodec {
    private final ObjectMapper mapper;

    /** 固定 DTO 字段顺序生成摘要，同一键的请求字段必须完全一致。 */
    public String hash(FinanceDtos.CreateTransaction request) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("收支请求摘要失败");
        }
    }

    /** 保存首次成功写入时的响应，重试不返回后来变化的余额。 */
    public String encode(FinanceDtos.Transaction response) {
        try { return mapper.writeValueAsString(response); }
        catch (JsonProcessingException error) { throw new IllegalStateException("收支响应编码失败"); }
    }

    /** 恢复持久化响应快照；只接受服务端自身 DTO。 */
    public FinanceDtos.Transaction decode(String response) {
        try { return mapper.readValue(response,FinanceDtos.Transaction.class); }
        catch (JsonProcessingException error) { throw new IllegalStateException("收支响应解码失败"); }
    }
}
