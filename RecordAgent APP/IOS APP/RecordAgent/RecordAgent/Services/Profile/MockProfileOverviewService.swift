import Foundation

/// 仅用于 Debug 的总览样例；不能与真实账户数据混用。
struct MockProfileOverviewService: ProfileOverviewServiceProtocol {
    func overview(for userID: String, month: Date) async throws -> ProfileOverview {
        try Task.checkCancellation()
        return ProfileOverview(
            month: month,
            totalAssetsInCents: 8_642_000,
            monthlyExpenseInCents: 326_800,
            totalRecordMinutes: 12_870,
            documentCount: 342,
            documentWordCount: 120_480,
            monthlyCompletedTodos: 24,
            monthlyTodoCount: 30,
            isDemo: true
        )
    }
}
