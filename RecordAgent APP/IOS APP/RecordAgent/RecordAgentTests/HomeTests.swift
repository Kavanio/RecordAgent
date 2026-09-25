import XCTest
@testable import RecordAgent

final class HomeTests: XCTestCase {
    func testOverviewDataCalculations() {
        let overview = HomeOverviewData(
            todoCompleted: 3,
            todoTotal: 5,
            scheduleCount: 2,
            expenseAmount: 86.5,
            studyHours: 2.5
        )

        XCTAssertEqual(overview.todoProgress, 0.6, accuracy: 0.001)
        XCTAssertEqual(overview.formattedExpense, "¥ 86.5")
        XCTAssertEqual(overview.formattedStudyHours, "2.5")

        // 边界条件：总数为 0 时
        let emptyOverview = HomeOverviewData(todoCompleted: 0, todoTotal: 0)
        XCTAssertEqual(emptyOverview.todoProgress, 0.0)
    }

    @MainActor
    func testHomeViewModelInitialState() {
        let viewModel = HomeViewModel()

        XCTAssertEqual(viewModel.userName, "王俊")
        XCTAssertEqual(viewModel.currentMonthText, "9月")
        XCTAssertEqual(viewModel.weekDays.count, 7)
        XCTAssertEqual(viewModel.weekDays.first?.dayNumber, 21)
        XCTAssertEqual(viewModel.weekDays.last?.dayNumber, 27)

        // 验证默认选中 23 号
        XCTAssertTrue(viewModel.selectedDateId.contains("23"))

        // 验证初始待办列表
        XCTAssertEqual(viewModel.recentTodos.count, 3)
        XCTAssertEqual(viewModel.overviewData.todoCompleted, 3)
        XCTAssertEqual(viewModel.overviewData.todoTotal, 5)
    }

    @MainActor
    func testSelectDate() {
        let viewModel = HomeViewModel()
        guard let day25 = viewModel.weekDays.first(where: { $0.dayNumber == 25 }) else {
            XCTFail("Should find day 25")
            return
        }

        viewModel.selectDate(day25)
        XCTAssertEqual(viewModel.selectedDateId, day25.id)
    }

    @MainActor
    func testToggleTodoCompletion() {
        let viewModel = HomeViewModel()
        guard let firstTodo = viewModel.recentTodos.first else {
            XCTFail("Should have initial todos")
            return
        }

        let initialCompleted = viewModel.overviewData.todoCompleted

        // 1. 勾选第一项
        viewModel.toggleTodoCompletion(id: firstTodo.id)
        XCTAssertTrue(viewModel.recentTodos.first?.isCompleted ?? false)
        XCTAssertEqual(viewModel.overviewData.todoCompleted, initialCompleted + 1)

        // 2. 再次勾选，取消完成
        viewModel.toggleTodoCompletion(id: firstTodo.id)
        XCTAssertFalse(viewModel.recentTodos.first?.isCompleted ?? true)
        XCTAssertEqual(viewModel.overviewData.todoCompleted, initialCompleted)
    }

    @MainActor
    func testAddQuickTodo() {
        let viewModel = HomeViewModel()
        let initialCount = viewModel.recentTodos.count
        let initialTotal = viewModel.overviewData.todoTotal

        viewModel.quickAddTitle = "跑步 5 公里"
        viewModel.addQuickTodo()

        XCTAssertEqual(viewModel.recentTodos.count, initialCount + 1)
        XCTAssertEqual(viewModel.overviewData.todoTotal, initialTotal + 1)
        XCTAssertEqual(viewModel.recentTodos.first?.title, "跑步 5 公里")
        XCTAssertEqual(viewModel.quickAddTitle, "")
    }
}
