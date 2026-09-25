import SwiftUI

/// 七个直接入口；普通字号均分宽度，大字体时允许水平滚动而不压缩标签。
struct MainNavigationBar: View {
    @Binding var selection: AppTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption2) private var minimumItemWidth = 44.0
    @ScaledMetric(relativeTo: .caption2) private var barHeight = 62.0
    @Namespace private var indicator

    var body: some View {
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 0) {
                        ForEach(AppTab.allCases) { tab in
                            Button {
                                selection = tab
                            } label: {
                                VStack(spacing: 5) {
                                    Image(systemName: tab.icon)
                                        .font(.system(size: 19, weight: selection == tab ? .semibold : .regular))
                                        .symbolVariant(selection == tab ? .fill : .none)
                                    Text(tab.title)
                                        .font(.caption2.weight(selection == tab ? .semibold : .regular))
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                    ZStack {
                                        Color.clear.frame(width: 12, height: 3)
                                        if selection == tab {
                                            Capsule().fill(AppColors.primaryBlue)
                                                .frame(width: 12, height: 3)
                                                .matchedGeometryEffect(id: "active-tab", in: indicator)
                                        }
                                    }
                                    .accessibilityHidden(true)
                                }
                                .foregroundStyle(selection == tab ? AppColors.primaryBlue : Color.secondary)
                                .frame(width: max(geometry.size.width / 7, minimumItemWidth), height: barHeight)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityAddTraits(selection == tab ? .isSelected : [])
                            .id(tab)
                        }
                    }
                }
                .onChange(of: selection) { _, tab in
                    withAnimation(reduceMotion ? nil : .smooth(duration: 0.28)) { proxy.scrollTo(tab) }
                }
            }
        }
        .frame(height: barHeight)
        .background(.ultraThinMaterial)
        .background(Color(uiColor: .systemBackground).opacity(0.8).ignoresSafeArea(edges: .bottom))
        .animation(reduceMotion ? nil : .smooth(duration: 0.28), value: selection)
    }
}
