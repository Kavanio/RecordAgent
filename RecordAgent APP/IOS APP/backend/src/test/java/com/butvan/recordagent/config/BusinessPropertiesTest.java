package com.butvan.recordagent.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/** 验证部署参数绑定以及启动时的硬边界，避免非法配置进入运行态。 */
class BusinessPropertiesTest {
    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void bindsPolicyOverrides() {
        context.withPropertyValues("recordagent.auth.code-retry-seconds=120")
                .run(
                        result -> {
                            assertThat(result).hasNotFailed();
                            assertThat(
                                            result.getBean(BusinessProperties.class)
                                                    .getAuth()
                                                    .getCodeRetrySeconds())
                                    .isEqualTo(120);
                        });
    }

    @Test
    void rejectsInvalidLimitAtStartup() {
        context.withPropertyValues("recordagent.files.max-image-bytes=2000001")
                .run(result -> assertThat(result).hasFailed());
        context.withPropertyValues("recordagent.reading.day-limit=0")
                .run(result -> assertThat(result).hasFailed());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BusinessProperties.class)
    static class Config {}
}
