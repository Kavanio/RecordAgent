import Foundation

enum RecordFormatting {
    static func duration(_ seconds: TimeInterval, locale: Locale, clock: Bool = false) -> String {
        let formatter = DateComponentsFormatter()
        var calendar = Calendar.current
        calendar.locale = locale
        formatter.calendar = calendar
        formatter.allowedUnits = clock ? [.hour, .minute, .second] : [.hour, .minute]
        formatter.unitsStyle = clock ? .positional : .abbreviated
        formatter.zeroFormattingBehavior = clock ? .pad : .dropLeading
        return formatter.string(from: max(0, seconds)) ?? Int(max(0, seconds) / 60).formatted(.number.locale(locale))
    }

    static func message(_ key: String, locale: Locale) -> String {
        AppLocalization.string(String.LocalizationValue(key), locale: locale)
    }
}
