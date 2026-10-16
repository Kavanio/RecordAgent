import SwiftUI

struct FinanceTransactionEditorView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onSaved: () async -> Void
    @State private var type: FinanceTransactionFilter = .expense
    @State private var amount = ""
    @State private var category = ""
    @State private var note = ""
    @State private var accountID: String?
    @State private var date: Date
    @State private var showsAccountCreate = false

    init(
        model: FinanceWorkspaceViewModel, financeService: any FinanceServiceProtocol,
        onSaved: @escaping () async -> Void
    ) {
        self.model = model
        self.financeService = financeService
        self.onSaved = onSaved
        _date = State(initialValue: Self.defaultDate(for: model.month))
        _accountID = State(initialValue: model.selectedAccountID ?? model.accounts.first?.id)
    }

    private static func defaultDate(for month: Date) -> Date {
        let now = Date()
        let calendar = Calendar.current
        if calendar.isDate(month, equalTo: now, toGranularity: .month) {
            return now
        }
        var targetComponents = calendar.dateComponents([.year, .month], from: month)
        let nowComponents = calendar.dateComponents([.day, .hour, .minute, .second], from: now)
        let range = calendar.range(of: .day, in: .month, for: month)
        let maxDay = range?.count ?? 28
        targetComponents.day = min(nowComponents.day ?? 1, maxDay)
        targetComponents.hour = nowComponents.hour
        targetComponents.minute = nowComponents.minute
        targetComponents.second = nowComponents.second
        return calendar.date(from: targetComponents) ?? month
    }

    var body: some View {
        WorkspaceEditor(
            title: "finance.transaction.create",
            isValid: accountID != nil && FinanceAmountInput.cents(amount) != nil && category.utf16.count <= 80
                && note.utf16.count <= 10_000, onSave: save
        ) {
            WorkspaceFilters(options: [.expense, .income], selection: $type) { $0.title }
            WorkspaceTextField(title: "finance.amount", text: $amount).keyboardType(.decimalPad)
                .font(.title2).monospacedDigit()
            VStack(alignment: .leading, spacing: 8) {
                Text("finance.account").font(.caption).foregroundStyle(.secondary)
                if model.accounts.isEmpty {
                    Text("finance.accounts.empty.description").font(.subheadline).foregroundStyle(.secondary)
                } else {
                    Picker("finance.account", selection: $accountID) {
                        ForEach(model.accounts) { account in Text(account.name).tag(Optional(account.id)) }
                    }.pickerStyle(.menu)
                }
                if let error = model.accountsError {
                    WorkspaceErrorView(message: error) {
                        Task { await model.loadAccounts(reset: model.accounts.isEmpty) }
                    }
                }
                if model.nextAccountOffset != nil {
                    Button("workspace.load_more") { Task { await model.loadAccounts(reset: false) } }.disabled(
                        model.isLoadingAccounts)
                }
                Button("finance.account.create") { showsAccountCreate = true }.frame(minHeight: 44)
                if model.isLoadingAccounts { ProgressView() }
            }
            FinanceCategoryPicker(type: type.rawValue, category: $category)
            WorkspaceTextField(title: "finance.note", text: $note, axis: .vertical)
            DatePicker(
                "finance.date", selection: $date,
                in: HomeCalendar(calendar: .current).supportedDateTimes, displayedComponents: [.date, .hourAndMinute])
        }
        .task {
            if model.accounts.isEmpty { await model.loadAccounts(reset: true) }
            if accountID == nil { accountID = model.accounts.first?.id }
        }
        .sheet(isPresented: $showsAccountCreate) {
            FinanceAccountEditorView(financeService: financeService) { account in
                model.accountCreated(account)
                accountID = account.id
            }
        }
    }

    private func save() async throws {
        guard let accountID, let cents = FinanceAmountInput.cents(amount),
            let account = model.accounts.first(where: { $0.id == accountID })
        else { throw FinanceEditorError.invalid }
        if type == .expense && cents > account.balanceInCents { throw FinanceEditorError.insufficientBalance }
        try await financeService.createTransaction(
            FinanceTransactionDraft(
                accountId: accountID,
                type: type.rawValue, category: category, note: note, amountInCents: cents, date: date))
        await onSaved()
    }
}

struct FinanceAccountEditorView: View {
    let financeService: any FinanceServiceProtocol
    let onSaved: (FinanceAccount) -> Void
    @State private var name = ""
    @State private var balance = "0"

    var body: some View {
        WorkspaceEditor(
            title: "finance.account.create",
            isValid: !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && name.utf16.count <= 120
                && FinanceAmountInput.cents(balance, allowsZero: true) != nil
        ) {
            guard let cents = FinanceAmountInput.cents(balance, allowsZero: true) else {
                throw FinanceEditorError.invalid
            }
            let account = try await financeService.createAccount(name: name, openingBalanceInCents: cents)
            onSaved(account)
        } content: {
            WorkspaceTextField(title: "finance.account.name", text: $name)
            WorkspaceTextField(title: "finance.account.opening_balance", text: $balance).keyboardType(.decimalPad)
        }
    }
}

private enum FinanceEditorError: LocalizedError {
    case invalid, insufficientBalance
    var errorDescription: String? {
        switch self {
        case .invalid: return String(localized: "finance.error.invalid")
        case .insufficientBalance: return String(localized: "calendar.finance.insufficient_balance")
        }
    }
}
