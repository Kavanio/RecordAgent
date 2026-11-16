package butvan.agent.network.dto.account;

/** 更新个人昵称与移除头像/背景图的请求体。 */
public record UpdateAccountAppearanceRequest(
        String nickname,
        boolean clearAvatar,
        boolean clearCover,
        long expectedVersion) {
}
