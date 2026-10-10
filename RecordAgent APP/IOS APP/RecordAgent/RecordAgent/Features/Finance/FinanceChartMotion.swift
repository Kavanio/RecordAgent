import SwiftUI

/// 只揭示最终几何图形，不插值或改写金额；系统减少动态效果时直接显示完整结果。
struct FinanceChartReveal: ViewModifier {
    let key: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var revealed = false

    func body(content: Content) -> some View {
        content.mask(alignment: .leading) {
            GeometryReader { geometry in
                Rectangle().frame(width: geometry.size.width * (revealed || reduceMotion ? 1 : 0))
            }
        }
        .task(id: key) {
            revealed = false
            guard !reduceMotion else {
                revealed = true
                return
            }
            do { try await Task.sleep(for: .milliseconds(30)) } catch { return }
            withAnimation(.easeOut(duration: 0.75)) { revealed = true }
        }
    }
}
