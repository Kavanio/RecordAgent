import SwiftUI

/// 编号目录的统一标签；窄屏与大字体下将摘要移到标题下方。
struct ProfileDirectoryRow: View {
    let number: Int
    let title: LocalizedStringKey
    let summary: Text

    var body: some View {
        HStack(alignment: .center, spacing: 18) {
            Text(number, format: .number.precision(.integerLength(2)))
                .font(.caption.monospacedDigit())
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 20) {
                    Text(title).font(.title3.weight(.medium))
                    Spacer(minLength: 8)
                    summary.font(.caption).foregroundStyle(.secondary)
                }
                .fixedSize(horizontal: true, vertical: false)

                VStack(alignment: .leading, spacing: 8) {
                    Text(title).font(.title3.weight(.medium))
                    summary.font(.caption).foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Image(systemName: "arrow.up.right")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        }
        .foregroundStyle(.primary)
        .padding(.vertical, 26)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}
