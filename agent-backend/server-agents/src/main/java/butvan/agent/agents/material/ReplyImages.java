package butvan.agent.agents.material;

import butvan.agent.agents.agent.AgentImageAttachment;
import butvan.agent.agents.agent.AgentImages;
import io.agentscope.core.agent.RuntimeContext;

import java.util.ArrayList;
import java.util.List;

/** 仅由当前对话轮持有的图片输出，审批恢复沿用同一实例。 */
public final class ReplyImages {
    public static final String SCREEN_TOOL = "capture_screen";
    public static final String WEB_IMAGE_TOOL = "web_image_search";

    private static final class ChannelDisabled {
        private static final ChannelDisabled INSTANCE = new ChannelDisabled();
    }

    private final ChatMaterialRecorder.SaveImagesCommand scope;
    private final List<Image> images = new ArrayList<>();
    private int bytes;
    private boolean screenshotApproved;
    private boolean screenshotStarted;

    public ReplyImages(ChatMaterialRecorder.SaveImagesCommand scope) {
        this.scope = scope;
    }

    /** 标记当前渠道不支持助手图片回复，供工具筛选隐藏相关 Schema。 */
    public static void disableForChannel(RuntimeContext context) {
        if (context != null) context.put(ChannelDisabled.class, ChannelDisabled.INSTANCE);
    }

    public static boolean isChannelDisabled(RuntimeContext context) {
        return context != null && context.get(ChannelDisabled.class) != null;
    }

    public static boolean isReplyImageTool(String toolName) {
        return SCREEN_TOOL.equals(toolName) || WEB_IMAGE_TOOL.equals(toolName);
    }

    /** 工具只从可信运行上下文取得当前会话，子会话无法使用父会话的附件槽。 */
    public static ReplyImages require(RuntimeContext context) {
        ReplyImages output = context == null ? null : context.get(ReplyImages.class);
        if (output == null || !output.scope.sessionId().equals(context.getSessionId())
                || !output.scope.ownerId().equals(context.getUserId())) {
            throw new IllegalArgumentException("此工具仅支持桌面端和 iOS 的主对话");
        }
        return output;
    }

    /** 只有应用层恢复已批准的同一运行时才调用。 */
    public synchronized void approveScreenshot() {
        screenshotApproved = true;
    }

    /** 每轮只执行一次经用户批准的屏幕采集。 */
    public synchronized void beginScreenshot() {
        if (!screenshotApproved) throw new IllegalArgumentException("截图需要本轮的明确批准。");
        if (screenshotStarted || images.size() >= AgentImages.MAX_COUNT) {
            throw new IllegalArgumentException("本轮截图次数或图片数量已达上限");
        }
        screenshotStarted = true;
    }

    /** 只在本轮可用，成功落盘后才生成客户端可访问的文件 ID。 */
    public synchronized void add(AgentImageAttachment image, String title, String sourceUrl) {
        if (images.size() >= AgentImages.MAX_COUNT || bytes + image.sizeBytes() > AgentImages.MAX_TOTAL_BYTES) {
            throw new IllegalArgumentException("本轮图片数量或大小已达上限");
        }
        images.add(new Image(image, title, sourceUrl));
        bytes += image.sizeBytes();
    }

    public synchronized int remainingSlots() {
        return AgentImages.MAX_COUNT - images.size();
    }

    /** 使用和用户图片相同的归档规则，标题和来源保存在助手消息。 */
    public synchronized List<Attachment> persist(ChatMaterialRecorder recorder) {
        if (images.isEmpty()) return List.of();
        var command = new ChatMaterialRecorder.SaveImagesCommand(scope.ownerId(), scope.channel(),
                scope.sessionKind(), scope.sessionId(), scope.projectId(), scope.sessionTitle(), scope.turnId(),
                images.stream().map(Image::content).toList());
        List<String> ids = recorder.recordReplyImages(command);
        List<Attachment> attachments = new ArrayList<>(ids.size());
        for (int index = 0; index < ids.size(); index++) {
            Image image = images.get(index);
            attachments.add(new Attachment(ids.get(index), image.title(), image.sourceUrl()));
        }
        return List.copyOf(attachments);
    }

    /** 回复落盘失败时移除已归档的文件。 */
    public void discard(ChatMaterialRecorder recorder, List<Attachment> attachments) {
        recorder.discardImages(scope.ownerId(), attachments.stream().map(Attachment::fileId).toList());
    }

    /** 客户端只获取稳定文件 ID 和展示元数据。 */
    public record Attachment(String fileId, String title, String sourceUrl) { }

    private record Image(AgentImageAttachment content, String title, String sourceUrl) { }
}
