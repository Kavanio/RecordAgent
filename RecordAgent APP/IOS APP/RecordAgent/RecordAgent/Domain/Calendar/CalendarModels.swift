import Foundation

enum CalendarTodoPeriod: String, CaseIterable, Identifiable, Sendable {
    case weekly, monthly
    var id: String { rawValue }
}

/// 月份只传有界标记和封面引用，正文仍按选中日期读取。
struct CalendarDayMarker: Identifiable, Equatable, Sendable {
    let date: Date
    let kinds: [HomeEntry.Kind]
    let sampleImageName: String?
    var id: Date { date }
}

struct CalendarMonthSnapshot: Equatable, Sendable {
    let month: Date
    let days: [CalendarDayMarker]
}

struct CalendarTodoGroup: Identifiable, Equatable, Sendable {
    let period: CalendarTodoPeriod
    let entries: [HomeEntry]
    var id: CalendarTodoPeriod { period }
    var completedCount: Int { entries.filter(\.isCompleted).count }
}

/// 当前新增入口承载待办、日程和手记；财务写入沿用后续账户与交易流程。
struct CalendarEntryDraft: Sendable {
    enum Kind: String, CaseIterable, Identifiable, Sendable {
        case todo, schedule, note
        var id: String { rawValue }
        var entryKind: HomeEntry.Kind {
            switch self {
            case .todo: return .todo
            case .schedule: return .schedule
            case .note: return .document
            }
        }
    }
    var kind: Kind = .todo
    var title = ""
    var detail = ""
    var date: Date
    var period: CalendarTodoPeriod?
    var isValid: Bool { !title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }
}
