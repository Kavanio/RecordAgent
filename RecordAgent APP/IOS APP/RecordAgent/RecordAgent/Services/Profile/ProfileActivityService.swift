import Foundation

/// 连续记录打卡单日模型
struct StreakDay: Equatable, Sendable, Identifiable {
    let date: Date
    let weekdaySymbol: String
    let isCompleted: Bool
    let entryCount: Int
    let isToday: Bool
    var id: Date { date }
}

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

    /// 连续记录天数
    var streakDays: Int {
        if isDemo { return 12 }
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        var current = today
        var streak = 0

        let todayHasRecord = days.contains {
            calendar.isDate($0.date, inSameDayAs: today) && !$0.entries.isEmpty
        }
        if !todayHasRecord {
            guard let yesterday = calendar.date(byAdding: .day, value: -1, to: today) else { return 0 }
            current = yesterday
        }

        while true {
            let target = current
            let hasRecord = days.contains {
                calendar.isDate($0.date, inSameDayAs: target) && !$0.entries.isEmpty
            }
            if hasRecord {
                streak += 1
                guard let previous = calendar.date(byAdding: .day, value: -1, to: current) else { break }
                current = previous
            } else {
                break
            }
        }
        return streak
    }

    /// 最近天数的打卡数据（默认 30 天，支持横向平滑滚动查看）
    func recentStreakDays(count: Int = 30, calendar: Calendar = .current) -> [StreakDay] {
        let today = calendar.startOfDay(for: .now)
        let weekdaySymbols = ["日", "一", "二", "三", "四", "五", "六"]
        var result: [StreakDay] = []
        for offset in (0..<count).reversed() {
            guard let date = calendar.date(byAdding: .day, value: -offset, to: today) else { continue }
            let dayEntries =
                days.first(where: { calendar.isDate($0.date, inSameDayAs: date) })?.entries ?? []
            let weekdayIndex = calendar.component(.weekday, from: date) - 1
            let symbol =
                (weekdayIndex >= 0 && weekdayIndex < weekdaySymbols.count)
                ? weekdaySymbols[weekdayIndex] : ""

            let isCompleted: Bool
            if isDemo {
                let demoPattern15 = [
                    true, true, true, true, true, true,
                    false, false, false,
                    true, true, true,
                    false, false, false
                ]
                if count == 15 {
                    let index = 14 - offset
                    isCompleted = index < demoPattern15.count ? demoPattern15[index] : (!dayEntries.isEmpty)
                } else {
                    if offset < 12 {
                        isCompleted = true
                    } else if offset == 12 || offset == 13 {
                        isCompleted = false
                    } else {
                        isCompleted = (offset % 3 != 0)
                    }
                }
            } else {
                isCompleted = !dayEntries.isEmpty
            }

            result.append(
                StreakDay(
                    date: date,
                    weekdaySymbol: symbol,
                    isCompleted: isCompleted,
                    entryCount: dayEntries.count,
                    isToday: offset == 0
                )
            )
        }
        return result
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
        let today = calendar.startOfDay(for: .now)
        let fifteenDaysAgo = calendar.date(byAdding: .day, value: -14, to: today) ?? interval.start
        let queryStart = min(interval.start, fifteenDaysAgo)
        let queryEnd = max(min(today, interval.end.addingTimeInterval(-1)), today)

        guard queryEnd >= queryStart else {
            return ProfileActivityMonth(days: [], isDemo: false)
        }
        let count = calendar.dateComponents([.day], from: queryStart, to: queryEnd).day ?? 0
        var days: [ProfileActivityMonth.Day] = []
        var demo = false
        for offset in 0...min(count, 45) {
            try Task.checkCancellation()
            guard let date = calendar.date(byAdding: .day, value: offset, to: queryStart) else {
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
