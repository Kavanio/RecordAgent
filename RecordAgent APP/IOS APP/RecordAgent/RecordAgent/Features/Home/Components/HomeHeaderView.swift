import SwiftUI

/// 首页顶部问候栏与快捷操作（搜索、通知）
public struct HomeHeaderView: View {
    @ObservedObject var viewModel: HomeViewModel

    public init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        HStack(alignment: .center) {
            // 左侧用户称谓与问候语
            VStack(alignment: .leading, spacing: 4) {
                Text("你好，\(viewModel.userName)")
                    .font(.system(size: 22, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)

                Text(viewModel.greetingSubtitle)
                    .font(.system(size: 13, weight: .regular))
                    .foregroundColor(AppColors.textSecondary)
            }

            Spacer()

            // 右侧搜索与通知图标
            HStack(spacing: 18) {
                Button(action: {
                    viewModel.showSearchAlert = true
                }) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 19, weight: .regular))
                        .foregroundColor(AppColors.textPrimary)
                }
                .accessibilityLabel("搜索")

                Button(action: {
                    viewModel.showNotificationAlert = true
                }) {
                    ZStack(alignment: .topTrailing) {
                        Image(systemName: "bell")
                            .font(.system(size: 19, weight: .regular))
                            .foregroundColor(AppColors.textPrimary)

                        if viewModel.unreadNotificationCount > 0 {
                            Circle()
                                .fill(Color(red: 1.0, green: 0.43, blue: 0.38)) // 柔和橙红未读指示点
                                .frame(width: 7, height: 7)
                                .overlay(
                                    Circle()
                                        .stroke(Color.white, lineWidth: 1.2)
                                )
                                .offset(x: 2, y: -2)
                        }
                    }
                }
                .accessibilityLabel("通知")
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .padding(.bottom, 8)
    }
}
