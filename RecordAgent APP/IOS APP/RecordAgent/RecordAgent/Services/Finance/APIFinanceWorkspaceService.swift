import Foundation

/// 财务工作区对接 `agent-backend` 的 `/agent/finance/overview` 与 `/agent/finance/transactions`。
actor APIFinanceWorkspaceService: FinanceWorkspaceServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func overview(month: String) async throws -> FinanceMonthOverview {
        let overview: BackendFinanceOverviewResponse = try await client.sendResult("/agent/finance/overview")
        let allTransactions: [BackendFinanceTransactionResponse] = try await client.sendResult(
            "/agent/finance/transactions")
        let monthTransactions = allTransactions.filter { $0.date.hasPrefix(month) }
        var expenseCents: Int64 = 0
        var incomeCents: Int64 = 0
        for tx in monthTransactions {
            let cents = Int64((tx.amount * 100).rounded())
            switch tx.transactionType.uppercased() {
            case "EXPENSE": expenseCents += cents
            case "INCOME": incomeCents += cents
            default: break
            }
        }
        return FinanceMonthOverview(
            totalAssetsInCents: Int64((overview.totalAssets * 100).rounded()),
            expenseInCents: expenseCents,
            incomeInCents: incomeCents,
            transactionCount: Int64(monthTransactions.count)
        )
    }

    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount> {
        let overview: BackendFinanceOverviewResponse = try await client.sendResult("/agent/finance/overview")
        let all = overview.accounts.map { $0.toDomain() }
        let pageSize = 100
        let start = min(max(0, offset), all.count)
        let end = min(start + pageSize, all.count)
        let slice = Array(all[start..<end])
        let hasMore = end < all.count
        return FinanceWorkspacePage(items: slice, nextOffset: hasMore ? end : nil, hasMore: hasMore)
    }

    func transactions(
        month: String, type: FinanceTransactionFilter, accountID: String?,
        query: String, offset: Int
    ) async throws -> FinanceWorkspacePage<FinanceTransaction> {
        let all: [BackendFinanceTransactionResponse] = try await client.sendResult("/agent/finance/transactions")
        let trimmedQuery = query.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        let filtered = all.filter { tx in
            guard tx.date.hasPrefix(month) else { return false }
            if type != .all, tx.transactionType.lowercased() != type.rawValue { return false }
            if let accountID, tx.accountId != accountID { return false }
            if !trimmedQuery.isEmpty {
                let categoryMatch = (tx.category ?? "").lowercased().contains(trimmedQuery)
                let noteMatch = (tx.note ?? "").lowercased().contains(trimmedQuery)
                if !categoryMatch && !noteMatch { return false }
            }
            return true
        }.map { $0.toDomain() }

        let pageSize = 50
        let start = min(max(0, offset), filtered.count)
        let end = min(start + pageSize, filtered.count)
        let slice = Array(filtered[start..<end])
        let hasMore = end < filtered.count
        return FinanceWorkspacePage(items: slice, nextOffset: hasMore ? end : nil, hasMore: hasMore)
    }
}
