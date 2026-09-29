import SwiftUI

struct HomeMonthPickerView: View {
    let selectedDate: Date
    let dates: HomeCalendar
    let onSelect: (Int, Int) -> Void
    @State private var year: Int
    @Environment(\.dismiss) private var dismiss

    init(selectedDate: Date, dates: HomeCalendar, onSelect: @escaping (Int, Int) -> Void) {
        self.selectedDate = selectedDate
        self.dates = dates
        self.onSelect = onSelect
        _year = State(initialValue: dates.calendar.component(.year, from: selectedDate))
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                HStack {
                    Text("home.month.choose").font(.headline)
                    Spacer()
                    Button("common.cancel") { dismiss() }.font(.subheadline)
                }
                HStack {
                    Button {
                        year -= 1
                    } label: {
                        Image(systemName: "chevron.left").frame(width: 44, height: 44)
                    }
                    .disabled(year <= 1900)
                    .accessibilityLabel("home.year.previous")
                    Spacer()
                    Picker("home.year", selection: $year) {
                        ForEach(1900...2100, id: \.self) { year in
                            Text(verbatim: String(year)).tag(year)
                        }
                    }
                    .pickerStyle(.menu)
                    .font(.title3.weight(.semibold))
                    Spacer()
                    Button {
                        year += 1
                    } label: {
                        Image(systemName: "chevron.right").frame(width: 44, height: 44)
                    }
                    .disabled(year >= 2100)
                    .accessibilityLabel("home.year.next")
                }
                LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 3), spacing: 10) {
                    ForEach(1...12, id: \.self) { month in
                        if let date = dates.calendar.date(from: DateComponents(year: year, month: month, day: 1)) {
                            let selected =
                                year == dates.calendar.component(.year, from: selectedDate)
                                && month == dates.calendar.component(.month, from: selectedDate)
                            Button {
                                onSelect(year, month)
                            } label: {
                                Text(date, format: .dateTime.month(.abbreviated))
                                    .font(.subheadline.weight(selected ? .semibold : .regular))
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .foregroundStyle(selected ? AppColors.primaryBlue : .primary)
                                    .background(selected ? AppColors.primaryBlue.opacity(0.1) : .clear, in: Capsule())
                            }
                            .accessibilityAddTraits(selected ? .isSelected : [])
                        }
                    }
                }
            }
            .padding(24)
        }
        .frame(idealWidth: 360)
        .buttonStyle(.plain)
        .tint(AppColors.primaryBlue)
    }
}
