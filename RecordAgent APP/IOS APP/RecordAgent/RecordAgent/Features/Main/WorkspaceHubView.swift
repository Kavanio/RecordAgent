import SwiftUI

/// 工作台以四个独立入口呈现业务域，保持与底部导航一致的选中语义。
struct WorkspaceHubView: View {
    let onSelect: (AppTab) -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 40.0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("tab.workspace")
                        .font(.system(size: 38, weight: .bold))
                        .foregroundStyle(AppColors.textPrimary)

                    Text("workspace.hub.subtitle")
                        .font(.subheadline)
                        .foregroundStyle(AppColors.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(.bottom, 64)

                LazyVGrid(columns: columns, alignment: .center, spacing: 52) {
                    ForEach(AppTab.workspaceTabs) { tab in
                        Button { onSelect(tab) } label: {
                            VStack(spacing: 10) {
                                Image(systemName: tab.icon)
                                    .font(.system(size: iconSize, weight: .ultraLight))
                                    .frame(height: iconSize + 8)

                                Text(tab.title)
                                    .font(.headline)
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.8)
                            }
                            .foregroundStyle(AppColors.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 88)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
            .padding(.horizontal, 32)
            .padding(.top, 68)
            .padding(.bottom, 40)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background { Color(uiColor: .systemBackground).ignoresSafeArea() }
    }
}
