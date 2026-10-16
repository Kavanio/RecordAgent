import SwiftUI

/// 全部流水记录页面：独立二级页面，承载月份选择、账户筛选、关键词搜索、收支类型过滤及分页浏览。
struct FinanceAllTransactionsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onChanged: () -> Void
    @Environment(\.locale) private var locale
    @State private var showsAccounts = false
    @State private var showsCreate = false
    @State private var selectedTransaction: FinanceTransaction?

    var body: some View {
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
                            .frame(maxWidth: .infinity, minHeight: 140)
                    } else if let error = model.transactionsError {
                        WorkspaceErrorView(message: error) {
                            Task { await model.loadTransactions(reset: model.transactions.isEmpty) }
                        }
                    } else if model.transactions.isEmpty && !model.isLoadingTransactions && model.transactionsError == nil {
                        VStack(spacing: 16) {
                            WorkspaceEmptyState(
                                title: "finance.empty.title",
                                description: "finance.empty.description",
                                symbol: "arrow.left.arrow.right"
                            )

                            if let previousMonth = Calendar.current.date(byAdding: .month, value: -1, to: model.month) {
                                Button {
                                    model.month = previousMonth
                                } label: {
                                    HStack(spacing: 6) {
                                        Image(systemName: "chevron.left")
                                        Text("finance.view_previous_month")
                                    }
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(AppColors.primaryBlue)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 10)
                                    .background(AppColors.primaryBlue.opacity(0.1), in: Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 16)
                    } else {
                        VStack(spacing: 0) {
                            ForEach(Array(model.transactions.enumerated()), id: \.element.id) { index, transaction in
                                Button {
                                    selectedTransaction = transaction
                                } label: {
                                    FinanceTransactionRow(
                                        transaction: transaction,
                                        accountName: model.accountName(for: transaction.accountId)
                                    )
                                    .padding(.horizontal, 16)
                                }
                                .buttonStyle(.plain)

                                if index < model.transactions.count - 1 {
                                    Divider()
                                        .opacity(0.35)
                                        .padding(.leading, 74)
                                        .padding(.trailing, 16)
                                }
                            }
                        }
                        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                        .overlay(
                            RoundedRectangle(cornerRadius: 20, style: .continuous)
                                .stroke(Color.primary.opacity(0.04), lineWidth: 1)
                        )

                        if model.nextTransactionOffset != nil {
                            Button("workspace.load_more") {
                                Task { await model.loadTransactions(reset: false) }
                            }
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(FinanceVisualStyle.accent)
                            .frame(maxWidth: .infinity, minHeight: 44)
                            .disabled(model.isLoadingTransactions)
                            .padding(.top, 12)
                        }

                        if model.isLoadingTransactions && !model.transactions.isEmpty {
                            ProgressView().padding(.top, 16)
                        }
                    }
                }
            }
            .padding(.horizontal, 24)
            .padding(.top, 12)
            .padding(.bottom, 32)
        }
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .navigationTitle("finance.transactions.all")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    showsCreate = true
                } label: {
                    Image(systemName: "plus")
                        .font(.body.weight(.medium))
                }
                .accessibilityLabel("finance.transaction.create")
            }
        }
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
        .sheet(isPresented: $showsCreate) {
            FinanceTransactionEditorView(model: model, financeService: financeService) {
                await model.refresh()
                onChanged()
            }
        }
        .sheet(item: $selectedTransaction) { transaction in
            FinanceTransactionDetailView(
                transaction: transaction,
                accountName: model.accountName(for: transaction.accountId)
            )
        }
        .onAppear {
            if model.selectedAccountID != nil {
                model.selectedAccountID = nil
            }
        }
    }
}
