package com.butvan.recordagent.service.finance;

import static org.assertj.core.api.Assertions.assertThat;

import com.butvan.recordagent.dto.finance.FinanceDtos;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** 请求指纹不随 JSON 全局格式变化，字段边界和空值仍可区分。 */
class FinanceRequestCodecTest {
    @Test
    void fingerprintSurvivesJsonConfigurationChangesAndDistinguishesFields() {
        var compact = new FinanceRequestCodec(new ObjectMapper());
        var pretty =
                new FinanceRequestCodec(
                        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT));
        UUID account = UUID.randomUUID();
        var first = request(account, "a", "bc");
        assertThat(compact.hash(first)).isEqualTo(pretty.hash(first));
        assertThat(compact.hash(first)).isNotEqualTo(compact.hash(request(account, "ab", "c")));
        assertThat(compact.hash(request(account, null, "")))
                .isNotEqualTo(compact.hash(request(account, "", "")));
    }

    private FinanceDtos.CreateTransaction request(UUID account, String category, String note) {
        return new FinanceDtos.CreateTransaction(
                account,
                "expense",
                category,
                note,
                100L,
                LocalDate.of(2026, 9, 30),
                LocalTime.NOON,
                "Asia/Shanghai");
    }
}
