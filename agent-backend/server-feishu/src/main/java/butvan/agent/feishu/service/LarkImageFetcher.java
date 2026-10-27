package butvan.agent.feishu.service;

import com.lark.oapi.Client;
import com.lark.oapi.service.im.v1.model.GetMessageResourceReq;
import com.lark.oapi.service.im.v1.model.GetMessageResourceResp;

/** 使用 SDK 单次 HTTP 接口下载图片；异常文本可能含临时 URL，不向上或向日志透出。 */
public final class LarkImageFetcher implements FeishuImageFetcher {
    private final Client client;

    public LarkImageFetcher(Client client) { this.client = client; }

    @Override
    public byte[] fetch(String messageId, String fileKey) {
        try {
            GetMessageResourceResp response = client.im().messageResource().get(GetMessageResourceReq.newBuilder()
                    .type("image").messageId(messageId).fileKey(fileKey).build());
            if (response == null || !response.success() || response.getData() == null) {
                throw new IllegalStateException("飞书图片下载失败");
            }
            return response.getData().toByteArray();
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Exception exception) {
            // 只保留异常类型，避免把带临时凭证的下载地址写进日志。
            throw new IllegalStateException("飞书图片下载失败：" + exception.getClass().getSimpleName());
        }
    }
}
