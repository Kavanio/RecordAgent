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

/// 个人总览优先调用 `/agent/insights/profile`；若后端尚未升级，则自动通过已有接口聚合计算。
actor APIProfileOverviewService: ProfileOverviewServiceProtocol {
    private let client: APIClient

    init(client: APIClient) { self.client = client }

    func overview(for userID: String, month: Date) async throws -> ProfileOverview {
        let parts = Calendar.current.dateComponents([.year, .month], from: month)
        let key = String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
        if let response: ProfileOverviewResponse = try? await client.sendResult(
            "/agent/insights/profile",
            query: [
                URLQueryItem(name: "month", value: key),
                URLQueryItem(name: "timezone", value: TimeZone.current.identifier),
            ]
        ) {
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
        return try await fallbackOverview(month: month, monthKey: key)
    }

    private func fallbackOverview(month: Date, monthKey: String) async throws -> ProfileOverview {
        let calendar = Calendar.current
        let tz = TimeZone.current.identifier
        let monthStart = calendar.dateInterval(of: .month, for: month)?.start ?? calendar.startOfDay(for: month)
        let monthEnd = calendar.date(byAdding: DateComponents(month: 1, day: -1), to: monthStart) ?? monthStart
        let prevStart = calendar.date(byAdding: .month, value: -1, to: monthStart) ?? monthStart
        let prevEnd = calendar.date(byAdding: .day, value: -1, to: monthStart) ?? prevStart
        let prevKey = Self.monthString(prevStart)

        let financeOverview: BackendFinanceOverviewResponse = try await client.sendResult("/agent/finance/overview")
        let allTransactions: [BackendFinanceTransactionResponse] = try await client.sendResult("/agent/finance/transactions")
        let expenseCents = allTransactions
            .filter { $0.date.hasPrefix(monthKey) && $0.normalizedCashflowType == "expense" }
            .reduce(0) { $0 + Int($1.amountInCents) }
        let prevExpenseCents = allTransactions
            .filter { $0.date.hasPrefix(prevKey) && $0.normalizedCashflowType == "expense" }
            .reduce(0) { $0 + Int($1.amountInCents) }

        let studyStats: FallbackStudyStats? = try? await client.sendResult(
            "/agent/study-sessions/statistics",
            query: [
                URLQueryItem(name: "from", value: Self.dayString(monthStart)),
                URLQueryItem(name: "to", value: Self.dayString(monthEnd)),
                URLQueryItem(name: "timezone", value: tz),
            ]
        )
        let prevStudyStats: FallbackStudyStats? = try? await client.sendResult(
            "/agent/study-sessions/statistics",
            query: [
                URLQueryItem(name: "from", value: Self.dayString(prevStart)),
                URLQueryItem(name: "to", value: Self.dayString(prevEnd)),
                URLQueryItem(name: "timezone", value: tz),
            ]
        )
        let recordMinutes = Int((studyStats?.totalDurationSeconds ?? 0) / 60)
        let prevRecordMinutes = Int((prevStudyStats?.totalDurationSeconds ?? 0) / 60)

        let recordFrom = Self.dayString(calendar.date(byAdding: .day, value: -710, to: .now) ?? .now)
        let recordTo = Self.dayString(calendar.date(byAdding: .day, value: 10, to: .now) ?? .now)
        let records: [FallbackRecordItem] = (try? await client.sendResult(
            "/agent/records",
            query: [
                URLQueryItem(name: "from", value: recordFrom),
                URLQueryItem(name: "to", value: recordTo),
            ]
        )) ?? []
        let activeRecords = records.filter { !$0.archived && $0.trashedAt == nil }
        let docWords = activeRecords.reduce(0) {
            $0 + ($1.contentText?.trimmingCharacters(in: .whitespacesAndNewlines).count ?? 0)
        }

        let today = calendar.startOfDay(for: .now)
        var todoCount = 0
        var completedTodos = 0
        if monthStart <= today {
            let effectiveEnd = min(monthEnd, today)
            let days: [FallbackDaySummary] = (try? await client.sendResult(
                "/agent/daily-events/days",
                query: [
                    URLQueryItem(name: "from", value: Self.dayString(monthStart)),
                    URLQueryItem(name: "to", value: Self.dayString(effectiveEnd)),
                ]
            )) ?? []
            for day in days {
                todoCount += day.todoCount ?? 0
                completedTodos += day.completedTodoCount ?? 0
            }
        }

        return ProfileOverview(
            month: month,
            totalAssetsInCents: Int((financeOverview.totalAssets * 100).rounded()),
            totalExpenseInCents: expenseCents,
            totalRecordMinutes: recordMinutes,
            documentCount: activeRecords.count,
            documentWordCount: docWords,
            monthlyCompletedTodos: completedTodos,
            monthlyTodoCount: todoCount,
            isDemo: false,
            assetsGrowthRate: nil,
            expenseGrowthRate: Self.growthRate(current: expenseCents, previous: prevExpenseCents),
            recordMinutesGrowthRate: Self.growthRate(current: recordMinutes, previous: prevRecordMinutes)
        )
    }

    private static func growthRate(current: Int, previous: Int) -> Double? {
        guard previous > 0 else { return nil }
        return Double(current - previous) / Double(previous)
    }

    private static func dayString(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    private static func monthString(_ date: Date) -> String {
        let parts = Calendar.current.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
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

private struct FallbackStudyStats: Decodable {
    let totalDurationSeconds: Int64
}

private struct FallbackRecordItem: Decodable {
    let contentText: String?
    let archived: Bool
    let trashedAt: Date?
}

private struct FallbackDaySummary: Decodable {
    let todoCount: Int?
    let completedTodoCount: Int?
}
