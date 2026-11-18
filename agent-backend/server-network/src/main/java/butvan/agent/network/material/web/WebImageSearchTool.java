package butvan.agent.network.material.web;

import butvan.agent.agents.material.ReplyImages;
import butvan.agent.agents.tool.AgentToolModule;
import butvan.agent.agents.tool.impl.WebSearchTool;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** 把与用户请求相关的公开图片作为助手附件送回当前会话。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebImageSearchTool implements AgentToolModule {
    private final WebSearchTool search;
    private final PublicImageDownloader downloader;

    @Tool(name = "web_image_search",
            description = "在用户要求看图、找图或发参考图时搜索公开图片，将成功下载的结果作为图片发送到当前对话。")
    public ToolResultBlock searchImages(RuntimeContext context,
                               @ToolParam(name = "query", description = "图片搜索关键词") String query,
                               @ToolParam(name = "count", description = "图片数量，1 至 4", required = false)
                               Integer count) {
        if (query == null || query.isBlank() || query.length() > 300) return ToolResultBlock.error("请输入 1 至 300 字的图片关键词。");
        try {
            ReplyImages reply = ReplyImages.require(context);
            int requested = Math.min(reply.remainingSlots(), count == null ? 3 : Math.clamp(count, 1, 4));
            if (requested == 0) return ToolResultBlock.error("本轮图片数量已达上限。");
            int added = 0;
            for (var result : search.findImages(query.strip(), requested + 2)) {
                PublicImageDownloader.DownloadedImage image;
                try {
                    image = downloader.download(result.url());
                } catch (IOException | IllegalArgumentException exception) {
                    log.debug("公开图片候选未导入：errorType={}", exception.getClass().getSimpleName());
                    continue;
                }
                String title = result.description().strip();
                if (title.isEmpty()) title = "网络图片 " + (added + 1);
                try {
                    reply.add(image.image(), title.substring(0, Math.min(title.length(), 120)), image.verifiedUrl());
                } catch (IllegalArgumentException exception) {
                    if (added == 0) return ToolResultBlock.error(exception.getMessage());
                    break;
                }
                if (++added == requested) break;
            }
            return added == 0 ? ToolResultBlock.error("本次没有找到可下载的公开图片。")
                    : ToolResultBlock.text("已将 " + added + " 张网络图片加入本轮回复，用户可点击查看大图或保存。");
        } catch (IllegalArgumentException exception) {
            return ToolResultBlock.error(exception.getMessage());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ToolResultBlock.error("找图已取消。");
        } catch (Exception exception) {
            log.warn("联网找图失败：errorType={}", exception.getClass().getSimpleName());
            return ToolResultBlock.error("联网找图失败，请检查 Tavily 配置或稍后重试。");
        }
    }
}
