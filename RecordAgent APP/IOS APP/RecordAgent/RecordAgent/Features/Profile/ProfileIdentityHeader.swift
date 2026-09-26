import SwiftUI
import UIKit

/// 宽幅封面、交叠头像和双入口组成个人身份区，图片只改变这一区域。
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
        VStack(alignment: .leading, spacing: 0) {
            cover
                .frame(height: 132)
                .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
                .overlay(alignment: .topTrailing) {
                    Button(action: onSettings) {
                        Image(systemName: "gearshape")
                            .font(.system(size: 19, weight: .medium))
                            .foregroundStyle(AppColors.textPrimary)
                            .frame(width: 44, height: 44)
                            .background(.regularMaterial, in: Circle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("profile.settings.title"))
                    .padding(12)
                }

            HStack(alignment: .bottom, spacing: 14) {
                ProfileAvatarView(
                    name: name, avatarURLString: user?.avatarUrl, imageData: appearance.avatarData
                )
                .padding(4)
                .background(Color(uiColor: .systemBackground), in: Circle())
                .offset(y: -28)
                .padding(.bottom, -28)
                VStack(alignment: .leading, spacing: 4) {
                    if let name, !name.isEmpty {
                        Text(verbatim: name)
                            .font(.title2.weight(.semibold))
                            .foregroundStyle(AppColors.textPrimary)
                    } else {
                        Text("profile.cover.fallback")
                            .font(.title2.weight(.semibold))
                    }
                    if let user {
                        Text(verbatim: user.email)
                            .font(.footnote)
                            .foregroundStyle(AppColors.textSecondary)
                            .lineLimit(2)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Button(action: onEdit) {
                    Image(systemName: "pencil")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.textPrimary)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(user == nil)
                .accessibilityLabel(Text("profile.edit.title"))
            }
            .padding(.horizontal, 4)
            .padding(.top, 8)
        }
    }

    @ViewBuilder private var cover: some View {
        if let data = appearance.coverData, let image = UIImage(data: data) {
            Image(uiImage: image)
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .clipped()
        } else {
            ZStack {
                LinearGradient(
                    colors: [
                        AppColors.skyLightBlue, HomeCategory.library.color.opacity(0.22),
                        HomeCategory.record.color.opacity(0.12),
                    ],
                    startPoint: .topLeading, endPoint: .bottomTrailing)
                Circle()
                    .stroke(.white.opacity(0.42), lineWidth: 1)
                    .frame(width: 240, height: 240)
                    .offset(x: 125, y: 50)
                Circle()
                    .stroke(.white.opacity(0.3), lineWidth: 1)
                    .frame(width: 164, height: 164)
                    .offset(x: 125, y: 50)
            }
        }
    }
}
