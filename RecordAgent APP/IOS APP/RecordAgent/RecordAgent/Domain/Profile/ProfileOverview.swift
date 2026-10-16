import Foundation

/// “我的”总览的服务端投影：累计值与自然月值分开，金额统一使用分。
struct ProfileOverview: Equatable, Sendable {
    let month: Date
    let totalAssetsInCents: Int
    let totalExpenseInCents: Int
    let totalRecordMinutes: Int
    let documentCount: Int
    let documentWordCount: Int
    let monthlyCompletedTodos: Int
    let monthlyTodoCount: Int
    let isDemo: Bool

    /// 资产相较于上个月的变动率（如 0.086 代表增长 8.6%，-0.031 代表下降 3.1%）
    let assetsGrowthRate: Double?
    /// 支出相较于上个月的变动率（如 -0.042 代表支出下降 4.2%）
    let expenseGrowthRate: Double?
    /// 记录时长相较于上个月的变动率（如 0.152 代表增长 15.2%）
    let recordMinutesGrowthRate: Double?

    init(
        month: Date,
        totalAssetsInCents: Int,
        totalExpenseInCents: Int,
        totalRecordMinutes: Int,
        documentCount: Int,
        documentWordCount: Int,
        monthlyCompletedTodos: Int,
        monthlyTodoCount: Int,
        isDemo: Bool,
        assetsGrowthRate: Double? = nil,
        expenseGrowthRate: Double? = nil,
        recordMinutesGrowthRate: Double? = nil
    ) {
        self.month = month
        self.totalAssetsInCents = totalAssetsInCents
        self.totalExpenseInCents = totalExpenseInCents
        self.totalRecordMinutes = totalRecordMinutes
        self.documentCount = documentCount
        self.documentWordCount = documentWordCount
        self.monthlyCompletedTodos = monthlyCompletedTodos
        self.monthlyTodoCount = monthlyTodoCount
        self.isDemo = isDemo
        self.assetsGrowthRate = assetsGrowthRate
        self.expenseGrowthRate = expenseGrowthRate
        self.recordMinutesGrowthRate = recordMinutesGrowthRate
    }

    /// 兼容旧参数名构造器
    init(
        month: Date,
        totalAssetsInCents: Int,
        monthlyExpenseInCents: Int,
        totalRecordMinutes: Int,
        documentCount: Int,
        documentWordCount: Int,
        monthlyCompletedTodos: Int,
        monthlyTodoCount: Int,
        isDemo: Bool,
        assetsGrowthRate: Double? = nil,
        expenseGrowthRate: Double? = nil,
        recordMinutesGrowthRate: Double? = nil
    ) {
        self.init(
            month: month,
            totalAssetsInCents: totalAssetsInCents,
            totalExpenseInCents: monthlyExpenseInCents,
            totalRecordMinutes: totalRecordMinutes,
            documentCount: documentCount,
            documentWordCount: documentWordCount,
            monthlyCompletedTodos: monthlyCompletedTodos,
            monthlyTodoCount: monthlyTodoCount,
            isDemo: isDemo,
            assetsGrowthRate: assetsGrowthRate,
            expenseGrowthRate: expenseGrowthRate,
            recordMinutesGrowthRate: recordMinutesGrowthRate
        )
    }

    /// 兼容旧命名
    var monthlyExpenseInCents: Int { totalExpenseInCents }

    /// 当月没有待办时没有可计算的完成率，不把“无待办”误写为 0%。
    var monthlyTodoCompletionRate: Double? {
        guard monthlyTodoCount > 0 else { return nil }
        return Double(monthlyCompletedTodos) / Double(monthlyTodoCount)
    }
}
