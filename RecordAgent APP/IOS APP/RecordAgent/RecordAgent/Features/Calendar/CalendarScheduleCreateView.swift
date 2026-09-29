import SwiftUI

/// 日程以日期时间为视觉轴线，名称与补充说明围绕具体安排展开。
struct CalendarScheduleCreateView: View {
    @Binding var draft: CalendarEntryDraft
    let dates: HomeCalendar

    var body: some View {
        VStack(alignment: .leading, spacing: 26) {
            TextField("calendar.create.schedule.placeholder", text: $draft.title)
                .font(.system(size: 20, weight: .medium))
                .submitLabel(.done)
                .padding(.vertical, 10)
                .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }

            HStack(alignment: .top, spacing: 18) {
                RoundedRectangle(cornerRadius: 2)
                    .fill(AppColors.overviewSchedule)
                    .frame(width: 3)
                DatePicker("calendar.create.schedule.time", selection: $draft.date,
                           in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
                    .datePickerStyle(.compact)
                    .padding(.vertical, 12)
            }
            .fixedSize(horizontal: false, vertical: true)

            TextField("calendar.create.schedule.detail_placeholder", text: $draft.detail,
                      axis: .vertical)
                .lineLimit(3...6)
                .padding(14)
                .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}
