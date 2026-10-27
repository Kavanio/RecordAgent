package butvan.agent.network.controller;

import butvan.agent.network.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.MailException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** 将可预期的参数与配置错误返回为统一的接口响应，并记录带上下文的日志。 */
@Slf4j
@RestControllerAdvice
public class ApiExceptionHandler {

    /** 重置冲突、凭证过期及任务不存在保持稳定业务码。 */
    @ExceptionHandler(butvan.agent.network.reset.service.ResetRequestException.class)
    public Result<Void> handleResetRequest(butvan.agent.network.reset.service.ResetRequestException exception) {
        return Result.error(exception.code(), exception.getMessage());
    }
    @ExceptionHandler(butvan.agent.agents.reset.ResetBlockedException.class)
    public Result<Void> handleResetBarrier(butvan.agent.agents.reset.ResetBlockedException exception) {
        return Result.error(423, exception.getMessage());
    }

    /** multipart 请求超过全局安全上限时，不向客户端暴露底层容器异常。 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException exception) {
        log.warn("[API-LOG] 上传文件超过系统允许大小: maxUploadSize={}", exception.getMaxUploadSize());
        return Result.error(400, "上传文件过大，请选择符合页面大小要求的文件");
    }

    /** 参数或业务校验错误统一返回 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleInvalidRequest(IllegalArgumentException exception) {
        log.warn("[API-LOG] 请求参数或业务校验失败: {}", exception.getMessage());
        return Result.error(400, exception.getMessage());
    }

    /** 服务暂不可用（如邮件服务未配置）统一返回 503。 */
    @ExceptionHandler(IllegalStateException.class)
    public Result<Void> handleUnavailableService(IllegalStateException exception) {
        log.error("[API-LOG] 服务暂不可用: {}", exception.getMessage(), exception);
        return Result.error(503, exception.getMessage());
    }

    /** SMTP 发送失败统一返回 503，不向客户端透出底层邮件异常细节。 */
    @ExceptionHandler(MailException.class)
    public Result<Void> handleMailFailure(MailException exception) {
        log.error("[API-LOG] 邮件发送失败，请检查 SMTP 配置: {}", exception.getMessage(), exception);
        return Result.error(503, "邮件服务暂不可用，请检查 SMTP 配置后重试");
    }
}
