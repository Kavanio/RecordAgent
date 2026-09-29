import SwiftUI

/// 原生设置分组：标题位于浅底圆角容器之外，内部行自行声明分隔线。
struct AppSettingsGroup<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 4)
            VStack(spacing: 0, content: content)
                .background(Color(uiColor: .secondarySystemGroupedBackground))
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        }
    }
}
