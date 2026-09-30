import SwiftUI

struct FinanceWorkspaceView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onBack: () -> Void
    var onChanged: () -> Void = {}
    @Environment(\.locale) private var locale
    @State private var showsCreate = false
    @State private var showsAccounts = false
    @State private var selectedTransaction: FinanceTransaction?

    var body: some View {
        WorkspaceScreen(title: "tab.finance", subtitle: "finance.workspace.subtitle",
                        onBack: onBack, actionTitle: "finance.transaction.create",
                        onAdd: { showsCreate = true }) {
            WorkspaceMonthPicker(date: $model.month)
            summary
            Button { showsAccounts = true } label: {
                HStack(spacing: 12) {
                    Label("finance.accounts", systemImage: "creditcard")
                    Spacer(minLength: 8)
                    if let account = model.selectedAccount { Text(account.name).lineLimit(1) }
                    else { Text("finance.account.all").foregroundStyle(.secondary) }
                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                }
                .font(.subheadline).frame(minHeight: 44)
            }
            .buttonStyle(.plain)
            WorkspaceSearchField(text: $model.query, prompt: "finance.search")
            WorkspaceFilters(options: FinanceTransactionFilter.allCases, selection: $model.type) { $0.title }
            WorkspaceSection(title: "finance.transactions") {
                if model.transactions.isEmpty && model.isLoadingTransactions { ProgressView() }
                if let error = model.transactionsError {
                    WorkspaceErrorView(message: error) { Task { await model.loadTransactions(reset: model.transactions.isEmpty) } }
                }
                if model.transactions.isEmpty && !model.isLoadingTransactions && model.transactionsError == nil {
                    WorkspaceEmptyState(title: "finance.empty.title", description: "finance.empty.description", symbol: "arrow.left.arrow.right")
                }
                LazyVStack(spacing: 0) {
                    ForEach(model.transactions) { transaction in
                        Button { selectedTransaction = transaction } label: {
                            FinanceTransactionRow(transaction: transaction, accountName: model.accountName(for: transaction.accountId))
                        }
                        .buttonStyle(.plain)
                        Divider()
                    }
                    if model.nextTransactionOffset != nil {
                        Button("workspace.load_more") { Task { await model.loadTransactions(reset: false) } }
                            .frame(minHeight: 44).disabled(model.isLoadingTransactions)
                    }
                    if model.isLoadingTransactions && !model.transactions.isEmpty { ProgressView().padding(.top, 16) }
                }
            }
        }
        .task(id: model.monthKey) { await model.loadOverviewIfNeeded() }
        .task { if model.accounts.isEmpty { await model.loadAccounts(reset: true) } }
        .task(id: model.filterKey) {
            do { try await Task.sleep(for: .milliseconds(250)); try Task.checkCancellation() }
            catch { return }
            await model.loadTransactionsIfNeeded()
        }
        .refreshable { await model.refresh() }
        .sheet(isPresented: $showsAccounts) {
            FinanceAccountsView(model: model, financeService: financeService, onChanged: onChanged)
        }
        .sheet(isPresented: $showsCreate) {
            FinanceTransactionEditorView(model: model, financeService: financeService) {
                await model.refresh()
                onChanged()
            }
        }
        .sheet(item: $selectedTransaction) {
            FinanceTransactionDetailView(transaction: $0, accountName: model.accountName(for: $0.accountId))
        }
    }

    @ViewBuilder private var summary: some View {
        if model.isLoadingOverview { ProgressView() }
        else if let error = model.overviewError {
            WorkspaceErrorView(message: error) { Task { await model.loadOverview() } }
        } else if let overview = model.overview {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Text("finance.assets").font(.caption).foregroundStyle(.secondary)
                    Text(WorkspaceFormat.money(overview.totalAssetsInCents, locale: locale))
                        .font(.largeTitle.weight(.medium)).monospacedDigit().minimumScaleFactor(0.7)
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 32) { monthValues(overview) }
                    VStack(alignment: .leading, spacing: 20) { monthValues(overview) }
                }
            }
        }
    }

    @ViewBuilder private func monthValues(_ overview: FinanceMonthOverview) -> some View {
        WorkspaceValue(label: "finance.month.expense", value: WorkspaceFormat.money(overview.expenseInCents, locale: locale))
        WorkspaceValue(label: "finance.month.income", value: WorkspaceFormat.money(overview.incomeInCents, locale: locale))
    }
}

private struct FinanceAccountsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var showsCreate = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Button {
                        model.selectedAccountID = nil
                        dismiss()
                    } label: {
                        HStack {
                            Text("finance.account.all")
                            Spacer()
                            if model.selectedAccountID == nil { Image(systemName: "checkmark") }
                        }.frame(minHeight: 44)
                    }.buttonStyle(.plain)
                    if let error = model.accountsError {
                        WorkspaceErrorView(message: error) { Task { await model.loadAccounts(reset: model.accounts.isEmpty) } }
                    }
                    if model.accounts.isEmpty && !model.isLoadingAccounts && model.accountsError == nil {
                        WorkspaceEmptyState(title: "finance.accounts.empty.title", description: "finance.accounts.empty.description", symbol: "creditcard")
                    }
                    LazyVStack(spacing: 0) {
                        ForEach(model.accounts) { account in
                            Button {
                                model.selectedAccountID = account.id
                                dismiss()
                            } label: {
                                HStack(alignment: .firstTextBaseline, spacing: 16) {
                                    Text(account.name).font(.body)
                                    Spacer(minLength: 8)
                                    Text(WorkspaceFormat.money(account.balanceInCents, locale: locale)).monospacedDigit()
                                    if model.selectedAccountID == account.id { Image(systemName: "checkmark").font(.caption) }
                                }.padding(.vertical, 20).frame(minHeight: 44)
                            }.buttonStyle(.plain)
                            Divider()
                        }
                        if model.nextAccountOffset != nil {
                            Button("workspace.load_more") { Task { await model.loadAccounts(reset: false) } }
                                .frame(minHeight: 44).disabled(model.isLoadingAccounts)
                        }
                    }
                    if model.isLoadingAccounts { ProgressView() }
                }.padding(24)
            }
            .background(Color(uiColor: .systemBackground).ignoresSafeArea())
            .navigationTitle("finance.accounts").navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("workspace.done") { dismiss() } }
                ToolbarItem(placement: .primaryAction) {
                    Button { showsCreate = true } label: { Image(systemName: "plus") }
                        .accessibilityLabel("finance.account.create")
                }
            }
        }
        .sheet(isPresented: $showsCreate) {
            FinanceAccountEditorView(financeService: financeService) { account in
                model.accountCreated(account)
                Task { await model.loadOverview() }
                onChanged()
            }
        }
    }
}
