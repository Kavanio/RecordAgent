import SwiftUI
import UIKit

/// 显示账户头像；尚无头像或加载失败时保留同尺寸的字母印记。
struct ProfileAvatarView: View {
    let name: String?
    let avatarURLString: String?
    var imageData: Data? = nil

    private var avatarURL: URL? {
        guard let avatarURLString,
            let url = URL(string: avatarURLString),
            url.scheme?.lowercased() == "https"
        else { return nil }
        return url
    }

    var body: some View {
        Group {
            if let imageData, let image = UIImage(data: imageData) {
                Image(uiImage: image).resizable().scaledToFill()
            } else if let avatarURL {
                AsyncImage(url: avatarURL) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().scaledToFill()
                    default:
                        placeholder
                    }
                }
            } else {
                placeholder
            }
        }
        .frame(width: 62, height: 62)
        .clipShape(Circle())
        .overlay(Circle().strokeBorder(AppColors.primaryBlue.opacity(0.12), lineWidth: 1))
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        Circle()
            .fill(AppColors.segmentSelectedBg)
            .overlay {
                if let initial = name?.first {
                    Text(verbatim: String(initial).uppercased())
                        .font(.title2.weight(.medium))
                        .foregroundStyle(AppColors.segmentSelectedText)
                } else {
                    Image(systemName: "person.crop.circle")
                        .font(.title2)
                        .foregroundStyle(AppColors.segmentSelectedText)
                }
            }
    }
}
