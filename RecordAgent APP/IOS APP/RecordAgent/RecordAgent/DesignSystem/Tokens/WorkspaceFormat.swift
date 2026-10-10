import Foundation

/// API 自然日采用公历且与当前日历时区一致，显示格式仍遵循界面语言。
enum WorkspaceFormat {
  static func money(_ cents: Int64, locale: Locale) -> String {
    (Decimal(cents) / 100).formatted(.currency(code: "CNY").locale(locale))
  }

  static func monthKey(_ date: Date) -> String {
    let parts = calendar.dateComponents([.year, .month], from: date)
    return String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0)
  }

  static func dayKey(_ date: Date) -> String {
    let parts = calendar.dateComponents([.year, .month, .day], from: date)
    return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
  }

  static func date(_ day: String) -> Date? {
    let values = day.split(separator: "-").compactMap { Int($0) }
    guard values.count == 3 else { return nil }
    var parts = DateComponents(year: values[0], month: values[1], day: values[2])
    parts.hour = 12
    guard let date = calendar.date(from: parts), dayKey(date) == day else { return nil }
    return date
  }

  private static var calendar: Calendar {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = .current
    return calendar
  }
}
