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

            VStack(alignment: .leading, spacing: 16) {
                HStack {
                    Text("finance.transactions.today").font(.headline)
                    Spacer()
                    NavigationLink {
                        FinanceAllTransactionsView(model: model, financeService: financeService, onChanged: onChanged)
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
                    .simultaneousGesture(TapGesture().onEnded {
                        openAllTransactions()
                    })
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
                    VStack(spacing: 0) {
                        ForEach(Array(model.todayTransactions.enumerated()), id: \.element.id) { index, transaction in
                            Button { selectedTransaction = transaction } label: {
                                FinanceTransactionRow(transaction: transaction, accountName: model.accountName(for: transaction.accountId))
                                    .padding(.horizontal, 16)
                            }
                            .buttonStyle(.plain)

                            if index < model.todayTransactions.count - 1 {
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

                    NavigationLink {
                        FinanceAllTransactionsView(model: model, financeService: financeService, onChanged: onChanged)
                    } label: {
                        HStack(spacing: 8) {
                            Text("finance.transactions.all")
                            Image(systemName: "arrow.right").font(.caption.weight(.semibold))
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(FinanceVisualStyle.accent)
                        .frame(maxWidth: .infinity, minHeight: 48)
                        .background(FinanceVisualStyle.accent.opacity(0.08), in: RoundedRectangle(cornerRadius: 16, style: .continuous))
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 4)
                    .simultaneousGesture(TapGesture().onEnded {
                        openAllTransactions()
                    })
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

    private func openAllTransactions() {
        model.selectedAccountID = nil
        model.query = ""
        model.type = .all
        if let latestEntry = model.visibleInsights?.transactions.first,
           let entryDate = latestEntry.date {
            let calendar = Calendar.current
            if !calendar.isDate(model.month, equalTo: entryDate, toGranularity: .month) {
                model.month = entryDate
            }
        }
    }

    @ViewBuilder private var summary: some View {
        if model.isLoadingOverview {
            ProgressView().frame(maxWidth: .infinity, minHeight: 80)
        } else if let error = model.overviewError {
            WorkspaceErrorView(message: error) { Task { await model.loadOverview() } }
        } else if let overview = model.overview {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 16) {
                    HStack(alignment: .center) {
                        Button { showsAccounts = true } label: {
                            HStack(spacing: 6) {
                                Image(systemName: "creditcard.fill")
                                    .font(.caption)
                                    .foregroundStyle(FinanceVisualStyle.accent)
                                Text("finance.assets")
                                    .font(.subheadline.weight(.medium))
                                    .foregroundStyle(.secondary)
                                Image(systemName: "chevron.right")
                                    .font(.caption2.weight(.bold))
                                    .foregroundStyle(.secondary.opacity(0.7))
                            }
                            .padding(.vertical, 4)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("finance.accounts")

                        Spacer()
                    }

                    Text(WorkspaceFormat.money(overview.totalAssetsInCents, locale: locale))
                        .font(.system(size: 34, weight: .bold, design: .rounded))
                        .monospacedDigit()
                        .foregroundStyle(Color.primary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.65)

                    HStack(spacing: 12) {
                        flowCapsule(
                            title: "finance.month.expense",
                            amount: overview.expenseInCents,
                            symbol: "arrow.up.right",
                            color: FinanceVisualStyle.expense,
                            sign: "-"
                        )
                        flowCapsule(
                            title: "finance.month.income",
                            amount: overview.incomeInCents,
                            symbol: "arrow.down.left",
                            color: FinanceVisualStyle.income,
                            sign: "+"
                        )
                    }
                }
                .padding(20)
                .background(
                    Color(uiColor: .secondarySystemGroupedBackground),
                    in: RoundedRectangle(cornerRadius: 22, style: .continuous)
                )
                .overlay(
                    RoundedRectangle(cornerRadius: 22, style: .continuous)
                        .stroke(Color.primary.opacity(0.04), lineWidth: 1)
                )
            }
        }
    }

    private func flowCapsule(
        title: LocalizedStringKey,
        amount: Int64,
        symbol: String,
        color: Color,
        sign: String
    ) -> some View {
        HStack(spacing: 10) {
            ZStack {
                Circle()
                    .fill(color.opacity(0.14))
                    .frame(width: 28, height: 28)
                Image(systemName: symbol)
                    .font(.caption2.weight(.bold))
                    .foregroundStyle(color)
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.caption2.weight(.medium))
                    .foregroundStyle(.secondary)
                let formatted = WorkspaceFormat.money(amount, locale: locale)
                Text("\(sign)\(formatted)")
                    .font(.subheadline.weight(.bold))
                    .monospacedDigit()
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .background(color.opacity(0.07), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}
