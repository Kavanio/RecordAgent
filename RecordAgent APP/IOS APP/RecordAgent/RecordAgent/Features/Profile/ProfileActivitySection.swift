import SwiftUI

/// 月内活动刻度与最近条目共享同一快照；点日期筛选，点条目回到原始详情。
struct ProfileActivitySection: View {
    let state: ProfileActivityViewModel.State
    let onOpen: (HomeEntry) -> Void

    @Environment(\.calendar) private var calendar
    @State private var selectedDay: Date?

    private var month: ProfileActivityMonth? {
        if case .ready(let month) = state { return month }
        return nil
    }

    private var visibleEntries: [HomeEntry] {
        guard let month else { return [] }
        if let selectedDay {
            return month.days.first(where: { calendar.isDate($0.date, inSameDayAs: selectedDay) })?
                .entries
                .sorted { $0.date > $1.date } ?? []
        }
        return Array(month.latest.prefix(4))
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            HStack(alignment: .firstTextBaseline) {
                Text("profile.activity.title")
                    .font(.headline)
                    .foregroundStyle(AppColors.textPrimary)
                Spacer()
                if month?.isDemo == true {
                    Text("profile.overview.demo")
                        .font(.caption2)
                        .foregroundStyle(AppColors.textSecondary)
                }
            }
            switch state {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 90)
            case .unavailable:
                Text("profile.activity.unavailable")
                    .font(.subheadline)
                    .foregroundStyle(AppColors.textSecondary)
            case .ready(let month):
                monthGrid(month)
                recent(month)
            }
        }
    }

    private func monthGrid(_ month: ProfileActivityMonth) -> some View {
        let start =
            calendar.dateInterval(of: .month, for: .now)?.start ?? calendar.startOfDay(for: .now)
        let count = calendar.range(of: .day, in: .month, for: start)?.count ?? 30
        let offset = (calendar.component(.weekday, from: start) - calendar.firstWeekday + 7) % 7
        let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 7)
        return VStack(alignment: .leading, spacing: 13) {
            Text(start, format: .dateTime.year().month(.wide))
                .font(.subheadline.weight(.medium))
                .foregroundStyle(AppColors.textSecondary)
            HStack(spacing: 8) {
                ForEach(0..<7, id: \.self) { index in
                    let symbol = calendar.veryShortStandaloneWeekdaySymbols[
                        (calendar.firstWeekday - 1 + index) % 7]
                    Text(verbatim: symbol)
                        .font(.caption2)
                        .foregroundStyle(AppColors.textSecondary)
                        .frame(maxWidth: .infinity)
                }
            }
            LazyVGrid(columns: columns, spacing: 11) {
                ForEach(0..<(offset + count), id: \.self) { index in
                    if index < offset {
                        Color.clear.frame(height: 28)
                    } else if let date = calendar.date(
                        byAdding: .day, value: index - offset, to: start)
                    {
                        dayButton(date, month: month)
                    }
                }
            }
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemBackground).opacity(0.62),
            in: RoundedRectangle(cornerRadius: 22))
    }

    private func dayButton(_ date: Date, month: ProfileActivityMonth) -> some View {
        let count =
            month.days.first(where: { calendar.isDate($0.date, inSameDayAs: date) })?.entries.count
            ?? 0
        let selected = selectedDay.map { calendar.isDate($0, inSameDayAs: date) } ?? false
        let future = date > Date.now
        return Button {
            selectedDay = selected ? nil : date
        } label: {
            RoundedRectangle(cornerRadius: 5, style: .continuous)
                .fill(
                    count == 0
                        ? AppColors.progressTrackBg
                        : AppColors.primaryBlue.opacity(count >= 3 ? 0.86 : 0.4)
                )
                .frame(width: 19, height: 19)
                .overlay {
                    if selected {
                        RoundedRectangle(cornerRadius: 8)
                            .strokeBorder(AppColors.textPrimary, lineWidth: 1.5)
                            .frame(width: 27, height: 27)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 32)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(future)
        .accessibilityLabel(Text(date, format: .dateTime.month().day()))
        .accessibilityValue(Text("profile.activity.count \(count)"))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func recent(_ month: ProfileActivityMonth) -> some View {
        VStack(alignment: .leading, spacing: 15) {
            HStack {
                Text(
                    selectedDay == nil ? "profile.activity.recent" : "profile.activity.selectedDay"
                )
                .font(.headline)
                .foregroundStyle(AppColors.textPrimary)
                Spacer()
                if selectedDay != nil {
                    Button("profile.activity.showRecent") { selectedDay = nil }
                        .font(.caption)
                }
            }
            if visibleEntries.isEmpty {
                Text("profile.activity.empty")
                    .font(.subheadline)
                    .foregroundStyle(AppColors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.vertical, 14)
            } else {
                ForEach(visibleEntries) { entry in
                    Button {
                        onOpen(entry)
                    } label: {
                        HStack(spacing: 13) {
                            Image(systemName: entry.kind.category.icon)
                                .font(.body)
                                .foregroundStyle(entry.kind.category.color)
                                .frame(width: 30)
                            VStack(alignment: .leading, spacing: 4) {
                                Text(entry.title)
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(AppColors.textPrimary)
                                    .lineLimit(2)
                                Text(entry.date, format: .dateTime.month().day().hour().minute())
                                    .font(.caption)
                                    .foregroundStyle(AppColors.textSecondary)
                            }
                            Spacer(minLength: 0)
                            Image(systemName: "arrow.up.right")
                                .font(.caption)
                                .foregroundStyle(AppColors.textTertiary)
                        }
                        .frame(minHeight: 52)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            if month.isDemo {
                Text("profile.activity.demoHint")
                    .font(.caption2)
                    .foregroundStyle(AppColors.textSecondary)
            }
        }
    }
}
