import Foundation

enum HomeCategory: String, CaseIterable, Identifiable, Sendable {
    case todo, finance, library, record
    var id: String { rawValue }
}

/// 当日信息流中的领域记录；金额使用分，专注时长使用分钟。
struct HomeEntry: Identifiable, Equatable, Sendable {
    enum Kind: String, Sendable {
        case todo, expense, income, document, focus
        var category: HomeCategory {
            switch self {
            case .todo: return .todo
            case .expense, .income: return .finance
            case .document: return .library
            case .focus: return .record
            }
        }
    }
    let id: String
    let kind: Kind
    let title: String
    let detail: String
    let date: Date
    var isCompleted = false
    var amountInCents = 0
    var durationMinutes = 0
}

struct HomeTrendPoint: Identifiable, Equatable, Sendable {
    let date: Date
    let expenseInCents: Int
    let focusMinutes: Int
    var id: Date { date }
}

struct HomeDaySnapshot: Equatable, Sendable {
    let date: Date
    var entries: [HomeEntry]
    let trend: [HomeTrendPoint]
    let isDemo: Bool
    var todos: [HomeEntry] { entries.filter { $0.kind == .todo } }
    var completedTodos: Int { todos.filter(\.isCompleted).count }
    var expenseInCents: Int { entries.filter { $0.kind == .expense }.reduce(0) { $0 + $1.amountInCents } }
    var incomeInCents: Int { entries.filter { $0.kind == .income }.reduce(0) { $0 + $1.amountInCents } }
    var documentCount: Int { entries.filter { $0.kind == .document }.count }
    var focusMinutes: Int { entries.filter { $0.kind == .focus }.reduce(0) { $0 + $1.durationMinutes } }
}

/// 统一处理跨年、闰年与夏令时边界，避免用固定秒数推算自然日。
struct HomeCalendar {
    var calendar: Calendar = .current
    var supportedDates: ClosedRange<Date> {
        let start = calendar.date(from: DateComponents(year: 1900, month: 1, day: 1)) ?? .distantPast
        let end = calendar.date(from: DateComponents(year: 2100, month: 12, day: 31)) ?? .distantFuture
        return start...end
    }
    func startOfWeek(containing date: Date) -> Date {
        calendar.dateInterval(of: .weekOfYear, for: date)?.start ?? calendar.startOfDay(for: date)
    }
    func addingDays(_ days: Int, to date: Date) -> Date {
        calendar.date(byAdding: .day, value: days, to: date) ?? date
    }
    func days(inWeek week: Date) -> [Date] {
        (0..<7).map { addingDays($0, to: week) }
    }
    func date(inYear year: Int, month: Int, preservingDayOf date: Date) -> Date? {
        guard let start = calendar.date(from: DateComponents(year: year, month: month, day: 1)),
            let range = calendar.range(of: .day, in: .month, for: start)
        else { return nil }
        return calendar.date(bySetting: .day, value: min(calendar.component(.day, from: date), range.count), of: start)
    }
    func isSupportedWeek(_ date: Date) -> Bool {
        date >= startOfWeek(containing: supportedDates.lowerBound)
            && date <= startOfWeek(containing: supportedDates.upperBound)
    }
    func weeks(around date: Date) -> [Date] {
        let start = startOfWeek(containing: date)
        return (-26...26).map { addingDays($0 * 7, to: start) }.filter(isSupportedWeek)
    }
}
