import SwiftUI

/// 四个常驻入口保持等宽；选中态仅改变前景色与字重。
struct MainNavigationBar: View {
    @Binding var selection: AppTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption) private var barHeight = 64.0

    var body: some View {
        HStack(spacing: 0) {
            ForEach(AppTab.primaryTabs) { tab in
                let isSelected = selection.navigationTab == tab
                Button {
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                        selection = tab
                    }
                } label: {
                    VStack(spacing: 5) {
                        Image(systemName: tab.icon)
                            .font(.system(size: 20, weight: isSelected ? .semibold : .regular))
                            .symbolVariant(isSelected ? .fill : .none)
                            .frame(height: 24)
                        Text(tab.navigationTitle)
                            .font(.caption.weight(isSelected ? .semibold : .regular))
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                    }
                    .foregroundStyle(isSelected ? AppColors.primaryBlue : AppColors.tabInactive)
                    .frame(maxWidth: .infinity)
                    .frame(height: barHeight)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(isSelected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 12)
        .background {
            AppColors.pageBackground
                .overlay(alignment: .top) {
                    Rectangle()
                        .fill(AppColors.inputBorder.opacity(0.7))
                        .frame(height: 0.5)
                }
                .ignoresSafeArea(edges: .bottom)
        }
        .sensoryFeedback(.selection, trigger: selection)
    }
}
