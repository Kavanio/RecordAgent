import SwiftUI
import UIKit

/// 沉浸式宽幅封面与个人信息浮层（小红书风格），宽度撑满屏幕，头像与昵称以背景图为衬底。
struct ProfileIdentityHeader: View {
    let user: AuthUser?
    let appearance: ProfileAppearance
    let onEdit: () -> Void
    let onSettings: () -> Void

    private var name: String? {
        if let nickname = appearance.nickname, !nickname.isEmpty { return nickname }
        if let nickname = user?.nickname, !nickname.isEmpty { return nickname }
        return user?.email.components(separatedBy: "@").first
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            // 1. 底层：撑满全宽的背景封面与防眩光渐变
            coverLayer

            // 2. 顶层：悬浮的设置操作与个人信息
            VStack(alignment: .leading, spacing: 0) {
                // 顶部状态栏与设置按钮栏（避让刘海屏与灵动岛）
                HStack {
                    Spacer()
                    Button(action: onSettings) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 18, weight: .medium))
                            .foregroundStyle(.white)
                            .frame(width: 38, height: 38)
                            .background(.ultraThinMaterial.opacity(0.85), in: Circle())
                            .overlay(Circle().strokeBorder(Color.white.opacity(0.25), lineWidth: 0.8))
                            .shadow(color: Color.black.opacity(0.18), radius: 6, x: 0, y: 2)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("profile.settings.title"))
                }
                .padding(.horizontal, 20)
                .padding(.top, 54)

                Spacer(minLength: 28)

                // 底部个人信息（头像、昵称、邮箱、编辑按钮）—— 完全以背景图为底
                HStack(alignment: .center, spacing: 14) {
                    ProfileAvatarView(
                        name: name,
                        avatarURLString: user?.avatarUrl,
                        imageData: appearance.avatarData,
                        size: 72,
                        showBorder: true
                    )

                    VStack(alignment: .leading, spacing: 5) {
                        if let name, !name.isEmpty {
                            Text(verbatim: name)
                                .font(.system(size: 21, weight: .bold))
                                .foregroundStyle(.white)
                                .shadow(color: Color.black.opacity(0.35), radius: 3, x: 0, y: 1)
                        } else {
                            Text("profile.cover.fallback")
                                .font(.system(size: 21, weight: .bold))
                                .foregroundStyle(.white)
                                .shadow(color: Color.black.opacity(0.35), radius: 3, x: 0, y: 1)
                        }

                        if let user {
                            Text(verbatim: user.email)
                                .font(.subheadline)
                                .foregroundStyle(.white.opacity(0.88))
                                .lineLimit(1)
                                .shadow(color: Color.black.opacity(0.3), radius: 2, x: 0, y: 1)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)

                    Button(action: onEdit) {
                        HStack(spacing: 5) {
                            Image(systemName: "pencil")
                                .font(.system(size: 13, weight: .semibold))
                            Text("profile.edit.title")
                                .font(.system(size: 13, weight: .medium))
                        }
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 8)
                        .background(.ultraThinMaterial.opacity(0.85), in: Capsule())
                        .overlay(Capsule().strokeBorder(Color.white.opacity(0.3), lineWidth: 0.8))
                        .shadow(color: Color.black.opacity(0.15), radius: 4, x: 0, y: 2)
                    }
                    .buttonStyle(.plain)
                    .disabled(user == nil)
                    .accessibilityLabel(Text("profile.edit.title"))
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 28)
            }
        }
        .frame(minHeight: 250)
        .clipped()
    }

    private var coverLayer: some View {
        ZStack {
            coverImage
            // 保护性暗色渐变：确保顶部设置按钮与底部文字在任何背景下都具备清晰对比度与高级层次
            LinearGradient(
                stops: [
                    .init(color: .black.opacity(0.38), location: 0.0),
                    .init(color: .black.opacity(0.08), location: 0.30),
                    .init(color: .black.opacity(0.48), location: 0.70),
                    .init(color: .black.opacity(0.70), location: 1.0)
                ],
                startPoint: .top,
                endPoint: .bottom
            )
        }
    }

    @ViewBuilder private var coverImage: some View {
        if let data = appearance.coverData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .clipped()
        } else {
            defaultCover
        }
    }

    private var defaultCover: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0.16, green: 0.23, blue: 0.38),
                    Color(red: 0.28, green: 0.38, blue: 0.58),
                    Color(red: 0.44, green: 0.50, blue: 0.65)
                ],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            Circle()
                .fill(
                    RadialGradient(
                        colors: [Color(red: 0.56, green: 0.68, blue: 0.94).opacity(0.35), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: 180
                    )
                )
                .frame(width: 320, height: 320)
                .offset(x: 100, y: -40)

            Circle()
                .fill(
                    RadialGradient(
                        colors: [Color(red: 0.92, green: 0.65, blue: 0.58).opacity(0.25), .clear],
                        center: .center,
                        startRadius: 0,
                        endRadius: 140
                    )
                )
                .frame(width: 240, height: 240)
                .offset(x: -80, y: 60)
        }
    }
}
