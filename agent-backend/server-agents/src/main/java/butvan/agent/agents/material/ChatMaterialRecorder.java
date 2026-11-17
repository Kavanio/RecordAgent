package butvan.agent.agents.material;

import butvan.agent.agents.agent.AgentImageAttachment;

import java.util.List;

/**
 * 对话素材持久化端口。
 *
 * <p>{@code server-agents} 只定义受控记录契约，不反向依赖 {@code server-network} 的数据库或文件实现；
 * 落盘与入库后的稳定文件 ID 将写入用户消息 transcript，供聊天窗口回显与档案库展示。</p>
 */
public interface ChatMaterialRecorder {

    /**
     * 持久化本轮用户发送的图片附件，并返回与输入顺序一致的稳定文件 ID 列表。
     *
     * @param command 包含所有者、渠道、会话与图片附件的保存命令
     * @return 与 {@code command.images()} 一一对应的唯一文件 ID 列表
     */
    List<String> recordUserImages(SaveImagesCommand command);

    /** 默认空实现，用于仅加载 {@code server-agents} 的隔离单元测试。 */
    static ChatMaterialRecorder noop() {
        return command -> List.of();
    }

    /** 对话图片落盘命令，包含用于多级目录分类归纳的完整会话上下文。 */
    record SaveImagesCommand(
            String ownerId,
            String channel,
            String sessionKind,
            String sessionId,
            String projectId,
            String sessionTitle,
            String turnId,
            List<AgentImageAttachment> images
    ) {
        public SaveImagesCommand {
            images = images == null ? List.of() : List.copyOf(images);
        }
    }
}
