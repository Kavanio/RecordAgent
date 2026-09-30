import SwiftUI

/// 日历收支记录页；保存后从服务端重新读取账户与当日投影。
struct CalendarFinanceCreateView: View {
    let dates: HomeCalendar
    let onSaved: () -> Void
    @StateObject private var model: FinanceCreateViewModel
    @State private var entryType: EntryType = .expense
    @State private var amount = ""
    @State private var category = ""
    @State private var note = ""
    @State private var date: Date
    @State private var showsAccountForm = false
    @State private var accountName = ""
    @State private var openingBalance = ""
    @State private var showsError = false

    init(
        date: Date, dates: HomeCalendar, service: any FinanceServiceProtocol,
        onSaved: @escaping () -> Void
    ) {
        self.dates = dates
        self.onSaved = onSaved
        _model = StateObject(wrappedValue: FinanceCreateViewModel(service: service))
        _date = State(initialValue: date)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                HStack(spacing: 8) {
                    ForEach(EntryType.allCases) { type in
                        Button {
                            entryType = type
                        } label: {
                            Text(type.title)
                                .font(.subheadline.weight(entryType == type ? .semibold : .regular))
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .foregroundStyle(entryType == type ? CalendarPalette.ink : CalendarPalette.secondary)
                                .background(
                                    entryType == type ? AppColors.segmentSelectedBg : AppColors.segmentBarBg,
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
                    FinanceCategoryPicker(type: entryType.rawValue, category: $category)
                    field("calendar.finance.account", icon: "creditcard") {
                        if model.isLoading {
                            ProgressView()
                        } else if model.accounts.isEmpty {
                            Text("calendar.finance.no_accounts")
                                .foregroundStyle(CalendarPalette.secondary)
                        } else {
                            Picker("calendar.finance.account", selection: $model.selectedAccountID) {
                                ForEach(model.accounts) { account in
                                    Text(account.name).tag(Optional(account.id))
                                }
                            }
                            .pickerStyle(.menu)
                        }
                    }
                    Button("calendar.finance.add_account") { showsAccountForm = true }
                        .font(.subheadline)
                        .frame(minHeight: 44)
                    TextField("calendar.finance.note", text: $note, axis: .vertical)
                        .lineLimit(2...4)
                        .financeInputStyle()
                    DatePicker(
                        "calendar.finance.date", selection: $date,
                        in: dates.supportedDateTimes, displayedComponents: [.date, .hourAndMinute]
                    )
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
        .task { await model.loadAccounts() }
        .onChange(of: model.errorMessage) { _, message in showsError = message != nil }
        .alert("calendar.finance.add_account", isPresented: $showsAccountForm) {
            TextField("calendar.finance.account_name", text: $accountName)
            TextField("calendar.finance.opening_balance", text: $openingBalance)
                .keyboardType(.decimalPad)
            Button("common.cancel", role: .cancel) {}
            Button("common.confirm") {
                Task {
                    if await model.createAccount(name: accountName, openingBalance: openingBalance) {
                        accountName = ""
                        openingBalance = ""
                    }
                }
            }
        }
        .alert("calendar.finance.save_failed", isPresented: $showsError) {
            Button("common.confirm", role: .cancel) { model.errorMessage = nil }
        } message: {
            Text(model.errorMessage ?? "")
        }
        .safeAreaInset(edge: .bottom) {
            AppButton(
                "calendar.finance.save", iconName: "checkmark", isLoading: model.isSaving,
                isEnabled: model.selectedAccountID != nil && FinanceAmount.cents(amount) != nil,
                action: save
            )
            .frame(maxWidth: 640)
            .padding(.horizontal, 26)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity)
            .background { Color(uiColor: .systemBackground).ignoresSafeArea(edges: .bottom) }
        }
    }

    private func save() {
        guard let cents = FinanceAmount.cents(amount), let account = model.selectedAccountID else { return }
        let selected = model.accounts.first { $0.id == account }
        if entryType == .expense && cents > (selected?.balanceInCents ?? 0) {
            model.errorMessage = String(localized: "calendar.finance.insufficient_balance")
            return
        }
        Task {
            let draft = FinanceTransactionDraft(
                accountId: account, type: entryType.rawValue,
                category: category, note: note, amountInCents: cents, date: date)
            if await model.save(draft) { onSaved() }
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

@MainActor
private final class FinanceCreateViewModel: ObservableObject {
    @Published var accounts: [FinanceAccount] = []
    @Published var selectedAccountID: String?
    @Published var isLoading = false
    @Published var isSaving = false
    @Published var errorMessage: String?
    private let service: any FinanceServiceProtocol

    init(service: any FinanceServiceProtocol) { self.service = service }

    func loadAccounts() async {
        isLoading = true
        defer { isLoading = false }
        do {
            accounts = try await service.accounts()
            if selectedAccountID == nil { selectedAccountID = accounts.first?.id }
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    func createAccount(name: String, openingBalance: String) async -> Bool {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, let cents = FinanceAmount.cents(openingBalance, allowsZero: true) else {
            errorMessage = String(localized: "calendar.finance.invalid_amount")
            return false
        }
        do {
            let account = try await service.createAccount(name: trimmed, openingBalanceInCents: cents)
            accounts.append(account)
            selectedAccountID = account.id
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }

    func save(_ draft: FinanceTransactionDraft) async -> Bool {
        guard !isSaving else { return false }
        isSaving = true
        defer { isSaving = false }
        do {
            try await service.createTransaction(draft)
            return true
        } catch {
            errorMessage = error.localizedDescription
            return false
        }
    }
}

private enum FinanceAmount {
    static func cents(_ input: String, allowsZero: Bool = false) -> Int64? {
        let parts = input.trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: ",", with: ".").split(separator: ".", omittingEmptySubsequences: false)
        guard parts.count <= 2, let whole = Int64(parts[0]), whole >= 0,
            parts.count == 1 || parts[1].count <= 2
        else { return nil }
        let fraction = parts.count == 2 ? String(parts[1]).padding(toLength: 2, withPad: "0", startingAt: 0) : "00"
        guard let fractional = Int64(fraction) else { return nil }
        let (scaled, overflow) = whole.multipliedReportingOverflow(by: 100)
        guard !overflow else { return nil }
        let (total, sumOverflow) = scaled.addingReportingOverflow(fractional)
        guard !sumOverflow, allowsZero || total > 0 else { return nil }
        return total
    }
}

extension View {
    fileprivate func financeInputStyle() -> some View {
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
