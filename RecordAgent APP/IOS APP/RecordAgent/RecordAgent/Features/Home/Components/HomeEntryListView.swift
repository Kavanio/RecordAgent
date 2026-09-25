import SwiftUI

/// 待办、收支、资料、专注共用一条信息流；完成按钮与详情入口分离。
struct HomeEntryListView: View {
    let entries: [HomeEntry]
    let savingIDs: Set<String>
    let onToggle: (HomeEntry) -> Void
    let onOpen: (HomeEntry) -> Void
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 20) {
            if entries.isEmpty {
                ContentUnavailableView {
                    Label("home.day.empty", systemImage: "sun.horizon")
                } description: {
                    Text("home.day.empty_detail")
                }
            } else {
                ForEach(entries) { entry in
                    HStack(alignment: .top, spacing: 10) {
                        leading(entry)
                        Button {
                            onOpen(entry)
                        } label: {
                            VStack(alignment: .leading, spacing: 5) {
                                HStack(spacing: 8) {
                                    Text(entry.kind.category.title)
                                        .foregroundStyle(entry.kind.category.color)
                                    Text(entry.date, format: .dateTime.hour().minute())
                                        .foregroundStyle(.secondary)
                                }
                                .font(.caption2)
                                if typeSize.isAccessibilitySize {
                                    title(entry)
                                    accessory(entry)
                                } else {
                                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                                        title(entry)
                                        Spacer(minLength: 0)
                                        accessory(entry)
                                    }
                                }
                                if entry.kind == .document {
                                    Text(entry.detail)
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                        .lineLimit(2)
                                }
                            }
                            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                            .padding(.vertical, 3)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityHint(Text("home.entry.open"))
                    }
                }
            }
        }
        .animation(reduceMotion ? nil : .smooth(duration: 0.25), value: entries)
    }

    private func title(_ entry: HomeEntry) -> some View {
        Text(entry.title)
            .font(.subheadline.weight(.medium))
            .foregroundStyle(entry.isCompleted ? Color.secondary : .primary)
            .strikethrough(entry.kind == .todo && entry.isCompleted)
            .lineLimit(typeSize.isAccessibilitySize ? nil : 2)
            .multilineTextAlignment(.leading)
    }

    @ViewBuilder private func accessory(_ entry: HomeEntry) -> some View {
        switch entry.kind {
        case .expense, .income:
            Text(HomeFormat.money(entry.amountInCents, locale: locale))
                .font(.subheadline.monospacedDigit())
                .foregroundStyle(entry.kind.category.color)
                .fixedSize(horizontal: true, vertical: false)
        case .focus:
            Text(HomeFormat.minutes(entry.durationMinutes, locale: locale))
                .font(.caption.monospacedDigit())
                .foregroundStyle(entry.kind.category.color)
                .fixedSize(horizontal: true, vertical: false)
        case .todo, .document:
            EmptyView()
        }
    }

    @ViewBuilder private func leading(_ entry: HomeEntry) -> some View {
        if entry.kind == .todo {
            Button {
                onToggle(entry)
            } label: {
                Group {
                    if savingIDs.contains(entry.id) {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: entry.isCompleted ? "checkmark.circle.fill" : "circle")
                            .font(.title3.weight(.light))
                            .foregroundStyle(entry.isCompleted ? AppColors.primaryBlue : Color.secondary.opacity(0.55))
                            .contentTransition(reduceMotion ? .identity : .symbolEffect(.replace))
                    }
                }
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(savingIDs.contains(entry.id))
            .accessibilityLabel(Text(entry.title))
            .accessibilityValue(entry.isCompleted ? Text("home.todo.done") : Text("home.todo.pending"))
            .accessibilityHint(entry.isCompleted ? Text("home.todo.undo") : Text("home.todo.complete"))
        } else {
            Image(systemName: entry.kind.category.icon)
                .font(.system(size: 18, weight: .light))
                .foregroundStyle(entry.kind.category.color)
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)
        }
    }
}
