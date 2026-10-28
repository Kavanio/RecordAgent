package butvan.agent.feishu.service;

/** 图片资源下载 seam：只在执行线程内调用，绝不放在长连接回调里阻塞收件。 */
@FunctionalInterface
public interface FeishuImageFetcher {

    /**
     * 下载消息内图片的原始字节。
     *
     * @param messageId 图片消息 ID
     * @param fileKey   平台图片键
     * @return 图片原始字节
     * @throws RuntimeException 下载失败；调用方转成用户可读反馈，不重试、不落盘
     */
    byte[] fetch(String messageId, String fileKey);
}
