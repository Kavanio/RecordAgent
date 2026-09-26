import SwiftUI
import UIKit

/// 全宽封面承载身份信息，底部融入个人主页的连续画布。
struct ProfileIdentityHeader: View {
    let user: AuthUser?
    let appearance: ProfileAppearance
    let onEdit: () -> Void
    let onSettings: () -> Void
    @Environment(\.colorScheme) private var colorScheme

    private var name: String? {
        if let nickname = appearance.nickname, !nickname.isEmpty { return nickname }
        if let nickname = user?.nickname, !nickname.isEmpty { return nickname }
        return user?.email.components(separatedBy: "@").first
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("profile.settings.title"))
                .shadow(color: .black.opacity(0.45), radius: 5, y: 2)
            }
            .padding(.horizontal, 24)
            .padding(.top, 54)

            Spacer(minLength: 58)

            HStack(alignment: .center, spacing: 14) {
                ProfileAvatarView(
                    name: name, avatarURLString: user?.avatarUrl, imageData: appearance.avatarData
                )
                .padding(3)
                .background(.white, in: Circle())

                VStack(alignment: .leading, spacing: 5) {
                    if let name, !name.isEmpty {
                        Text(verbatim: name)
                            .font(.title2.weight(.semibold))
                    } else {
                        Text("profile.cover.fallback")
                            .font(.title2.weight(.semibold))
                    }
                    if let user {
                        Text(verbatim: user.email)
                            .font(.footnote)
                            .foregroundStyle(.white.opacity(0.88))
                            .lineLimit(2)
                    }
                }
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.42), radius: 4, y: 1)
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(.white)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(user == nil)
                .accessibilityLabel(Text("profile.edit.title"))
                .shadow(color: .black.opacity(0.45), radius: 5, y: 2)
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 48)
        }
        .frame(maxWidth: .infinity)
        .frame(minHeight: 278)
        .background {
            GeometryReader { geometry in
                ZStack {
                    coverImage(size: geometry.size)
                    LinearGradient(
                        stops: [
                            .init(color: .black.opacity(0.35), location: 0),
                            .init(color: .clear, location: 0.36),
                            .init(color: .black.opacity(0.22), location: 0.55),
                            .init(color: .black.opacity(0.62), location: 1),
                        ],
                        startPoint: .top, endPoint: .bottom)
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
            }
        }
        .overlay(alignment: .bottom) {
            ZStack {
                Color(uiColor: .systemBackground)
                AppColors.skyLightBlue.opacity(colorScheme == .dark ? 0.08 : 0.55)
            }
            .mask {
                LinearGradient(colors: [.clear, .white], startPoint: .top, endPoint: .bottom)
            }
            .frame(height: 48)
            .allowsHitTesting(false)
        }
    }

    @ViewBuilder private func coverImage(size: CGSize) -> some View {
        if let data = appearance.coverData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size.width, height: size.height)
                .clipped()
        } else {
            ZStack {
                LinearGradient(
                    colors: [
                        Color(red: 0.17, green: 0.27, blue: 0.42),
                        Color(red: 0.35, green: 0.43, blue: 0.60),
                        HomeCategory.record.color.opacity(0.85),
                    ],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
                Circle()
                    .fill(Color.white.opacity(0.12))
                    .frame(width: 220, height: 220)
                    .blur(radius: 65)
                    .offset(x: size.width * 0.32, y: -48)
            }
            .frame(width: size.width, height: size.height)
            .clipped()
        }
    }
}
