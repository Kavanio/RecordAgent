import Foundation

/// 账户范围内的个人总览读取边界；真实服务应一次返回同一统计时点的全部数据。
protocol ProfileOverviewServiceProtocol: Sendable {
    func overview(for userID: String, month: Date) async throws -> ProfileOverview
}

enum ProfileOverviewError: Error { case unavailable }

/// Release 在统计接口接通之前呈现不可用状态。
struct UnavailableProfileOverviewService: ProfileOverviewServiceProtocol {
    func overview(for userID: String, month: Date) async throws -> ProfileOverview {
        throw ProfileOverviewError.unavailable
    }
}

/// 个人总览对接 `agent-backend` 的 `/agent/insights/profile`。
actor APIProfileOverviewService: ProfileOverviewServiceProtocol {
    private let client: APIClient

    init(client: APIClient) { self.client = client }

    func overview(for userID: String, month: Date) async throws -> ProfileOverview {
        let parts = Calendar.current.dateComponents([.year, .month], from: month)
        let key = String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
        let response: ProfileOverviewResponse = try await client.sendResult(
            "/agent/insights/profile",
            query: [
                URLQueryItem(name: "month", value: key),
                URLQueryItem(name: "timezone", value: TimeZone.current.identifier),
            ]
        )
        return ProfileOverview(
            month: month,
            totalAssetsInCents: response.totalAssetsInCents,
            totalExpenseInCents: response.totalExpenseInCents,
            totalRecordMinutes: response.totalRecordMinutes,
            documentCount: response.documentCount,
            documentWordCount: response.documentWordCount,
            monthlyCompletedTodos: response.monthlyCompletedTodos,
            monthlyTodoCount: response.monthlyTodoCount,
            isDemo: false,
            assetsGrowthRate: response.assetsGrowthRate,
            expenseGrowthRate: response.expenseGrowthRate,
            recordMinutesGrowthRate: response.recordMinutesGrowthRate
        )
    }
}

private struct ProfileOverviewResponse: Decodable {
    let month: String
    let totalAssetsInCents: Int
    let totalExpenseInCents: Int
    let totalRecordMinutes: Int
    let documentCount: Int
    let documentWordCount: Int
    let monthlyCompletedTodos: Int
    let monthlyTodoCount: Int
    let assetsGrowthRate: Double?
    let expenseGrowthRate: Double?
    let recordMinutesGrowthRate: Double?
}
