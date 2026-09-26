import Foundation

/// “我的”总览的服务端投影：累计值与自然月值分开，金额统一使用分。
struct ProfileOverview: Equatable, Sendable {
    let month: Date
    let totalAssetsInCents: Int
    let monthlyExpenseInCents: Int
    let totalRecordMinutes: Int
    let documentCount: Int
    let documentWordCount: Int
    let monthlyCompletedTodos: Int
    let monthlyTodoCount: Int
    let isDemo: Bool

    /// 当月没有待办时没有可计算的完成率，不把“无待办”误写为 0%。
    var monthlyTodoCompletionRate: Double? {
        guard monthlyTodoCount > 0 else { return nil }
        return Double(monthlyCompletedTodos) / Double(monthlyTodoCount)
    }
}
