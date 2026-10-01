import SwiftUI

struct FinanceWorkspaceView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onBack: () -> Void
    var onChanged: () -> Void = {}
    @Environment(\.locale) private var locale
    @State private var showsCreate = false
    @State private var showsAccounts = false
    @State private var showsAllTransactions = false
    @State private var selectedTransaction: FinanceTransaction?

    var body: some View {
        WorkspaceScreen(title: "tab.finance", subtitle: "finance.workspace.subtitle",
                        onBack: onBack, actionTitle: "finance.transaction.create",
                        onAdd: { showsCreate = true }) {
            WorkspaceMonthPicker(date: $model.month)
            summary
            FinanceInsightsView(model: model)

            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("finance.transactions.today").font(.headline)
                    Spacer()
                    Button {
                        showsAllTransactions = true
                    } label: {
                        HStack(spacing: 4) {
                            Text("finance.transactions.view_all")
                            Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                        }
                        .font(.subheadline)
                        .foregroundStyle(AppColors.primaryBlue)
                        .frame(minHeight: 44)
                    }
                    .buttonStyle(.plain)
                }

                if model.todayTransactions.isEmpty && model.isLoadingTodayTransactions {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 60)
                } else if model.todayTransactions.isEmpty {
                    VStack(spacing: 12) {
                        Text("finance.today.empty")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Button {
                            showsCreate = true
                        } label: {
                            Label("finance.transaction.create", systemImage: "plus")
                                .font(.caption.weight(.medium))
                                .foregroundStyle(AppColors.primaryBlue)
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(AppColors.primaryBlue.opacity(0.1), in: Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(model.todayTransactions) { transaction in
                            Button { selectedTransaction = transaction } label: {
                                FinanceTransactionRow(transaction: transaction, accountName: model.accountName(for: transaction.accountId))
                            }
                            .buttonStyle(.plain)
                        }
                    }

                    Button {
                        showsAllTransactions = true
                    } label: {
                        HStack(spacing: 6) {
                            Text("finance.transactions.all")
                            Image(systemName: "arrow.right").font(.caption.weight(.semibold))
                        }
                        .font(.subheadline.weight(.medium))
                        .foregroundStyle(AppColors.primaryBlue)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .background(AppColors.primaryBlue.opacity(0.08), in: RoundedRectangle(cornerRadius: 14))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                }
            }
        }
        .task(id: model.monthKey) { await model.loadOverviewIfNeeded() }
        .task { if model.accounts.isEmpty { await model.loadAccounts(reset: true) } }
        .task { await model.loadTodayTransactions() }
        .refreshable { await model.refresh() }
        .sheet(isPresented: $showsAccounts) {
            FinanceAccountsView(model: model, financeService: financeService, onChanged: onChanged)
                .workspaceSheet(compact: true)
        }
        .sheet(isPresented: $showsAllTransactions) {
            FinanceAllTransactionsView(model: model, financeService: financeService, onChanged: onChanged)
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
