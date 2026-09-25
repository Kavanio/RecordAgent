import SwiftUI

/// 首页顶部问候栏与快捷操作（搜索、通知）
struct HomeHeaderView: View {
    @ObservedObject var viewModel: HomeViewModel

    init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
    }

    var body: some View {
        HStack(alignment: .center) {
            // 左侧用户称谓与问候语
            VStack(alignment: .leading, spacing: 4) {
                Text("home.greeting")
                    .font(.title2.bold())
                    .foregroundColor(AppColors.textPrimary)

                Text("home.motto")
                    .font(.footnote)
                    .foregroundColor(AppColors.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }

            Spacer()

            // 右侧搜索与通知图标
            HStack(spacing: 2) {
                Button(action: {
                    viewModel.showSearchAlert = true
                }) {
                    Image(systemName: "magnifyingglass")
                        .font(.system(size: 19, weight: .regular))
                        .foregroundColor(AppColors.textPrimary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("home.search.title"))

                Button(action: {
                    viewModel.showNotificationAlert = true
                }) {
                    Image(systemName: "bell")
                        .font(.system(size: 19, weight: .regular))
                        .foregroundColor(AppColors.textPrimary)
                        .frame(minWidth: 44, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(Text("home.notifications.title"))
            }
        }
        .padding(.horizontal, 20)
        .padding(.top, 6)
        .padding(.bottom, 8)
    }
}
