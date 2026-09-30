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
            FinanceInsightsView(model: model)
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
                .workspaceSheet(compact: true)
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
                    Button { showsAccounts = true } label: {
                        HStack(spacing: 8) {
                            Text("finance.assets")
                            Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                        }.font(.subheadline.weight(.medium)).foregroundStyle(.secondary)
                            .frame(minHeight: 44)
                    }.buttonStyle(.plain).accessibilityLabel("finance.accounts")
                    Text(WorkspaceFormat.money(overview.totalAssetsInCents, locale: locale))
                        .foregroundStyle(FinanceVisualStyle.accent)
                        .font(.largeTitle.weight(.bold)).monospacedDigit()
                        .lineLimit(1).minimumScaleFactor(0.6)
                }
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 32) { monthValues(overview) }
                    VStack(alignment: .leading, spacing: 20) { monthValues(overview) }
                }
            }
        }
    }

    @ViewBuilder private func monthValues(_ overview: FinanceMonthOverview) -> some View {
        monthMetric("finance.month.expense", amount: overview.expenseInCents, symbol: "arrow.up.right", color: FinanceVisualStyle.expense)
        monthMetric("finance.month.income", amount: overview.incomeInCents, symbol: "arrow.down.left", color: FinanceVisualStyle.income)
    }
    private func monthMetric(_ title: LocalizedStringKey, amount: Int64, symbol: String, color: Color) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            Label(title, systemImage: symbol).font(.caption.weight(.medium)).foregroundStyle(color)
            Text(WorkspaceFormat.money(amount, locale: locale))
                .font(.title2.weight(.semibold)).foregroundStyle(color).monospacedDigit()
        }.accessibilityElement(children: .combine)
    }
}
