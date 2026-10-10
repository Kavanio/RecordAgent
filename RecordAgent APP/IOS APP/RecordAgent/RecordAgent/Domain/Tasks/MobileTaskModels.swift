import Foundation

enum MobileTaskRecurrence: String, Codable, CaseIterable, Hashable, Sendable {
    case once, daily, weekly, monthly
}

enum MobileTaskFilter: String, CaseIterable, Hashable, Sendable {
    case all, enabled, paused
    var enabled: Bool? {
        switch self { case .all: return nil; case .enabled: return true; case .paused: return false }
    }
}

/// 自动提醒与日历待办是独立领域；配置保存、系统注册、实际观察到送达分别表达。
struct MobileTask: Codable, Identifiable, Sendable, Equatable {
    let id: String
    var title: String
    var body: String
    var recurrence: MobileTaskRecurrence
    var hour: Int
    var minute: Int
    var weekday: Int?
    var dayOfMonth: Int?
    var onceDate: String?
    var timeZone: String
    var enabled: Bool
    var version: Int64

    static func draft(now: Date = .now, timeZone: TimeZone = .current) -> MobileTask {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        let next = calendar.date(byAdding: .hour, value: 1, to: now) ?? now
        return MobileTask(id: UUID().uuidString, title: "", body: "", recurrence: .once,
                          hour: calendar.component(.hour, from: next), minute: calendar.component(.minute, from: next),
                          weekday: nil, dayOfMonth: nil, onceDate: MobileTaskSchedule.dayKey(next, in: timeZone),
                          timeZone: timeZone.identifier, enabled: true, version: 0)
    }
}

struct MobileTaskPage<Item: Decodable & Sendable>: Decodable, Sendable {
    let items: [Item]
    let nextOffset: Int?
    let hasMore: Bool
}

struct MobileTaskDelivery: Decodable, Identifiable, Sendable, Equatable {
    let id: String
    let deliveredAt: Date
}

struct MobileTaskObservation: Sendable, Equatable {
    let taskID: String
    let eventID: String
    let deliveredAt: Date
}

enum MobileNotificationPermission: Sendable, Equatable {
    case notDetermined, denied, authorized, provisional
    var canRegister: Bool { self == .authorized || self == .provisional }
}

enum MobileTaskRegistration: Sendable, Equatable {
    case registered, permissionRequired, permissionDenied, failed, paused, expired, unknown
}

struct MobileTaskNotificationSnapshot: Sendable {
    var permission: MobileNotificationPermission
    var registrations: [String: MobileTaskRegistration]
}

enum MobileTaskError: LocalizedError, Sendable {
    case invalidSchedule, invalidTitle, registrationFailed
    var errorDescription: String? {
        switch self {
        case .invalidSchedule: return String(localized: "tasks.error.schedule")
        case .invalidTitle: return String(localized: "tasks.error.title")
        case .registrationFailed: return String(localized: "tasks.error.registration")
        }
    }
}

protocol MobileTaskServiceProtocol: Sendable {
    func list(query: String, enabled: Bool?, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTask>
    func task(id: String) async throws -> MobileTask
    func enabledTasks() async throws -> [MobileTask]
    func save(_ task: MobileTask, creating: Bool) async throws -> MobileTask
    func delete(id: String, version: Int64) async throws
    func deliveries(taskID: String, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTaskDelivery>
    func observe(_ observation: MobileTaskObservation) async throws
}

protocol MobileTaskNotificationProtocol: Sendable {
    func activate(accountID: String) async -> UUID
    func permission() async -> MobileNotificationPermission
    func requestPermission() async throws -> MobileNotificationPermission
    func reconcile(tasks: [MobileTask], accountID: String, sessionToken: UUID) async -> MobileTaskNotificationSnapshot
    func observations(accountID: String, sessionToken: UUID) async -> [MobileTaskObservation]
    func acknowledge(eventID: String) async
    func remove(taskID: String, accountID: String, sessionToken: UUID, includeDelivered: Bool) async
    func clearAll() async
}

/// 使用自然日与 IANA 时区生成系统日历触发条件；每月只允许 1–28 日。
enum MobileTaskSchedule {
    static func components(for task: MobileTask) throws -> DateComponents {
        guard (0...23).contains(task.hour), (0...59).contains(task.minute),
              let zone = TimeZone(identifier: task.timeZone)
        else { throw MobileTaskError.invalidSchedule }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        var parts = DateComponents()
        parts.calendar = calendar
        parts.timeZone = zone
        parts.hour = task.hour
        parts.minute = task.minute
        switch task.recurrence {
        case .once:
            guard let key = task.onceDate, let day = date(key, in: zone) else { throw MobileTaskError.invalidSchedule }
            let natural = calendar.dateComponents([.year, .month, .day], from: day)
            parts.year = natural.year
            parts.month = natural.month
            parts.day = natural.day
        case .daily: break
        case .weekly:
            guard let weekday = task.weekday, (1...7).contains(weekday) else { throw MobileTaskError.invalidSchedule }
            parts.weekday = weekday == 7 ? 1 : weekday + 1
        case .monthly:
            guard let day = task.dayOfMonth, (1...28).contains(day) else { throw MobileTaskError.invalidSchedule }
            parts.day = day
        }
        return parts
    }

    static func nextDate(for task: MobileTask, after now: Date = .now) -> Date? {
        guard let parts = try? components(for: task), let calendar = parts.calendar else { return nil }
        if task.recurrence == .once {
            guard let date = calendar.date(from: parts), date > now else { return nil }
            return date
        }
        return calendar.nextDate(after: now, matching: parts, matchingPolicy: .nextTime,
                                 repeatedTimePolicy: .first, direction: .forward)
    }

    static func date(_ key: String, in zone: TimeZone) -> Date? {
        let numbers = key.split(separator: "-").compactMap { Int($0) }
        guard numbers.count == 3, key.count == 10 else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = DateComponents(year: numbers[0], month: numbers[1], day: numbers[2])
        guard let date = calendar.date(from: parts) else { return nil }
        let normalized = calendar.dateComponents([.year, .month, .day], from: date)
        guard normalized.year == parts.year, normalized.month == parts.month, normalized.day == parts.day else { return nil }
        return date
    }

    static func dayKey(_ date: Date, in zone: TimeZone) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}
