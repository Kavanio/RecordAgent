import Foundation

/// 首页“今日概览”核心统计指标数据模型
public struct HomeOverviewData: Equatable {
    /// 已完成待办数
    public var todoCompleted: Int
    /// 待办总数
    public var todoTotal: Int
    /// 今日日程数量
    public var scheduleCount: Int
    /// 今日支出总金额（以元为单位展示）
    public var expenseAmount: Double
    /// 今日累计专注/学习时长（以小时为单位）
    public var studyHours: Double

    public init(
        todoCompleted: Int = 3,
        todoTotal: Int = 5,
        scheduleCount: Int = 2,
        expenseAmount: Double = 86.5,
        studyHours: Double = 2.5
    ) {
        self.todoCompleted = todoCompleted
        self.todoTotal = todoTotal
        self.scheduleCount = scheduleCount
        self.expenseAmount = expenseAmount
        self.studyHours = studyHours
    }

    /// 待办完成百分比（0.0 ~ 1.0）
    public var todoProgress: Double {
        guard todoTotal > 0 else { return 0 }
        return min(max(Double(todoCompleted) / Double(todoTotal), 0), 1.0)
    }

    /// 支出格式化文本（保留 1 位小数）
    public var formattedExpense: String {
        return String(format: "¥ %.1f", expenseAmount)
    }

    /// 学习时长格式化文本
    public var formattedStudyHours: String {
        return String(format: "%.1f", studyHours)
    }
}

/// 首页近期待办事项模型
public struct RecentTodoItem: Identifiable, Equatable {
    public let id: UUID
    /// 待办事项标题
    public var title: String
    /// 预定执行时间或标签（如 "10:00", "14:00", "今天"）
    public var timeString: String
    /// 是否已勾选完成
    public var isCompleted: Bool

    public init(
        id: UUID = UUID(),
        title: String,
        timeString: String,
        isCompleted: Bool = false
    ) {
        self.id = id
        self.title = title
        self.timeString = timeString
        self.isCompleted = isCompleted
    }
}

/// 周历条单日信息模型
public struct WeekCalendarDay: Identifiable, Equatable {
    public var id: String { "\(dayNumber)-\(weekdaySymbol)" }
    public let date: Date
    /// 星期中文简写（如 "一", "二", "三", "四", "五", "六", "日"）
    public let weekdaySymbol: String
    /// 日期数字（如 21, 22, 23...）
    public let dayNumber: Int
    /// 是否为今天
    public let isToday: Bool

    public init(
        date: Date,
        weekdaySymbol: String,
        dayNumber: Int,
        isToday: Bool = false
    ) {
        self.date = date
        self.weekdaySymbol = weekdaySymbol
        self.dayNumber = dayNumber
        self.isToday = isToday
    }
}
