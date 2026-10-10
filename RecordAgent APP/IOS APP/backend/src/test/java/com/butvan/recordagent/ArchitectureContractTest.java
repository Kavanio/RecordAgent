package com.butvan.recordagent;

import static org.assertj.core.api.Assertions.assertThat;

import com.butvan.recordagent.annotation.ApiLog;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.RegexPatternTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;

/** 用工程契约测试防止新增接口遗漏日志，以及服务边界再次泄露持久层类型。 */
class ArchitectureContractTest {
    @Test
    void mappedControllersHaveLogsAndAvoidRepositories() throws Exception {
        var scanner = scanner("com[.]butvan[.]recordagent[.]controller[.].*");
        var candidates = scanner.findCandidateComponents("com.butvan.recordagent.controller");
        assertThat(candidates).isNotEmpty();
        for (var bean : candidates) {
            var type = Class.forName(bean.getBeanClassName());
            for (var method : type.getDeclaredMethods()) {
                if (AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                    assertThat(method.getAnnotation(ApiLog.class))
                            .as(method.toString())
                            .isNotNull();
            }
            for (var field : type.getDeclaredFields())
                assertThat(field.getType().getName())
                        .doesNotContain(".repository.", ".entity.", "JdbcTemplate");
        }
    }

    @Test
    void serviceInterfacesDoNotExportPersistenceTypes() throws Exception {
        var candidates =
                scanner("com[.]butvan[.]recordagent[.]service[.].*")
                        .findCandidateComponents("com.butvan.recordagent.service");
        assertThat(candidates).isNotEmpty();
        for (var bean : candidates) {
            var type = Class.forName(bean.getBeanClassName());
            if (!type.isInterface()) continue;
            for (var method : type.getDeclaredMethods()) {
                assertThat(method.getGenericReturnType().getTypeName())
                        .doesNotContain(".repository.", ".entity.");
                for (var parameter : method.getGenericParameterTypes())
                    assertThat(parameter.getTypeName()).doesNotContain(".repository.", ".entity.");
            }
        }
    }

    private ClassPathScanningCandidateComponentProvider scanner(String regex) {
        var scanner =
                new ClassPathScanningCandidateComponentProvider(false) {
                    @Override
                    protected boolean isCandidateComponent(AnnotatedBeanDefinition bean) {
                        return bean.getMetadata().isIndependent();
                    }
                };
        scanner.addIncludeFilter(new RegexPatternTypeFilter(Pattern.compile(regex)));
        return scanner;
    }
}
