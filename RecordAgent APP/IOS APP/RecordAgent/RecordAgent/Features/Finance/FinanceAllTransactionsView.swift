import SwiftUI

/// 全部流水记录页面：独立承载月份选择、账户筛选、关键词搜索、收支类型过滤及分页浏览。
struct FinanceAllTransactionsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var showsAccounts = false
    @State private var showsCreate = false
    @State private var selectedTransaction: FinanceTransaction?

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(
                title: "finance.transactions.all",
                subtitle: "finance.transactions.all.subtitle"
            ) { dismiss() }
            .padding(.horizontal, 24)
            .padding(.top, 28)
            .padding(.bottom, 16)

            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    WorkspaceMonthPicker(date: $model.month)

                    Button {
                        showsAccounts = true
                    } label: {
                        HStack(spacing: 12) {
                            Label("finance.accounts", systemImage: "creditcard")
                            Spacer(minLength: 8)
                            if let account = model.selectedAccount {
                                Text(account.name).lineLimit(1)
                            } else {
                                Text("finance.account.all").foregroundStyle(.secondary)
                            }
                            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
                        }
                        .font(.subheadline)
                        .padding(.horizontal, 16)
                        .frame(minHeight: 48)
                        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
                    }
                    .buttonStyle(.plain)

                    WorkspaceSearchField(text: $model.query, prompt: "finance.search")

                    WorkspaceFilters(options: FinanceTransactionFilter.allCases, selection: $model.type) { $0.title }

                    VStack(alignment: .leading, spacing: 0) {
                        if model.transactions.isEmpty && model.isLoadingTransactions {
                            ProgressView()
                                .frame(maxWidth: .infinity, minHeight: 120)
                        } else if let error = model.transactionsError {
                            WorkspaceErrorView(message: error) {
                                Task { await model.loadTransactions(reset: model.transactions.isEmpty) }
                            }
                        } else if model.transactions.isEmpty && !model.isLoadingTransactions && model.transactionsError == nil {
                            WorkspaceEmptyState(
                                title: "finance.empty.title",
                                description: "finance.empty.description",
                                symbol: "arrow.left.arrow.right"
                            )
                            .frame(maxWidth: .infinity, minHeight: 160)
                        } else {
                            LazyVStack(spacing: 0) {
                                ForEach(model.transactions) { transaction in
                                    Button {
                                        selectedTransaction = transaction
                                    } label: {
                                        FinanceTransactionRow(
                                            transaction: transaction,
                                            accountName: model.accountName(for: transaction.accountId)
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }

                                if model.nextTransactionOffset != nil {
                                    Button("workspace.load_more") {
                                        Task { await model.loadTransactions(reset: false) }
                                    }
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .disabled(model.isLoadingTransactions)
                                    .padding(.top, 8)
                                }

                                if model.isLoadingTransactions && !model.transactions.isEmpty {
                                    ProgressView().padding(.top, 16)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .task(id: model.filterKey) {
            do {
                try await Task.sleep(for: .milliseconds(250))
                try Task.checkCancellation()
            } catch { return }
            await model.loadTransactionsIfNeeded()
        }
        .sheet(isPresented: $showsAccounts) {
            FinanceAccountsView(model: model, financeService: financeService, onChanged: onChanged)
                .workspaceSheet(compact: true)
        }
        .sheet(item: $selectedTransaction) { transaction in
            FinanceTransactionDetailView(
                transaction: transaction,
                accountName: model.accountName(for: transaction.accountId)
            )
        }
    }
}
