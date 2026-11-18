package butvan.agent.network.material.screen;

import butvan.agent.agents.material.ReplyImages;
import butvan.agent.agents.tool.AgentToolModule;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.concurrent.Semaphore;

/** 复用聊天审批，在已授权的后端宿主上执行一次主屏截图。 */
@Component
@Slf4j
public class ScreenCaptureTool implements AgentToolModule {
    private final boolean enabled;
    private final Path enableFile = Path.of(System.getProperty("user.home"), ".butvan-agent", "screen-capture.enabled");
    private final ScreenCapture capture;
    private final Semaphore captureSlot = new Semaphore(1);

    public ScreenCaptureTool(@Value("${butvan.screen-capture.enabled:false}") boolean enabled) {
        this.enabled = enabled;
        this.capture = new ScreenCapture();
    }

    @Tool(name = ReplyImages.SCREEN_TOOL,
            description = "用户要求把电脑屏幕截图发给他时，截取当前后端宿主电脑的主屏幕并作为图片回到原对话。"
                    + "本工具需要用户单次审批。")
    public ToolResultBlock capture(RuntimeContext context) {
        if (!enabled && !Files.isRegularFile(enableFile, LinkOption.NOFOLLOW_LINKS)) {
            return ToolResultBlock.error("此电脑尚未启用截图。可在后端设置 BUTVAN_SCREEN_CAPTURE_ENABLED=true，"
                    + "或在后端用户目录的 .butvan-agent 下创建 screen-capture.enabled 文件。");
        }
        try {
            ReplyImages reply = ReplyImages.require(context);
            if (!captureSlot.tryAcquire()) return ToolResultBlock.error("电脑正在处理一次截图，请稍后再试。");
            try {
                reply.beginScreenshot();
                reply.add(capture.primaryDisplay(), "电脑主屏截图", null);
            } finally {
                captureSlot.release();
            }
            return ToolResultBlock.text("主屏截图已加入本轮回复，可以请用户点开图片查看或保存。");
        } catch (IllegalArgumentException | IllegalStateException exception) {
            return ToolResultBlock.error(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.error("截图已取消。");
        } catch (Exception | LinkageError exception) {
            log.warn("主屏截图未完成：errorType={}", exception.getClass().getSimpleName());
            return ToolResultBlock.error("截图失败，请确认后端在已登录且解锁的图形桌面运行，并检查系统截图权限。");
        }
    }
}
