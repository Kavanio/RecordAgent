import Foundation

/// 月内活动与原始按日条目共享事实来源，条目 ID 可回到首页详情。
struct ProfileActivityMonth: Equatable, Sendable {
    struct Day: Equatable, Sendable, Identifiable {
        let date: Date
        let entries: [HomeEntry]
        var id: Date { date }
    }
    let days: [Day]
    let isDemo: Bool

    var latest: [HomeEntry] {
        days.flatMap(\.entries).sorted { $0.date > $1.date }
    }
}

protocol ProfileActivityServiceProtocol: Sendable {
    func month(for account: String, startingAt month: Date, locale: Locale) async throws
        -> ProfileActivityMonth
}

/// 当前阶段复用首页按日服务；未来月聚合 API 接通后替换此适配器。
struct HomeProfileActivityService: ProfileActivityServiceProtocol {
    let homeService: any HomeDataServiceProtocol

    func month(for account: String, startingAt month: Date, locale: Locale) async throws
        -> ProfileActivityMonth
    {
        let calendar = Calendar.current
        guard let interval = calendar.dateInterval(of: .month, for: month) else {
            return ProfileActivityMonth(days: [], isDemo: false)
        }
        let lastDay = min(Date.now, interval.end.addingTimeInterval(-1))
        guard lastDay >= interval.start else {
            return ProfileActivityMonth(days: [], isDemo: false)
        }
        let count =
            calendar.dateComponents(
                [.day], from: interval.start, to: calendar.startOfDay(for: lastDay)
            ).day ?? 0
        var days: [ProfileActivityMonth.Day] = []
        var demo = false
        for offset in 0...min(count, 30) {
            try Task.checkCancellation()
            guard let date = calendar.date(byAdding: .day, value: offset, to: interval.start) else {
                continue
            }
            let snapshot = try await homeService.snapshot(for: date, locale: locale)
            demo = demo || snapshot.isDemo
            let entries = snapshot.entries.filter {
                $0.kind == .document || $0.kind == .focus || ($0.kind == .todo && $0.isCompleted)
            }
            days.append(.init(date: date, entries: entries))
        }
        return ProfileActivityMonth(days: days, isDemo: demo)
    }
}
