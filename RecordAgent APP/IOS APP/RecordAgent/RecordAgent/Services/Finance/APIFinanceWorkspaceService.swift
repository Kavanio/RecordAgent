import Foundation

/// 财务工作区读取独立的月总览和有界流水，沿用现有财务服务进行写入。
actor APIFinanceWorkspaceService: FinanceWorkspaceServiceProtocol {
    private let client: APIClient
    init(client: APIClient) { self.client = client }

    func overview(month: String) async throws -> FinanceMonthOverview {
        let data = try await client.send("/api/v1/finance/overview", query: [
            URLQueryItem(name: "month", value: month)
        ])
        return try APIClient.decoder.decode(FinanceMonthOverview.self, from: data)
    }

    func accounts(offset: Int) async throws -> FinanceWorkspacePage<FinanceAccount> {
        let data = try await client.send("/api/v1/finance/accounts/page", query: [
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: "100")
        ])
        return try APIClient.decoder.decode(FinanceWorkspacePage<FinanceAccount>.self, from: data)
    }

    func transactions(month: String, type: FinanceTransactionFilter, accountID: String?,
                      query: String, offset: Int) async throws -> FinanceWorkspacePage<FinanceTransaction> {
        var parameters = [
            URLQueryItem(name: "month", value: month),
            URLQueryItem(name: "type", value: type.rawValue),
            URLQueryItem(name: "query", value: query.trimmingCharacters(in: .whitespacesAndNewlines)),
            URLQueryItem(name: "offset", value: String(offset)),
            URLQueryItem(name: "limit", value: "50")
        ]
        if let accountID { parameters.append(URLQueryItem(name: "accountId", value: accountID)) }
        let data = try await client.send("/api/v1/finance/transactions/page", query: parameters)
        return try APIClient.decoder.decode(FinanceWorkspacePage<FinanceTransaction>.self, from: data)
    }
}
