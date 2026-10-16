import Foundation

enum HomeCategory: String, CaseIterable, Identifiable, Sendable {
    case todo, finance, library, record
    var id: String { rawValue }
}

/// 当日信息流中的领域记录；金额使用分，专注时长使用分钟。
struct HomeEntry: Identifiable, Equatable, Sendable {
    enum Kind: String, Sendable {
        case todo, schedule, expense, income, document, photo, focus
        var category: HomeCategory {
            switch self {
            case .todo, .schedule: return .todo
            case .expense, .income: return .finance
            case .document, .photo: return .library
            case .focus: return .record
            }
        }
    }
    let id: String
    let kind: Kind
    let title: String
    let detail: String
    let date: Date
    /// 资料的服务端创建/修改时间；date 保留记录所属自然日及创建时刻的本地时间。
    var createdAt: Date? = nil
    var updatedAt: Date? = nil
    var isCompleted = false
    var amountInCents = 0
    var durationMinutes = 0
    /// 仅用于明确标注的开发样例；真实附件需由服务端稳定文件 ID 映射。
    var sampleImageNames: [String] = []
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
    var documentCount: Int { entries.filter { $0.kind.category == .library }.count }
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
    var supportedDateTimes: ClosedRange<Date> {
        supportedDates.lowerBound...addingDays(1, to: supportedDates.upperBound).addingTimeInterval(-1)
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

    func startOfMonth(containing date: Date) -> Date {
        calendar.dateInterval(of: .month, for: date)?.start ?? calendar.startOfDay(for: date)
    }

    /// 月网格固定周一起始，只补齐首尾周；四、五、六周月份均按自然日生成。
    func monthGrid(containing date: Date) -> [Date] {
        let start = startOfMonth(containing: date)
        guard let count = calendar.range(of: .day, in: .month, for: start)?.count else { return [] }
        let leading = (calendar.component(.weekday, from: start) + 5) % 7
        let cells = ((leading + count + 6) / 7) * 7
        return (0..<cells).map { addingDays($0 - leading, to: start) }
    }

    func shiftingMonth(_ offset: Int, from date: Date) -> Date? {
        guard let month = calendar.date(byAdding: .month, value: offset, to: startOfMonth(containing: date)),
            let target = self.date(
                inYear: calendar.component(.year, from: month),
                month: calendar.component(.month, from: month), preservingDayOf: date),
            supportedDates.contains(target)
        else { return nil }
        return target
    }

    /// 返回所选自然周内的指定 ISO 星期，不使用固定秒数跨日。
    func weeklyOccurrence(weekday: Int, containing date: Date) -> Date {
        let offset = (calendar.component(.weekday, from: date) + 5) % 7
        let monday = calendar.startOfDay(for: addingDays(-offset, to: date))
        return addingDays(weekday - 1, to: monday)
    }

    func periodEnd(_ period: CalendarTodoPeriod, containing date: Date) -> Date {
        if period == .daily { return calendar.startOfDay(for: date) }
        if period == .weekly {
            let weekday = (calendar.component(.weekday, from: date) + 5) % 7
            return calendar.startOfDay(for: addingDays(6 - weekday, to: date))
        }
        let start = startOfMonth(containing: date)
        let count = calendar.range(of: .day, in: .month, for: start)?.count ?? 1
        return addingDays(count - 1, to: start)
    }
}
