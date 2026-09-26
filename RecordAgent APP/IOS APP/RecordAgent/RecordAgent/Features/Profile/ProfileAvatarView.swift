import SwiftUI
import UIKit

/// 显示账户头像；尚无头像或加载失败时保留同尺寸的字母印记。
struct ProfileAvatarView: View {
    let name: String?
    let avatarURLString: String?
    var imageData: Data? = nil
    var size: CGFloat = 72
    var showBorder: Bool = true

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
        .frame(width: size, height: size)
        .clipShape(Circle())
        .overlay {
            if showBorder {
                Circle().strokeBorder(Color.white, lineWidth: 2.5)
            }
        }
        .shadow(color: Color.black.opacity(0.18), radius: 6, x: 0, y: 3)
        .accessibilityHidden(true)
    }

    private var placeholder: some View {
        Circle()
            .fill(AppColors.segmentSelectedBg)
            .overlay {
                if let initial = name?.first {
                    Text(verbatim: String(initial).uppercased())
                        .font(.system(size: size * 0.42, weight: .bold))
                        .foregroundStyle(AppColors.segmentSelectedText)
                } else {
                    Image(systemName: "person.crop.circle")
                        .font(.system(size: size * 0.45))
                        .foregroundStyle(AppColors.segmentSelectedText)
                }
            }
    }
}
