import SwiftUI

/// 日历的收支记录页；账户与流水服务接入前不提供虚假的保存操作。
struct CalendarFinanceCreateView: View {
    let dates: HomeCalendar
    @State private var entryType: EntryType = .expense
    @State private var amount = ""
    @State private var category = ""
    @State private var note = ""
    @State private var date: Date

    init(date: Date, dates: HomeCalendar) {
        self.dates = dates
        _date = State(initialValue: date)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 8) {
                    ForEach(EntryType.allCases) { type in
                        Button { entryType = type } label: {
                            Text(type.title)
                                .font(.subheadline.weight(entryType == type ? .semibold : .regular))
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .foregroundStyle(entryType == type ? CalendarPalette.ink : CalendarPalette.secondary)
                                .background(entryType == type ? AppColors.segmentSelectedBg : AppColors.segmentBarBg,
                                            in: Capsule())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(entryType == type ? .isSelected : [])
                    }
                }

                VStack(alignment: .leading, spacing: 9) {
                    Text("calendar.finance.amount")
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(CalendarPalette.secondary)
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Text("¥").font(.title3.weight(.medium))
                        TextField("0.00", text: $amount)
                            .keyboardType(.decimalPad)
                            .font(.system(size: 32, weight: .medium, design: .rounded))
                            .accessibilityLabel("calendar.finance.amount")
                    }
                    .foregroundStyle(CalendarPalette.ink)
                    .padding(.vertical, 8)
                    .overlay(alignment: .bottom) { Rectangle().fill(AppColors.inputBorder).frame(height: 1) }
                }

                VStack(alignment: .leading, spacing: 20) {
                    TextField("calendar.finance.category", text: $category)
                        .financeInputStyle()
                    field("calendar.finance.account", icon: "creditcard") {
                        Text("calendar.finance.account_unavailable")
                            .foregroundStyle(CalendarPalette.secondary)
                    }
                    TextField("calendar.finance.note", text: $note, axis: .vertical)
                        .lineLimit(2...4)
                        .financeInputStyle()
                    DatePicker("calendar.finance.date", selection: $date,
                               in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
                        .datePickerStyle(.compact)
                }
            }
            .frame(maxWidth: 640, alignment: .leading)
            .frame(maxWidth: .infinity)
            .padding(.horizontal, 26)
            .padding(.top, 28)
            .padding(.bottom, 28)
        }
        .scrollDismissesKeyboard(.interactively)
        .background { Color(uiColor: .systemBackground).ignoresSafeArea() }
        .navigationTitle("calendar.create.finance")
        .navigationBarTitleDisplayMode(.inline)
        .safeAreaInset(edge: .bottom) {
            AppButton("calendar.finance.save", iconName: "checkmark", isEnabled: false, action: {})
                .frame(maxWidth: 640)
                .padding(.horizontal, 26)
                .padding(.vertical, 12)
                .frame(maxWidth: .infinity)
                .background { Color(uiColor: .systemBackground).ignoresSafeArea(edges: .bottom) }
        }
    }

    private func field<Content: View>(
        _ title: LocalizedStringKey, icon: String, @ViewBuilder content: () -> Content
    ) -> some View {
        VStack(alignment: .leading, spacing: 9) {
            Label(title, systemImage: icon)
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(CalendarPalette.ink)
            content()
                .font(.body)
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
        }
    }
}

private extension View {
    func financeInputStyle() -> some View {
        self.padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(AppColors.segmentBarBg, in: RoundedRectangle(cornerRadius: 14))
    }
}

private enum EntryType: String, CaseIterable, Identifiable {
    case expense, income
    var id: String { rawValue }
    var title: LocalizedStringKey {
        self == .expense ? "calendar.finance.expense" : "calendar.finance.income"
    }
}
