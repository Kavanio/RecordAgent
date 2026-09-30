import SwiftUI

/// 图表专用等宽分段控件；不使用横向滚动，手机窄屏也能同时看到所有选项。
struct FinanceChartTabs<Value: Hashable>: View {
    let label: LocalizedStringKey
    let options: [Value]
    @Binding var selection: Value
    let title: (Value) -> LocalizedStringKey

    var body: some View {
        HStack(spacing: 4) {
            ForEach(options, id: \.self) { option in
                Button {
                    selection = option
                } label: {
                    Text(title(option))
                        .font(.subheadline.weight(selection == option ? .semibold : .regular))
                        .foregroundStyle(selection == option ? FinanceVisualStyle.accent : Color.secondary)
                        .lineLimit(1).minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(
                            selection == option ? FinanceVisualStyle.accent.opacity(0.14) : .clear,
                            in: RoundedRectangle(cornerRadius: 12))
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == option ? .isSelected : [])
            }
        }
        .accessibilityElement(children: .contain).accessibilityLabel(label)
    }
}
