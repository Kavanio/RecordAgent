import SwiftUI

extension MobileTaskRecurrence {
    var title: LocalizedStringKey {
        switch self {
        case .once: return "tasks.recurrence.once"
        case .daily: return "tasks.recurrence.daily"
        case .weekly: return "tasks.recurrence.weekly"
        case .monthly: return "tasks.recurrence.monthly"
        }
    }
}

extension MobileTaskFilter {
    var title: LocalizedStringKey {
        switch self { case .all: return "tasks.filter.all"; case .enabled: return "tasks.filter.enabled"; case .paused: return "tasks.filter.paused" }
    }
}

extension MobileTaskRegistration {
    var title: LocalizedStringKey {
        switch self {
        case .registered: return "tasks.registration.registered"
        case .permissionRequired: return "tasks.registration.permission_required"
        case .permissionDenied: return "tasks.registration.permission_denied"
        case .failed: return "tasks.registration.failed"
        case .paused: return "tasks.registration.paused"
        case .expired: return "tasks.registration.expired"
        case .unknown: return "tasks.registration.unknown"
        }
    }

    var symbol: String {
        switch self {
        case .registered: return "bell"
        case .paused, .expired: return "bell.slash"
        default: return "exclamationmark.circle"
        }
    }
}

enum MobileTaskPresentation {
    static func date(_ date: Date, task: MobileTask, locale: Locale) -> String {
        date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened, locale: locale,
                                      timeZone: TimeZone(identifier: task.timeZone) ?? .current))
    }

    static func weekday(_ isoDay: Int, locale: Locale) -> String {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = locale
        let index = isoDay == 7 ? 0 : isoDay
        let symbols = calendar.weekdaySymbols
        return symbols.indices.contains(index) ? symbols[index] : ""
    }
}
