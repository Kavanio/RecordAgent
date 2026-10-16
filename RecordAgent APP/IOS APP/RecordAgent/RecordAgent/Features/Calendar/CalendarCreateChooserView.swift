import SwiftUI

/// 日历新增入口沿用工作台的留白与图标网格，先选业务，再进入对应页面。
struct CalendarCreateChooserView: View {
    let onSelect: (CalendarEntryDraft.Kind) -> Void
    let onSelectFinance: () -> Void
    @Environment(\.dismiss) private var dismiss
    @ScaledMetric(relativeTo: .largeTitle) private var iconSize = 38.0

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 16), count: 2)

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    Spacer()
                    Button("common.cancel") { dismiss() }
                        .font(.title3.weight(.semibold))
                        .frame(minHeight: 44)
                }
                VStack(alignment: .leading, spacing: 8) {
                    Text("calendar.create.choose")
                        .font(.system(size: 34, weight: .bold))
                        .foregroundStyle(AppColors.textPrimary)
                    Text("calendar.create.choose.subtitle")
                        .font(.subheadline)
                        .foregroundStyle(AppColors.textSecondary)
                }
                .padding(.top, 16)
                .padding(.bottom, 46)

                LazyVGrid(columns: columns, spacing: 32) {
                    option("calendar.create.todo", icon: "checkmark.circle", action: { onSelect(.todo) })
                    option("calendar.create.schedule", icon: "calendar", action: { onSelect(.schedule) })
                    option("calendar.create.finance", icon: "arrow.up.right", action: onSelectFinance)
                    option("calendar.create.note", icon: "square.and.pencil", action: { onSelect(.note) })
                }
            }
            .padding(.horizontal, 32)
            .padding(.bottom, 32)
        }
        .background { Color(uiColor: .systemBackground).ignoresSafeArea() }
    }

    private func option(
        _ title: LocalizedStringKey, icon: String, action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            VStack(spacing: 10) {
                Image(systemName: icon)
                    .font(.system(size: iconSize, weight: .ultraLight))
                    .frame(height: iconSize + 8)
                Text(title)
                    .font(.headline)
                    .multilineTextAlignment(.center)
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(AppColors.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 94)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
