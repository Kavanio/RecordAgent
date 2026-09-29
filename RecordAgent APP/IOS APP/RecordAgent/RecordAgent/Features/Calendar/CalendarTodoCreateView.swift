import SwiftUI

/// 待办以事项为核心，时间和周期规则为可选安排。
struct CalendarTodoCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "checkmark.circle")
                    .font(.system(size: 35, weight: .ultraLight))
                    .foregroundStyle(CalendarPalette.accent)
                Text("calendar.create.todo.heading")
                    .font(.system(size: 31, weight: .semibold))
                    .foregroundStyle(CalendarPalette.ink)
                Text("calendar.create.todo.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(CalendarPalette.secondary)
            }

            HStack(alignment: .firstTextBaseline, spacing: 14) {
                Image(systemName: "circle")
                    .font(.title2.weight(.ultraLight))
                    .foregroundStyle(CalendarPalette.accent)
                TextField("calendar.create.todo.placeholder", text: $draft.title)
                    .font(.title3.weight(.medium))
                    .submitLabel(.done)
            }
            .padding(.vertical, 12)
            .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }

            VStack(alignment: .leading, spacing: 16) {
                Label("calendar.create.todo.when", systemImage: "clock")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
                DatePicker("home.date.choose", selection: $draft.date,
                           in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
                    .datePickerStyle(.compact)
            }

            VStack(alignment: .leading, spacing: 14) {
                Label("calendar.create.repeat", systemImage: "repeat")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
                HStack(spacing: 8) {
                    repeatOption("calendar.repeat.none", period: nil)
                    repeatOption("calendar.repeat.week", period: .weekly)
                    repeatOption("calendar.repeat.month", period: .monthly)
                }
                if draft.period != nil {
                    Text("calendar.create.period_hint")
                        .font(.footnote)
                        .foregroundStyle(CalendarPalette.secondary)
                }
            }

            VStack(alignment: .leading, spacing: 9) {
                Text("calendar.create.todo.notes")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
                TextField("calendar.create.detail", text: $draft.detail, axis: .vertical)
                    .lineLimit(2...4)
                    .padding(14)
                    .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }

    private func repeatOption(_ title: LocalizedStringKey, period: CalendarTodoPeriod?) -> some View {
        let selected = draft.period == period
        return Button { draft.period = period } label: {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .regular))
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .frame(maxWidth: .infinity, minHeight: 44)
                .foregroundStyle(selected ? CalendarPalette.accent : CalendarPalette.secondary)
                .background(selected ? CalendarPalette.accent.opacity(0.1) : AppColors.segmentBarBg,
                            in: Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}
