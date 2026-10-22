package butvan.agent.agents.review;

import java.util.List;

/**
 * 复盘草案生成 seam。生产实现调用用户明确授权的固定模型连接，
 * 测试使用 fake；调用方不依赖具体模型 SDK。
 */
public interface ReviewGenerator {

    /** 生成一次结构化草案；失败必须抛出可识别的生成异常。 */
    ReviewDraft generate(ReviewGenerationRequest request);

    /** 模型生成失败分类，用于决定是否降级为基础报告。 */
    class ReviewGenerationException extends RuntimeException {
        public enum Kind { TIMEOUT, INVALID, UNAVAILABLE, CANCELLED }
        private final Kind kind;

        public ReviewGenerationException(Kind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public ReviewGenerationException(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public Kind kind() { return kind; }
    }
}
