package butvan.agent.network.dto.account;

/** 个人资料外观响应（昵称、头像版本、背景图版本与并发控制版本号）。 */
public record AccountAppearanceResponse(
        String nickname,
        String avatarVersion,
        String coverVersion,
        long version) {
}
