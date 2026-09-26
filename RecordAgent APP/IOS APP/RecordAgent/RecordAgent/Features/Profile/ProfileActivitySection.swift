import SwiftUI

/// 连续记录打卡刻度与最近条目共享同一快照；点打卡圆点可筛选，点条目回到原始详情。
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
            switch state {
            case .loading:
                ProgressView().frame(maxWidth: .infinity, minHeight: 90)
            case .unavailable:
                Text("profile.activity.unavailable")
                    .font(.subheadline)
                    .foregroundStyle(AppColors.textSecondary)
            case .ready(let month):
                continuousRecordCard(month)
                recent(month)
            }
        }
    }

    /// 连续记录卡片：顶部展示“连续记录”与天数箭头，下方横向排列 15 天星期及圆点打卡状态
    private func continuousRecordCard(_ month: ProfileActivityMonth) -> some View {
        let streakDays = month.recentStreakDays(calendar: calendar)

        return VStack(alignment: .leading, spacing: 14) {
            HStack(alignment: .firstTextBaseline) {
                Text("profile.streak.title")
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(AppColors.textPrimary)

                Spacer(minLength: 8)

                HStack(alignment: .firstTextBaseline, spacing: 4) {
                    Text(verbatim: "\(month.streakDays)")
                        .font(.title3.weight(.bold).monospacedDigit())
                        .foregroundStyle(AppColors.textPrimary)

                    Text("profile.streak.unit")
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.textSecondary)

                    Image(systemName: "chevron.right")
                        .font(.caption2.weight(.semibold))
                        .foregroundStyle(AppColors.textTertiary)
                        .padding(.leading, 1)
                }
                .accessibilityElement(children: .combine)
            }

            // 15 天打卡刻度（等间距横向自适应排布）
            HStack(spacing: 0) {
                ForEach(streakDays) { day in
                    VStack(spacing: 11) {
                        Text(day.weekdaySymbol)
                            .font(.caption2.weight(.medium))
                            .foregroundStyle(AppColors.textSecondary)
                            .frame(maxWidth: .infinity)

                        dayDot(day: day)
                            .frame(maxWidth: .infinity)
                    }
                }
            }
            .padding(.top, 4)
            .padding(.bottom, 2)
        }
        .padding(18)
        .background(
            Color(uiColor: .secondarySystemBackground).opacity(0.65),
            in: RoundedRectangle(cornerRadius: 22, style: .continuous)
        )
    }

    private func dayDot(day: StreakDay) -> some View {
        let isSelected = selectedDay.map { calendar.isDate($0, inSameDayAs: day.date) } ?? false

        return Button {
            selectedDay = isSelected ? nil : day.date
        } label: {
            ZStack {
                if day.isCompleted {
                    Circle()
                        .fill(
                            LinearGradient(
                                colors: [
                                    Color(red: 0.44, green: 0.70, blue: 1.0),
                                    AppColors.primaryBlue
                                ],
                                startPoint: .top,
                                endPoint: .bottom
                            )
                        )
                        .frame(width: 12, height: 12)
                } else {
                    Circle()
                        .fill(AppColors.progressTrackBg)
                        .frame(width: 11, height: 11)
                }

                if isSelected {
                    Circle()
                        .strokeBorder(AppColors.textPrimary, lineWidth: 1.5)
                        .frame(width: 20, height: 20)
                }
            }
            .frame(width: 22, height: 22)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(day.date, format: .dateTime.month().day()))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
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
