import SwiftUI

/// 设置行统一留白与可访问字号布局，交互由调用方提供。
struct AppSettingsRow<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let content: () -> Content
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout =
            dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(alignment: .center, spacing: 20))
        layout {
            Text(title)
                .foregroundStyle(.primary)
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 8) }
            content()
        }
        .font(.body)
        .padding(20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
