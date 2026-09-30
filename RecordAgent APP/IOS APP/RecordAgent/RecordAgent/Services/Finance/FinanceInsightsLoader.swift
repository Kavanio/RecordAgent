import Foundation

extension FinanceWorkspaceServiceProtocol {
    /// 分析独立读取完整周期，不使用被搜索、账户筛选或分页截断的界面列表。
    /// 所有页成功后才发布结果；异常游标和安全上限返回失败，不展示部分统计。
    func insights(period: FinanceAnalysisPeriod) async throws -> FinanceInsights {
        var accounts: [FinanceAccount] = []
        var accountOffset = 0
        var accountIDs: Set<String> = []
        while true {
            try Task.checkCancellation()
            let page = try await self.accounts(offset: accountOffset)
            accounts.append(contentsOf: page.items.filter { accountIDs.insert($0.id).inserted })
            guard accounts.count <= 10_000 else { throw APIClientError.invalidResponse }
            guard page.hasMore else { break }
            guard let next = page.nextOffset, next > accountOffset, next <= 10_000, accounts.count < 10_000 else {
                throw APIClientError.invalidResponse
            }
            accountOffset = next
        }
        var entries: [FinanceTransaction] = []
        for month in period.months {
            var offset = 0
            while true {
                try Task.checkCancellation()
                let page = try await transactions(month: month, type: .all, accountID: nil, query: "", offset: offset)
                entries.append(contentsOf: page.items)
                guard entries.count <= 100_000 else { throw APIClientError.invalidResponse }
                guard page.hasMore else { break }
                guard let next = page.nextOffset, next > offset, next <= 100_000 else {
                    throw APIClientError.invalidResponse
                }
                offset = next
            }
        }
        try Task.checkCancellation()
        return try FinanceInsights(period: period, entries: entries, accounts: accounts)
    }
}
