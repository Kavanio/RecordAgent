import Foundation

enum FinanceChartRange: String, CaseIterable, Identifiable, Sendable {
    case week, month, year
    var id: String { rawValue }
}

/// 周从周一开始，月份与年份沿用工作区选中日期；跨日计算不使用固定秒数。
struct FinanceAnalysisPeriod: Equatable, Sendable {
    let range: FinanceChartRange
    let start: Date
    let end: Date
    let calendar: Calendar

    init(anchor: Date, range: FinanceChartRange, timeZone: TimeZone = .current) {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = timeZone
        calendar.firstWeekday = 2
        calendar.minimumDaysInFirstWeek = 4
        self.calendar = calendar
        self.range = range
        let component: Calendar.Component = range == .week ? .weekOfYear : range == .month ? .month : .year
        let interval = calendar.dateInterval(of: component, for: anchor)
        start = interval?.start ?? calendar.startOfDay(for: anchor)
        end = interval?.end ?? anchor
    }

    var key: String { "\(range.rawValue)|\(dayKey(start))|\(calendar.timeZone.identifier)" }
    var months: [String] {
        var result: [String] = []
        var date = calendar.dateInterval(of: .month, for: start)?.start ?? start
        while date < end {
            let parts = calendar.dateComponents([.year, .month], from: date)
            result.append(String(format: "%04d-%02d", parts.year ?? 0, parts.month ?? 0))
            guard let next = calendar.date(byAdding: .month, value: 1, to: date), next > date else { break }
            date = next
        }
        return result
    }

    var bucketDates: [Date] {
        var dates: [Date] = []
        var date = start
        while date < end {
            dates.append(date)
            guard let next = calendar.date(byAdding: range == .year ? .month : .day, value: 1, to: date), next > date
            else { break }
            date = next
        }
        return dates
    }

    func dayKey(_ date: Date) -> String {
        let parts = calendar.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }

    func date(_ key: String) -> Date? {
        let parts = key.split(separator: "-").compactMap { Int($0) }
        guard parts.count == 3,
            let value = calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2])),
            dayKey(value) == key
        else { return nil }
        return value
    }
}

struct FinanceTrendBucket: Identifiable, Sendable {
    let date: Date
    var expense: Int64 = 0
    var income: Int64 = 0
    var categories: [String: Int64] = [:]
    var id: Date { date }
}

struct FinanceAccountFlow: Identifiable, Sendable {
    let id: String
    let name: String?
    var expense: Int64 = 0
    var income: Int64 = 0
}

struct FinanceRankingEntry: Identifiable, Sendable {
    let id: String
    let category: String
    let note: String
    let date: Date?
    let amount: Int64
}

struct FinanceInsights: Sendable {
    let period: FinanceAnalysisPeriod
    let buckets: [FinanceTrendBucket]
    let accounts: [FinanceAccountFlow]
    let transactions: [FinanceRankingEntry]
    let categories: [FinanceRankingEntry]
    let expense: Int64
    let income: Int64

    /// 聚合先按整数分完成，图形坐标才转浮点；溢出或不合法金额拒绝展示。
    init(period: FinanceAnalysisPeriod, entries: [FinanceTransaction], accounts: [FinanceAccount]) throws {
        self.period = period
        var buckets = period.bucketDates.map { FinanceTrendBucket(date: $0) }
        var flows = accounts.reduce(into: [String: FinanceAccountFlow]()) {
            $0[$1.id] = FinanceAccountFlow(id: $1.id, name: $1.name)
        }
        var expenses: [FinanceRankingEntry] = []
        var categoryAmounts: [String: Int64] = [:]
        var seen: Set<String> = []
        var expense: Int64 = 0
        var income: Int64 = 0
        for entry in entries where seen.insert(entry.id).inserted {
            guard let date = period.date(entry.date), date >= period.start, date < period.end else { continue }
            guard entry.amountInCents >= 0, entry.type == "expense" || entry.type == "income" else {
                throw FinanceAnalysisError.invalidData
            }
            let bucketDate = period.range == .year ? period.calendar.dateInterval(of: .month, for: date)?.start : date
            guard let index = buckets.firstIndex(where: { $0.date == bucketDate }) else { continue }
            var flow = flows[entry.accountId] ?? FinanceAccountFlow(id: entry.accountId, name: nil)
            if entry.type == "expense" {
                expense = try Self.add(expense, entry.amountInCents)
                buckets[index].expense = try Self.add(buckets[index].expense, entry.amountInCents)
                buckets[index].categories[entry.category] = try Self.add(
                    buckets[index].categories[entry.category] ?? 0, entry.amountInCents)
                flow.expense = try Self.add(flow.expense, entry.amountInCents)
                categoryAmounts[entry.category] = try Self.add(
                    categoryAmounts[entry.category] ?? 0, entry.amountInCents)
                expenses.append(
                    FinanceRankingEntry(
                        id: entry.id, category: entry.category, note: entry.note, date: date,
                        amount: entry.amountInCents))
            } else {
                income = try Self.add(income, entry.amountInCents)
                buckets[index].income = try Self.add(buckets[index].income, entry.amountInCents)
                flow.income = try Self.add(flow.income, entry.amountInCents)
            }
            flows[entry.accountId] = flow
        }
        self.buckets = buckets
        self.accounts = flows.values.sorted { $0.id < $1.id }
        transactions = expenses.sorted { $0.amount == $1.amount ? $0.id < $1.id : $0.amount > $1.amount }
        categories = categoryAmounts.map {
            FinanceRankingEntry(id: $0.key, category: $0.key, note: "", date: nil, amount: $0.value)
        }
        .sorted { $0.amount == $1.amount ? $0.id < $1.id : $0.amount > $1.amount }
        self.expense = expense
        self.income = income
    }

    private static func add(_ lhs: Int64, _ rhs: Int64) throws -> Int64 {
        let (sum, overflow) = lhs.addingReportingOverflow(rhs)
        guard !overflow else { throw FinanceAnalysisError.invalidData }
        return sum
    }
}

enum FinanceAnalysisError: LocalizedError {
    case invalidData
    var errorDescription: String? { String(localized: "api.error.response") }
}
