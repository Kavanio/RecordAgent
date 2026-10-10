import SwiftUI

struct FinanceAccountsView: View {
    @ObservedObject var model: FinanceWorkspaceViewModel
    let financeService: any FinanceServiceProtocol
    let onChanged: () -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var showsCreate = false

    var body: some View {
        VStack(spacing: 0) {
            WorkspaceSheetHeader(title: "finance.accounts", subtitle: "finance.account.choose") { dismiss() }
                .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 20)
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    accountRow(
                        name: nil, balance: nil, symbol: "square.stack.3d.up", color: AppColors.primaryBlue,
                        selected: model.selectedAccountID == nil
                    ) {
                        model.selectedAccountID = nil
                        dismiss()
                    }
                    if let error = model.accountsError {
                        WorkspaceErrorView(message: error) {
                            Task { await model.loadAccounts(reset: model.accounts.isEmpty) }
                        }
                    }
                    if model.accounts.isEmpty && !model.isLoadingAccounts && model.accountsError == nil {
                        WorkspaceEmptyState(
                            title: "finance.accounts.empty.title", description: "finance.accounts.empty.description",
                            symbol: "creditcard")
                    }
                    ForEach(model.accounts) { account in
                        accountRow(
                            name: account.name, balance: account.balanceInCents, symbol: "creditcard.fill",
                            color: FinanceVisualStyle.category(account.id),
                            selected: model.selectedAccountID == account.id
                        ) {
                            model.selectedAccountID = account.id
                            dismiss()
                        }
                    }
                    if model.nextAccountOffset != nil {
                        Button("workspace.load_more") { Task { await model.loadAccounts(reset: false) } }
                            .frame(minHeight: 44).disabled(model.isLoadingAccounts)
                    }
                    if model.isLoadingAccounts { ProgressView().frame(maxWidth: .infinity) }
                }.padding(.horizontal, 24).padding(.bottom, 20)
            }
        }
        .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
        .safeAreaInset(edge: .bottom) {
            Button {
                showsCreate = true
            } label: {
                Label("finance.account.create", systemImage: "plus")
                    .font(.subheadline.weight(.semibold)).frame(maxWidth: .infinity, minHeight: 50)
                    .foregroundStyle(AppColors.primaryBlue)
                    .background(AppColors.primaryBlue.opacity(0.1), in: RoundedRectangle(cornerRadius: 16))
            }.buttonStyle(.plain).padding(.horizontal, 24).padding(.vertical, 12).background(.regularMaterial)
        }
        .sheet(isPresented: $showsCreate) {
            FinanceAccountEditorView(financeService: financeService) { account in
                model.accountCreated(account)
                Task {
                    await model.loadOverview()
                    await model.loadInsights()
                }
                onChanged()
            }
            .presentationDetents([.medium, .large])
        }
    }

    private func accountRow(
        name: String?, balance: Int64?, symbol: String, color: Color,
        selected: Bool, onSelect: @escaping () -> Void
    ) -> some View {
        Button(action: onSelect) {
            HStack(spacing: 14) {
                Image(systemName: symbol).font(.title3).foregroundStyle(color)
                    .frame(width: 44, height: 44).background(color.opacity(0.1), in: RoundedRectangle(cornerRadius: 14))
                VStack(alignment: .leading, spacing: 7) {
                    if let name {
                        Text(name).font(.body.weight(.medium))
                    } else {
                        Text("finance.account.all").font(.body.weight(.medium))
                    }
                    if let balance {
                        Text(WorkspaceFormat.money(balance, locale: locale)).font(.subheadline).monospacedDigit()
                            .foregroundStyle(.secondary)
                    } else {
                        Text("finance.account.all.description").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 4)
                Image(systemName: selected ? "checkmark.circle.fill" : "circle")
                    .font(.title3).foregroundStyle(selected ? AppColors.primaryBlue : Color.secondary.opacity(0.3))
            }
            .padding(16).frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 20))
            .overlay {
                RoundedRectangle(cornerRadius: 20).stroke(
                    selected ? AppColors.primaryBlue.opacity(0.5) : .clear, lineWidth: 1)
            }
        }
        .buttonStyle(.plain).accessibilityAddTraits(selected ? .isSelected : [])
    }
}
