package butvan.agent.agents.review;

/** 渠道回复只关联生活复盘主题，不共享渠道和桌面的活跃 AgentState。 */
public interface LifeReviewReplyPort {
    String reply(String owner, String contentId, String replyId, String text);
}
