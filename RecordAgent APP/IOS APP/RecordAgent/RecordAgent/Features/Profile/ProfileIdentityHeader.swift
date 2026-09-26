import SwiftUI
import UIKit

/// 有照片时用短渐变衔接页面；无照片时与页面共享纯净底色。
struct ProfileIdentityHeader: View {
    let user: AuthUser?
    let appearance: ProfileAppearance
    let onEdit: () -> Void
    let onSettings: () -> Void
    @Environment(\.locale) private var locale

    private var cover: UIImage? {
        guard let data = appearance.coverData else { return nil }
        return UIImage(data: data)
    }

    private var foreground: Color { cover == nil ? AppColors.textPrimary : .white }

    private var name: String? {
        user?.profileDisplayName(override: appearance.nickname, locale: locale)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Spacer()
                Button(action: onSettings) {
                    Image(systemName: "gearshape")
                        .font(.system(size: 20, weight: .medium))
                        .foregroundStyle(foreground)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("profile.settings.title"))
                .shadow(color: .black.opacity(cover == nil ? 0 : 0.35), radius: 4, y: 1)
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
                            .foregroundStyle(
                                cover == nil ? AppColors.textSecondary : .white.opacity(0.9)
                            )
                            .lineLimit(2)
                    }
                }
                .foregroundStyle(foreground)
                .shadow(color: .black.opacity(cover == nil ? 0 : 0.35), radius: 3, y: 1)
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(foreground)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(user == nil)
                .accessibilityLabel(Text("profile.edit.title"))
                .shadow(color: .black.opacity(cover == nil ? 0 : 0.35), radius: 4, y: 1)
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
                    if cover != nil {
                        LinearGradient(
                            colors: [.black.opacity(0.18), .clear, .black.opacity(0.48)],
                            startPoint: .top, endPoint: .bottom)
                    }
                }
                .frame(width: geometry.size.width, height: geometry.size.height)
                .clipped()
            }
        }
        .overlay(alignment: .bottom) {
            if cover != nil {
                LinearGradient(
                    colors: [.clear, Color(uiColor: .systemBackground)],
                    startPoint: .top, endPoint: .bottom
                )
                .frame(height: 22)
                .allowsHitTesting(false)
            }
        }
    }

    @ViewBuilder private func coverImage(size: CGSize) -> some View {
        if let image = cover {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(width: size.width, height: size.height)
                .clipped()
        } else {
            Color(uiColor: .systemBackground)
                .frame(width: size.width, height: size.height)
        }
    }
}

extension AuthUser {
    /// 个性化昵称优先；缺失时按账户稳定生成展示名。
    func profileDisplayName(override: String?, locale: Locale) -> String {
        for candidate in [override, nickname] {
            let value = candidate?.trimmingCharacters(in: .whitespacesAndNewlines)
            if let value, !value.isEmpty { return value }
        }
        let number = generatedNicknameNumber
        return AppLocalization.string("profile.generatedNickname \(number)", locale: locale)
    }
}
