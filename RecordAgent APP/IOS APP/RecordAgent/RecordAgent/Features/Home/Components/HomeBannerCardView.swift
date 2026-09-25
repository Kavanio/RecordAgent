import SwiftUI

/// 首页主插画横幅卡片：展示 banner2.png 及精美名言语录
public struct HomeBannerCardView: View {
    public init() {}

    public var body: some View {
        ZStack(alignment: .topLeading) {
            // 背景插画图片（使用 banner2.png 对应 Assets: HomeBanner）
            Image("HomeBanner")
                .resizable()
                .scaledToFill()
                .frame(maxWidth: .infinity)
                .frame(height: 220)
                .clipped()

            // 顶部叠加诗意生活语录
            VStack(alignment: .leading, spacing: 6) {
                Text("生活不在别处，\n而在每一个被记录的当下。")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Color(red: 0.20, green: 0.24, blue: 0.30)) // #333D4D
                    .lineSpacing(4)

                Text("—")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundColor(Color(red: 0.30, green: 0.36, blue: 0.44))
            }
            .padding(.top, 24)
            .padding(.leading, 20)
        }
        .frame(height: 220)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .padding(.horizontal, 16)
    }
}
