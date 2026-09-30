import Foundation

/// 专注时段以绝对时间保存；自然月筛选由设备时区转换。
struct StudyEntry: Identifiable, Equatable, Sendable {
    let id: UUID
    let title: String
    let startedAt: Date
    let endedAt: Date?
    let timeZone: String
    let version: Int64

    var isActive: Bool { endedAt == nil }

    func durationSeconds(at now: Date = .now) -> TimeInterval {
        max(0, (endedAt ?? now).timeIntervalSince(startedAt))
    }
}

struct StudyOverview: Equatable, Sendable {
    let minutes: Int64
    let sessionCount: Int64
    let activeDays: Int64
}

struct StudyPage: Sendable {
    let items: [StudyEntry]
    let nextOffset: Int?
    let hasMore: Bool
}

struct StudyDraft: Equatable, Sendable {
    let id: UUID
    let title: String
    let startedAt: Date
    let endedAt: Date?
    let timeZone: String
}

enum StudyValidationError: LocalizedError, Sendable {
    case invalidTitle
    case invalidInterval
    case activeExists
    case operationInProgress

    var errorDescription: String? {
        switch self {
        case .invalidTitle: return String(localized: "record.error.title")
        case .invalidInterval: return String(localized: "record.error.interval")
        case .activeExists: return String(localized: "record.error.active_exists")
        case .operationInProgress: return String(localized: "workspace.error.busy")
        }
    }
}

enum StudyRules {
    static let minimumSeconds: TimeInterval = 60
    static let maximumSeconds: TimeInterval = 7 * 24 * 60 * 60

    static func isValidTitle(_ title: String) -> Bool {
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return !value.isEmpty && value.utf16.count <= 240
    }

    /// 校验绝对时长，跨午夜或夏令时的手动补录不按墙上时钟推算。
    static func isValidInterval(start: Date, end: Date, now: Date = .now) -> Bool {
        let seconds = end.timeIntervalSince(start)
        return seconds >= minimumSeconds && seconds <= maximumSeconds && end <= now
    }

    static func monthKey(_ date: Date, calendar: Calendar = .current) -> String {
        var gregorian = Calendar(identifier: .gregorian)
        gregorian.timeZone = calendar.timeZone
        let parts = gregorian.dateComponents([.year, .month], from: date)
        return String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
    }
}

protocol StudyServiceProtocol: Sendable {
    func entries(month: String, timeZone: String, query: String, offset: Int, limit: Int) async throws -> StudyPage
    func overview(month: String, timeZone: String) async throws -> StudyOverview
    func active() async throws -> StudyEntry?
    func create(_ draft: StudyDraft) async throws -> StudyEntry
    func finish(id: UUID, version: Int64, endedAt: Date) async throws -> StudyEntry
    func delete(id: UUID, version: Int64) async throws
}

/// 未确认创建结果时，同内容重试保留 UUID 与开始时间，避免重复补录。
/// 仅保留当前进程内的待确认请求；不承诺离线队列或重启重发。
struct StudySubmissionState {
    private var pending: (title: String, start: Date?, end: Date?, zone: String, draft: StudyDraft)?

    mutating func draft(title: String, start: Date?, end: Date?, zone: String, now: Date) -> StudyDraft {
        let value = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if let pending, pending.title == value, pending.start == start,
           pending.end == end, pending.zone == zone {
            return pending.draft
        }
        let draft = StudyDraft(id: UUID(), title: value, startedAt: start ?? now, endedAt: end, timeZone: zone)
        pending = (value, start, end, zone, draft)
        return draft
    }

    mutating func completed(id: UUID) {
        if pending?.draft.id == id { pending = nil }
    }
}
