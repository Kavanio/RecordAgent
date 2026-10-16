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

    /** 固定字段顺序和 UTF-8 长度前缀生成摘要，不受 JSON 格式或属性排序配置变化影响。 */
    public String hash(FinanceDtos.CreateTransaction request) {
        try (var bytes = new java.io.ByteArrayOutputStream();
                var output = new java.io.DataOutputStream(bytes)) {
            writeField(output, "finance-v1");
            writeField(output, request.accountId().toString());
            writeField(output, request.type());
            writeField(output, request.category());
            writeField(output, request.note());
            output.writeLong(request.amountInCents());
            writeField(output, request.date().toString());
            writeField(output, request.time().toString());
            writeField(output, request.timeZone());
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (java.io.IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("收支请求摘要失败");
        }
    }

    private static void writeField(java.io.DataOutputStream output, String field)
            throws java.io.IOException {
        if (field == null) {
            output.writeInt(-1);
            return;
        }
        byte[] value = field.getBytes(StandardCharsets.UTF_8);
        output.writeInt(value.length);
        output.write(value);
    }

    /** 保存首次成功写入时的响应，重试不返回后来变化的余额。 */
    public String encode(FinanceDtos.Transaction response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("收支响应编码失败");
        }
    }

    /** 恢复持久化响应快照；只接受服务端自身 DTO。 */
    public FinanceDtos.Transaction decode(String response) {
        try {
            return mapper.readValue(response, FinanceDtos.Transaction.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("收支响应解码失败");
        }
    }
}
