import SwiftUI
import UIKit

/// 显示账户头像；未设置或加载失败时显示轻量豆子形象。
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
            .fill(Color(red: 0.99, green: 0.96, blue: 0.91))
            .overlay {
                Canvas { context, size in
                    let w = size.width
                    let h = size.height
                    var bean = Path()
                    bean.move(to: CGPoint(x: w * 0.33, y: h * 0.13))
                    bean.addCurve(
                        to: CGPoint(x: w * 0.77, y: h * 0.17),
                        control1: CGPoint(x: w * 0.48, y: h * 0.01),
                        control2: CGPoint(x: w * 0.72, y: h * 0.04))
                    bean.addCurve(
                        to: CGPoint(x: w * 0.88, y: h * 0.54),
                        control1: CGPoint(x: w * 0.95, y: h * 0.26),
                        control2: CGPoint(x: w * 0.78, y: h * 0.39))
                    bean.addCurve(
                        to: CGPoint(x: w * 0.67, y: h * 0.88),
                        control1: CGPoint(x: w * 0.98, y: h * 0.74),
                        control2: CGPoint(x: w * 0.82, y: h * 0.90))
                    bean.addCurve(
                        to: CGPoint(x: w * 0.22, y: h * 0.81),
                        control1: CGPoint(x: w * 0.46, y: h * 0.98),
                        control2: CGPoint(x: w * 0.23, y: h * 0.94))
                    bean.addCurve(
                        to: CGPoint(x: w * 0.33, y: h * 0.13),
                        control1: CGPoint(x: w * 0.06, y: h * 0.60),
                        control2: CGPoint(x: w * 0.12, y: h * 0.26))
                    context.fill(bean, with: .color(Color(red: 0.72, green: 0.48, blue: 0.32)))
                    context.stroke(
                        bean, with: .color(Color(red: 0.53, green: 0.32, blue: 0.23)),
                        lineWidth: 1.2)

                    let ink = Color(red: 0.26, green: 0.18, blue: 0.16)
                    for x in [CGFloat(0.43), 0.65] {
                        context.fill(
                            Path(
                                ellipseIn: CGRect(
                                    x: w * x, y: h * 0.42, width: w * 0.055, height: h * 0.07)),
                            with: .color(ink))
                    }
                    for x in [CGFloat(0.31), 0.70] {
                        context.fill(
                            Path(
                                ellipseIn: CGRect(
                                    x: w * x, y: h * 0.55, width: w * 0.10, height: h * 0.055)),
                            with: .color(Color(red: 0.94, green: 0.66, blue: 0.57).opacity(0.82)))
                    }
                    var smile = Path()
                    smile.move(to: CGPoint(x: w * 0.47, y: h * 0.62))
                    smile.addQuadCurve(
                        to: CGPoint(x: w * 0.62, y: h * 0.61),
                        control: CGPoint(x: w * 0.55, y: h * 0.73))
                    context.stroke(
                        smile, with: .color(ink),
                        style: StrokeStyle(lineWidth: 1.7, lineCap: .round))
                }
            }
    }
}
