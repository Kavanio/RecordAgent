import Foundation

/// 与首页共用同一数据源，补充月摘要、周期待办与日历新增操作。
protocol CalendarDataServiceProtocol: HomeDataServiceProtocol {
    func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot
    func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup]
    func createEntry(_ draft: CalendarEntryDraft) async throws
}

extension UnavailableHomeDataService: CalendarDataServiceProtocol {
    func month(for date: Date, locale: Locale) async throws -> CalendarMonthSnapshot {
        throw HomeDataError.unavailable
    }
    func periodicTodos(for date: Date, locale: Locale) async throws -> [CalendarTodoGroup] {
        throw HomeDataError.unavailable
    }
    func createEntry(_ draft: CalendarEntryDraft) async throws { throw HomeDataError.unavailable }
}
