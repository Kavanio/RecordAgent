import Combine
import Foundation

@MainActor
final class HomeViewModel: ObservableObject {
    @Published private(set) var selectedDate: Date
    @Published private(set) var weekStarts: [Date]
    @Published private(set) var snapshot: HomeDaySnapshot?
    @Published private(set) var isLoading = false
    @Published private(set) var loadFailed = false
    @Published private(set) var savingTodoIDs: Set<String> = []
    @Published var showWriteError = false
    @Published var showSearchAlert = false
    @Published var showNotificationAlert = false
    @Published var category: HomeCategory?
    @Published private(set) var requestedEntryID: String?

    let dates: HomeCalendar
    private let service: any HomeDataServiceProtocol
    private var loadRevision = 0

    init(service: any HomeDataServiceProtocol, now: Date = .now, calendar: Calendar = .current) {
        self.service = service
        self.dates = HomeCalendar(calendar: calendar)
        self.selectedDate = calendar.startOfDay(for: now)
        self.weekStarts = dates.weeks(around: now)
    }

    var visibleEntries: [HomeEntry] { entries(in: category) }

    func openFromProfile(_ entry: HomeEntry) {
        selectDate(entry.date)
        category = entry.kind.category
        requestedEntryID = entry.id
    }

    func consumeRequestedEntry() { requestedEntryID = nil }
    func entries(in category: HomeCategory?) -> [HomeEntry] {
        (snapshot?.entries ?? []).filter { category == nil || $0.kind.category == category }
    }

    func selectDate(_ date: Date) {
        let normalized = dates.calendar.startOfDay(for: date)
        guard dates.supportedDates.contains(normalized), normalized != selectedDate else { return }
        selectedDate = normalized
        loadRevision += 1
        snapshot = nil
        loadFailed = false
        isLoading = true
        let week = dates.startOfWeek(containing: normalized)
        if !weekStarts.contains(week) {
            weekStarts = dates.weeks(around: normalized)
        } else if week == weekStarts.first {
            weekStarts.insert(
                contentsOf: (-26..<0).map { dates.addingDays($0 * 7, to: week) }.filter(
                    dates.isSupportedWeek), at: 0)
        } else if week == weekStarts.last {
            weekStarts.append(
                contentsOf: (1...26).map { dates.addingDays($0 * 7, to: week) }.filter(
                    dates.isSupportedWeek))
        }
    }

    func selectWeek(_ week: Date) {
        let currentWeek = dates.startOfWeek(containing: selectedDate)
        guard currentWeek != week else { return }
        let offset =
            dates.calendar.dateComponents([.day], from: currentWeek, to: selectedDate).day ?? 0
        let candidate = dates.addingDays(offset, to: week)
        selectDate(
            min(max(candidate, dates.supportedDates.lowerBound), dates.supportedDates.upperBound))
    }

    func selectMonth(year: Int, month: Int) {
        guard let date = dates.date(inYear: year, month: month, preservingDayOf: selectedDate)
        else { return }
        selectDate(date)
    }

    /// revision 与日期双重检查，防止快速滑动时旧请求覆盖当前日期。
    func load(locale: Locale) async {
        loadRevision += 1
        let revision = loadRevision
        let date = selectedDate
        isLoading = true
        loadFailed = false
        do {
            let result = try await service.snapshot(for: date, locale: locale)
            guard !Task.isCancelled, revision == loadRevision, date == selectedDate else { return }
            snapshot = result
            isLoading = false
        } catch {
            guard revision == loadRevision else { return }
            isLoading = false
            if !(error is CancellationError) { loadFailed = true }
        }
    }

    /// 服务确认后更新当前快照；离开日期后不把旧日数据写入新日。
    func toggleTodo(_ entry: HomeEntry) async {
        guard entry.kind == .todo, !savingTodoIDs.contains(entry.id),
            let current = snapshot?.entries.first(where: { $0.id == entry.id })
        else { return }
        let date = selectedDate
        savingTodoIDs.insert(entry.id)
        defer { savingTodoIDs.remove(entry.id) }
        do {
            try await service.setTodoCompleted(
                id: entry.id, completed: !current.isCompleted, date: date)
            guard selectedDate == date,
                let index = snapshot?.entries.firstIndex(where: { $0.id == entry.id })
            else { return }
            snapshot?.entries[index].isCompleted = !current.isCompleted
        } catch {
            if !(error is CancellationError) { showWriteError = true }
        }
    }
}
