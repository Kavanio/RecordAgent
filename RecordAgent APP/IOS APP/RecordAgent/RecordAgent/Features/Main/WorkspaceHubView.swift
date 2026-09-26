import SwiftUI

/// 将四个低频业务入口收纳为一个清晰的目录，保留独立业务语义。
struct WorkspaceHubView: View {
    let onSelect: (AppTab) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("tab.workspace")
                    .font(.system(size: 34, weight: .bold, design: .rounded))
                    .foregroundStyle(AppColors.textPrimary)
                    .padding(.bottom, 10)

                Text("workspace.hub.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(AppColors.textSecondary)
                    .padding(.bottom, 44)

                ForEach(Array(AppTab.workspaceTabs.enumerated()), id: \.element) { index, tab in
                    Button { onSelect(tab) } label: {
                        HStack(spacing: 18) {
                            Text(index + 1, format: .number.precision(.integerLength(2)))
                                .font(.caption.monospacedDigit().weight(.medium))
                                .foregroundStyle(AppColors.textTertiary)
                                .frame(width: 22, alignment: .leading)

                            Image(systemName: tab.icon)
                                .font(.system(size: 21, weight: .light))
                                .foregroundStyle(AppColors.primaryBlue)
                                .frame(width: 30)

                            Text(tab.title)
                                .font(.title3.weight(.medium))
                                .foregroundStyle(AppColors.textPrimary)

                            Spacer(minLength: 0)

                            Image(systemName: "arrow.up.right")
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(AppColors.textTertiary)
                        }
                        .frame(minHeight: 84)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)

                    if index < AppTab.workspaceTabs.count - 1 {
                        Rectangle()
                            .fill(AppColors.inputBorder)
                            .frame(height: 1)
                    }
                }
            }
            .padding(.horizontal, 28)
            .padding(.top, 42)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background { HomeCanvas() }
    }
}
