package com.butvan.recordagent.service.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.butvan.recordagent.handler.ApiErrorCode;
import com.butvan.recordagent.handler.ApiException;
import org.junit.jupiter.api.Test;

/** 确认业务日期计算使用真实时区，并以稳定错误码拒绝非法输入。 */
class BusinessTimeZoneTest {
    @Test
    void acceptsIanaZone() {
        assertThat(BusinessTimeZone.parse("Asia/Shanghai").getId()).isEqualTo("Asia/Shanghai");
    }

    @Test
    void rejectsInvalidZoneWithoutEchoingInput() {
        assertThatThrownBy(() -> BusinessTimeZone.parse("private-value"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.errorCode()).isEqualTo(ApiErrorCode.INVALID_ARGUMENT);
                            assertThat(error.getMessage()).doesNotContain("private-value");
                        });
    }
}
