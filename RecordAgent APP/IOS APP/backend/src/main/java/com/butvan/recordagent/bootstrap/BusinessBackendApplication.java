package com.butvan.recordagent.bootstrap;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;

/** 独立业务后端的启动入口。 */
@SpringBootApplication(scanBasePackages = "com.butvan.recordagent", exclude = UserDetailsServiceAutoConfiguration.class)
public class BusinessBackendApplication {

    /** 启动 HTTP 服务与数据库迁移。 */
    public static void main(String[] args) {
        SpringApplication.run(BusinessBackendApplication.class, args);
    }
}
