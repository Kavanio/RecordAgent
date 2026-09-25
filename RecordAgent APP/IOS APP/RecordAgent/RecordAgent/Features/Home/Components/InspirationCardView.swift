import SwiftUI

/// 底部生活灵感语录与拍立得照片展示卡片
public struct InspirationCardView: View {
    public init() {}

    public var body: some View {
        HStack(alignment: .center, spacing: 14) {
            // 左侧生活语录
            VStack(alignment: .leading, spacing: 10) {
                // 装饰小碎片点缀
                HStack(spacing: 4) {
                    Image(systemName: "sparkle")
                        .font(.system(size: 10))
                        .foregroundColor(AppColors.primaryBlue.opacity(0.6))
                    Image(systemName: "leaf.fill")
                        .font(.system(size: 8))
                        .foregroundColor(Color(red: 0.55, green: 0.75, blue: 0.95))
                        .rotationEffect(.degrees(-20))
                }

                Text("持续记录，\n会让普通的日子闪闪发光。")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(Color(red: 0.28, green: 0.33, blue: 0.42)) // #47546B
                    .lineSpacing(4)

                Text("-- RecordAgent")
                    .font(.system(size: 12, weight: .regular, design: .serif))
                    .italic()
                    .foregroundColor(AppColors.textSecondary)
                    .padding(.leading, 24)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 8)

            // 右侧倾斜拍立得照片卡片
            polaroidCard
                .rotationEffect(.degrees(5))
                .padding(.trailing, 10)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    }

    /// 拍立得照片视图组件
    private var polaroidCard: some View {
        VStack(spacing: 8) {
            // 拍立得上部照片
            Image("PolaroidLandscape")
                .resizable()
                .scaledToFill()
                .frame(width: 96, height: 76)
                .clipped()
                .clipShape(RoundedRectangle(cornerRadius: 2, style: .continuous))

            // 拍立得底部留白手写文案与爱心
            HStack(spacing: 4) {
                Text("A better me.")
                    .font(.system(size: 10, weight: .medium, design: .serif))
                    .foregroundColor(Color(red: 0.35, green: 0.40, blue: 0.48))

                Spacer()

                Image(systemName: "heart")
                    .font(.system(size: 9, weight: .regular))
                    .foregroundColor(Color(red: 0.35, green: 0.40, blue: 0.48))
            }
            .padding(.horizontal, 2)
            .padding(.bottom, 2)
        }
        .padding(6)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 4, style: .continuous))
        .shadow(color: Color.black.opacity(0.12), radius: 6, x: 2, y: 4)
        .shadow(color: Color.black.opacity(0.04), radius: 1, x: 0, y: 1)
    }
}
