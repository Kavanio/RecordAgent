import SwiftUI

/// 首页主插画横幅：通栏展示 banner2.png，带有上下平滑自然过渡渐变
public struct HomeBannerCardView: View {
    public init() {}

    public var body: some View {
        ZStack(alignment: .topLeading) {
            // 背景插画图片：通栏撑满，无卡片框与外边距
            Image("HomeBanner")
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: 250)
                .clipped()

            // 顶部过渡渐变：与周历区域的淡蓝天空自然融合
            LinearGradient(
                colors: [
                    Color(red: 0.93, green: 0.96, blue: 1.0),
                    Color(red: 0.93, green: 0.96, blue: 1.0).opacity(0.6),
                    Color.clear
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .frame(height: 48)

            // 底部过渡渐变：向下方背景与概览卡片平滑淡出
            VStack {
                Spacer()
                LinearGradient(
                    colors: [
                        Color.clear,
                        AppColors.pageBackground.opacity(0.35),
                        AppColors.pageBackground.opacity(0.85),
                        AppColors.pageBackground
                    ],
                    startPoint: .top,
                    endPoint: .bottom
                )
                .frame(height: 70)
            }

            // 顶部叠加诗意生活语录
            VStack(alignment: .leading, spacing: 6) {
                Text("home.banner.quote")
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundColor(Color(red: 0.20, green: 0.24, blue: 0.30)) // #333D4D
                    .lineSpacing(4)
                    .shadow(color: Color.white.opacity(0.6), radius: 2, x: 0, y: 1)

                Text("—")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color(red: 0.30, green: 0.36, blue: 0.44))
            }
            .padding(.top, 20)
            .padding(.leading, 24)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 250)
    }
}
