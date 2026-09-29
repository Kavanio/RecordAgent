import SwiftUI

/// 日程以日期时间为视觉轴线，名称与补充说明围绕具体安排展开。
struct CalendarScheduleCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 30) {
            VStack(alignment: .leading, spacing: 10) {
                Image(systemName: "calendar")
                    .font(.system(size: 35, weight: .ultraLight))
                    .foregroundStyle(AppColors.overviewSchedule)
                Text("calendar.create.schedule.heading")
                    .font(.system(size: 31, weight: .semibold))
                    .foregroundStyle(CalendarPalette.ink)
                Text("calendar.create.schedule.subtitle")
                    .font(.subheadline)
                    .foregroundStyle(CalendarPalette.secondary)
            }

            VStack(alignment: .leading, spacing: 11) {
                Text("calendar.create.schedule.name")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.secondary)
                TextField("calendar.create.schedule.placeholder", text: $draft.title)
                    .font(.title2.weight(.medium))
                    .submitLabel(.done)
                    .padding(.vertical, 10)
                    .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }
            }

            HStack(alignment: .top, spacing: 18) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(AppColors.overviewSchedule)
                    .frame(width: 3)
                VStack(alignment: .leading, spacing: 18) {
                    Label("calendar.create.schedule.time", systemImage: "clock")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(CalendarPalette.ink)
                    DatePicker("home.date.choose", selection: $draft.date,
                               in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
                        .datePickerStyle(.compact)
                    Text("calendar.create.schedule.time_hint")
                        .font(.footnote)
                        .foregroundStyle(CalendarPalette.secondary)
                }
                .padding(.vertical, 18)
            }
            .fixedSize(horizontal: false, vertical: true)

            VStack(alignment: .leading, spacing: 9) {
                Label("calendar.create.schedule.detail", systemImage: "text.alignleft")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(CalendarPalette.ink)
                TextField("calendar.create.schedule.detail_placeholder", text: $draft.detail,
                          axis: .vertical)
                    .lineLimit(3...6)
                    .padding(14)
                    .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
            }
        }
    }
}
