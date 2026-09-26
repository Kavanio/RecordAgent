import SwiftUI

/// 全宽轻材质导航底座；选中图标的柔色胶囊在七个入口间连续移动。
struct MainNavigationBar: View {
    @Binding var selection: AppTab
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption2) private var minimumItemWidth = 46.0
    @ScaledMetric(relativeTo: .caption2) private var barHeight = 72.0
    @Namespace private var indicator

    var body: some View {
        GeometryReader { geometry in
            ScrollViewReader { proxy in
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 0) {
                        ForEach(AppTab.allCases) { tab in
                            let isSelected = selection == tab
                            Button {
                                withAnimation(reduceMotion ? nil : .spring(response: 0.36, dampingFraction: 0.82)) {
                                    selection = tab
                                }
                            } label: {
                                VStack(spacing: 5) {
                                    Image(systemName: tab.icon)
                                        .font(.system(size: 19, weight: isSelected ? .semibold : .regular))
                                        .symbolVariant(isSelected ? .fill : .none)
                                        .foregroundStyle(isSelected ? AppColors.primaryBlue : Color.secondary)
                                        .scaleEffect(isSelected ? 1.08 : 1)
                                        .frame(width: 39, height: 34)
                                        .background {
                                            if isSelected {
                                                Capsule()
                                                    .fill(AppColors.primaryBlue.opacity(0.15))
                                                    .matchedGeometryEffect(id: "selectedTab", in: indicator)
                                            }
                                        }
                                    Text(tab.title)
                                        .font(.caption2.weight(isSelected ? .semibold : .regular))
                                        .foregroundStyle(isSelected ? AppColors.textPrimary : Color.secondary)
                                        .lineLimit(1)
                                        .minimumScaleFactor(0.8)
                                }
                                .frame(width: max((geometry.size.width - 16) / 7, minimumItemWidth), height: barHeight)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(NavigationPressStyle(reduceMotion: reduceMotion))
                            .accessibilityAddTraits(isSelected ? .isSelected : [])
                            .id(tab)
                        }
                    }
                    .padding(.horizontal, 8)
                }
                .onChange(of: selection) { _, tab in
                    withAnimation(reduceMotion ? nil : .smooth(duration: 0.3)) { proxy.scrollTo(tab, anchor: .center) }
                }
            }
        }
        .frame(height: barHeight)
        .background {
            Rectangle()
                .fill(.ultraThinMaterial)
                .overlay(alignment: .top) {
                    LinearGradient(
                        colors: [AppColors.skyLightBlue.opacity(0.28), .clear],
                        startPoint: .top,
                        endPoint: .bottom
                    )
                    .frame(height: 42)
                }
                .ignoresSafeArea(edges: .bottom)
        }
        .shadow(color: .black.opacity(0.055), radius: 16, y: -4)
        .sensoryFeedback(.selection, trigger: selection)
    }
}

private struct NavigationPressStyle: ButtonStyle {
    let reduceMotion: Bool

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed && !reduceMotion ? 0.92 : 1)
            .animation(
                reduceMotion ? nil : .spring(response: 0.24, dampingFraction: 0.78), value: configuration.isPressed)
    }
}
