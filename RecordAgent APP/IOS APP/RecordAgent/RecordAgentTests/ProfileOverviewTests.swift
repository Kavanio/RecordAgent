import XCTest

@testable import RecordAgent

final class ProfileOverviewTests: XCTestCase {
    func testMonthlyCompletionRateDistinguishesNoTodosFromZeroCompleted() {
        let base = ProfileOverview(
            month: Date(), totalAssetsInCents: 0, monthlyExpenseInCents: 0,
            totalRecordMinutes: 0, documentCount: 0, documentWordCount: 0,
            monthlyCompletedTodos: 0, monthlyTodoCount: 0, isDemo: false)
        XCTAssertNil(base.monthlyTodoCompletionRate)

        let noneCompleted = ProfileOverview(
            month: base.month, totalAssetsInCents: 0, monthlyExpenseInCents: 0,
            totalRecordMinutes: 0, documentCount: 0, documentWordCount: 0,
            monthlyCompletedTodos: 0, monthlyTodoCount: 4, isDemo: false)
        XCTAssertEqual(noneCompleted.monthlyTodoCompletionRate, 0)

        let partlyCompleted = ProfileOverview(
            month: base.month, totalAssetsInCents: 0, monthlyExpenseInCents: 0,
            totalRecordMinutes: 0, documentCount: 0, documentWordCount: 0,
            monthlyCompletedTodos: 3, monthlyTodoCount: 4, isDemo: false)
        XCTAssertEqual(partlyCompleted.monthlyTodoCompletionRate, 0.75)
    }

    @MainActor
    func testUnavailableOverviewDoesNotShowSampleMetrics() async {
        let model = ProfileOverviewViewModel(service: UnavailableProfileOverviewService())
        await model.load(userID: "user-1", month: Date())
        XCTAssertEqual(model.state, .unavailable)
    }
}
