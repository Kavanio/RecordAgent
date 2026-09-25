import Foundation
import SwiftUI

/// 首页交互与数据状态驱动视图模型
@MainActor
public final class HomeViewModel: ObservableObject {
    // MARK: - 用户信息与问候
    @Published public var userName: String = "王俊"
    @Published public var greetingSubtitle: String = "新的一天，也会很好。"

    // MARK: - 日期与周历条
    @Published public var currentMonthText: String = "9月"
    @Published public var weekDays: [WeekCalendarDay] = []
    @Published public var selectedDateId: String = ""

    // MARK: - 核心概览指标
    @Published public var overviewData: HomeOverviewData = HomeOverviewData(
        todoCompleted: 3,
        todoTotal: 5,
        scheduleCount: 2,
        expenseAmount: 86.5,
        studyHours: 2.5
    )

    // MARK: - 近期待办清单
    @Published public var recentTodos: [RecentTodoItem] = [
        RecentTodoItem(title: "完成八股文复习", timeString: "10:00", isCompleted: false),
        RecentTodoItem(title: "整理项目文档", timeString: "14:00", isCompleted: false),
        RecentTodoItem(title: "健身 30 分钟", timeString: "今天", isCompleted: false)
    ]

    // MARK: - 交互弹窗与反馈
    @Published public var showQuickAddSheet: Bool = false
    @Published public var quickAddTitle: String = ""
    @Published public var showSearchAlert: Bool = false
    @Published public var showNotificationAlert: Bool = false
    @Published public var unreadNotificationCount: Int = 1

    public init() {
        setupWeekDays()
    }

    /// 初始化周历日期数据（对齐设计图：9月 21~27 日，23 日选中）
    private func setupWeekDays() {
        let calendar = Calendar.current
        let weekdayNames = ["一", "二", "三", "四", "五", "六", "日"]
        var days: [WeekCalendarDay] = []

        // 构建 21 ~ 27 日
        for (index, dayNum) in (21...27).enumerated() {
            var components = DateComponents()
            components.year = 2026
            components.month = 9
            components.day = dayNum
            let date = calendar.date(from: components) ?? Date()
            let isToday = (dayNum == 23)
            let item = WeekCalendarDay(
                date: date,
                weekdaySymbol: weekdayNames[index % 7],
                dayNumber: dayNum,
                isToday: isToday
            )
            days.append(item)
        }

        self.weekDays = days
        // 默认选中 23 号
        if let defaultSelected = days.first(where: { $0.dayNumber == 23 }) {
            self.selectedDateId = defaultSelected.id
        } else if let first = days.first {
            self.selectedDateId = first.id
        }
    }

    /// 切换选中的日期
    public func selectDate(_ day: WeekCalendarDay) {
        withAnimation(.spring(response: 0.3, dampingFraction: 0.7)) {
            self.selectedDateId = day.id
        }
    }

    /// 勾选/取消勾选待办事项，并联动概览数据中的完成计数
    public func toggleTodoCompletion(id: UUID) {
        guard let index = recentTodos.firstIndex(where: { $0.id == id }) else { return }
        withAnimation(.spring(response: 0.25, dampingFraction: 0.8)) {
            let newState = !recentTodos[index].isCompleted
            recentTodos[index].isCompleted = newState

            // 联动今日概览完成数
            if newState {
                overviewData.todoCompleted = min(overviewData.todoCompleted + 1, overviewData.todoTotal)
            } else {
                overviewData.todoCompleted = max(overviewData.todoCompleted - 1, 0)
            }
        }
    }

    /// 快捷添加待办
    public func addQuickTodo() {
        guard !quickAddTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        let newTodo = RecentTodoItem(
            title: quickAddTitle.trimmingCharacters(in: .whitespacesAndNewlines),
            timeString: "今天",
            isCompleted: false
        )
        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
            recentTodos.insert(newTodo, at: 0)
            overviewData.todoTotal += 1
            quickAddTitle = ""
            showQuickAddSheet = false
        }
    }
}
