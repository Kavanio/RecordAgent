import SwiftUI

/// 首页主插画横幅：图片透明度融入页面底色，保留原图的天空与人物细节。
struct HomeBannerCardView: View {
    init() {}

    var body: some View {
        ZStack(alignment: .topLeading) {
            Image("HomeBanner")
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: 170)
                .clipped()
                .mask {
                    // 透明遮罩让实际页面背景透出，避免固定颜色渐变形成色带。
                    LinearGradient(
                        stops: [
                            .init(color: .clear, location: 0),
                            .init(color: .white, location: 0.17),
                            .init(color: .white, location: 0.58),
                            .init(color: .clear, location: 1),
                        ],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                }

            // 顶部叠加诗意生活语录
            VStack(alignment: .leading, spacing: 6) {
                Text("home.banner.quote")
                    .font(.footnote.weight(.medium))
                    .foregroundColor(Color(red: 0.20, green: 0.24, blue: 0.30))  // #333D4D
                    .lineSpacing(4)
                    .shadow(color: Color.white.opacity(0.6), radius: 2, x: 0, y: 1)

                Text("—")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color(red: 0.30, green: 0.36, blue: 0.44))
            }
            .padding(.top, 12)
            .padding(.leading, 24)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 170)
    }
}
