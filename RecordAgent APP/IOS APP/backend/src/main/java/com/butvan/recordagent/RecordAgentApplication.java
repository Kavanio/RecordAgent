package com.butvan.recordagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** 独立业务后端的启动入口。 */
@org.springframework.boot.context.properties.ConfigurationPropertiesScan
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class RecordAgentApplication {

    /** 启动 HTTP 服务与数据库迁移。 */
    public static void main(String[] args) {
        SpringApplication.run(RecordAgentApplication.class, args);
    }
}
