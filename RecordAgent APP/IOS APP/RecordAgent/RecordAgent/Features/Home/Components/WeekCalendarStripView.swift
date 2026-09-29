import SwiftUI

/// 周历采用原生分页滚动，滑动保留星期位置，月份选择与内容日期同步。
struct WeekCalendarStripView: View {
    @ObservedObject var viewModel: HomeViewModel
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @ScaledMetric(relativeTo: .caption) private var weekHeight = 84.0
    @State private var visibleWeek: Date?
    @State private var showMonths = false
    @State private var pendingWeekSelection: Task<Void, Never>?
    @State private var programmaticWeek: Date?

    init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
        _visibleWeek = State(initialValue: viewModel.dates.startOfWeek(containing: viewModel.selectedDate))
    }

    var body: some View {
        VStack(spacing: 6) {
            HStack {
                Button {
                    showMonths = true
                } label: {
                    HStack(spacing: 7) {
                        Text(viewModel.selectedDate, format: .dateTime.year().month(.wide))
                            .font(.subheadline.weight(.semibold))
                        Image(systemName: "chevron.down").font(.caption2.weight(.bold))
                    }
                    .foregroundStyle(.primary)
                    .frame(minHeight: 44)
                }
                .accessibilityHint(Text("home.month.choose"))
                .popover(isPresented: $showMonths) {
                    HomeMonthPickerView(selectedDate: viewModel.selectedDate, dates: viewModel.dates) { year, month in
                        viewModel.selectMonth(year: year, month: month)
                        showMonths = false
                    }
                    .presentationCompactAdaptation(.sheet)
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                }
                Spacer()
                Button("home.today") { viewModel.selectDate(.now) }
                    .font(.caption.weight(.medium))
                    .frame(minWidth: 44, minHeight: 44)
                    .opacity(viewModel.dates.calendar.isDateInToday(viewModel.selectedDate) ? 0.45 : 1)
                    .disabled(viewModel.dates.calendar.isDateInToday(viewModel.selectedDate))
            }
            .padding(.horizontal, 24)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 0) {
                    ForEach(viewModel.weekStarts, id: \.self) { week in
                        HStack(spacing: 0) {
                            ForEach(viewModel.dates.days(inWeek: week), id: \.self) { date in
                                dayButton(date)
                            }
                        }
                        .containerRelativeFrame(.horizontal)
                        .id(week)
                    }
                }
                .scrollTargetLayout()
            }
            .frame(height: weekHeight)
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $visibleWeek)
            .onChange(of: visibleWeek) { _, week in
                pendingWeekSelection?.cancel()
                guard let week else { return }
                // 月份跳转会改变滚动位置，不应被当成一次反向滑动。
                if let programmaticWeek {
                    if week == programmaticWeek { self.programmaticWeek = nil }
                    return
                }
                pendingWeekSelection = Task { @MainActor in
                    try? await Task.sleep(for: .milliseconds(130))
                    guard !Task.isCancelled, visibleWeek == week else { return }
                    withAnimation(reduceMotion ? nil : .smooth(duration: 0.28)) {
                        viewModel.selectWeek(week)
                    }
                }
            }
            .onChange(of: viewModel.selectedDate) { _, date in
                let week = viewModel.dates.startOfWeek(containing: date)
                guard visibleWeek != week else { return }
                pendingWeekSelection?.cancel()
                programmaticWeek = week
                let distance =
                    visibleWeek.flatMap {
                        viewModel.dates.calendar.dateComponents([.day], from: $0, to: week).day
                    } ?? 0
                withAnimation(reduceMotion || abs(distance) > 21 ? nil : .smooth(duration: 0.38)) {
                    visibleWeek = week
                }
            }
            .onDisappear { pendingWeekSelection?.cancel() }
        }
        .buttonStyle(.plain)
    }

    private func dayButton(_ date: Date) -> some View {
        let selected = viewModel.dates.calendar.isDate(date, inSameDayAs: viewModel.selectedDate)
        let today = viewModel.dates.calendar.isDateInToday(date)
        return Button {
            withAnimation(reduceMotion ? nil : .smooth(duration: 0.26)) {
                viewModel.selectDate(date)
            }
        } label: {
            VStack(spacing: 7) {
                Text(date, format: .dateTime.weekday(.narrow))
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                Text(date, format: .dateTime.day())
                    .font(.subheadline.weight(selected ? .semibold : .regular))
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)
                    .monospacedDigit()
                    .foregroundStyle(selected ? Color.white : Color.primary)
                    .frame(minWidth: 34, minHeight: 34)
                    .padding(2)
                    .background { if selected { Circle().fill(AppColors.primaryBlue) } }
                Circle()
                    .fill(today ? AppColors.primaryBlue : .clear)
                    .frame(width: 4, height: 4)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 3)
            .contentShape(Rectangle())
        }
        .disabled(!viewModel.dates.supportedDates.contains(date))
        .accessibilityLabel(Text(date, format: .dateTime.year().month().day().weekday()))
        .accessibilityValue(today ? Text("home.today") : Text(verbatim: ""))
        .accessibilityAddTraits(selected ? .isSelected : [])
        .animation(reduceMotion ? nil : .smooth(duration: 0.22), value: selected)
    }
}
