package com.butvan.recordagent.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** 不含凭据的业务策略配置；非法数值在启动时拒绝，凭据仍由 Spring 部署配置管理。 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "recordagent")
public class BusinessProperties {
    @Valid private Auth auth = new Auth();
    @Valid private Files files = new Files();
    @Valid private Mail mail = new Mail();
    @Valid private Reading reading = new Reading();

    /** 会话期限、验证码防重放及登录限流策略。 */
    @Getter
    @Setter
    public static class Auth {
        @Min(1)
        @Max(20)
        private int maxCodeAttempts = 5;

        @Min(1)
        @Max(60)
        private int codeValidityMinutes = 10;

        @Min(1)
        @Max(3600)
        private int codeRetrySeconds = 60;

        @Min(1)
        @Max(1440)
        private int accessTokenMinutes = 15;

        @Min(1)
        @Max(365)
        private int refreshTokenDays = 30;

        @Min(1)
        @Max(1000)
        private int passwordAttempts = 10;

        @Min(1)
        @Max(3600)
        private int passwordWindowSeconds = 60;

        @Min(1)
        @Max(1000)
        private int requestAttempts = 60;

        @Min(1)
        @Max(3600)
        private int requestWindowSeconds = 60;
    }

    /** 文件存储位置与 JPEG 上限；最大只允许 2 MB，不能放宽工程契约。 */
    @Getter
    @Setter
    public static class Files {
        @NotBlank private String root = "./data/files/objects";

        @Min(4)
        @Max(2_000_000)
        private int maxImageBytes = 2_000_000;
    }

    /** 非秘密的发件人配置；允许开发环境未配置，发送时返回明确错误。 */
    @Getter
    @Setter
    public static class Mail {
        private String from = "";
    }

    /** 明细返回数量保护；超限返回可识别错误，统计使用独立聚合查询。 */
    @Getter
    @Setter
    public static class Reading {
        @Min(1)
        @Max(1000)
        private int dayLimit = 200;

        @Min(1)
        @Max(1000)
        private int accountLimit = 100;
    }
}
