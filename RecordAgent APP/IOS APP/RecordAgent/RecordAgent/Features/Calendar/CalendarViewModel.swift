import Combine
import Foundation

/// 日历的月摘要与便签独立加载；当天正文继续使用主容器持有的 HomeViewModel。
@MainActor
final class CalendarViewModel: ObservableObject {
    @Published private(set) var month: CalendarMonthSnapshot?
    @Published private(set) var groups: [CalendarTodoGroup] = []
    @Published private(set) var monthFailed = false
    @Published private(set) var notesFailed = false
    @Published private(set) var isLoading = false
    @Published private(set) var savingIDs: Set<String> = []
    @Published private(set) var isCreating = false
    @Published var showWriteError = false
    @Published var writeErrorMessage: String?

    private let service: any CalendarDataServiceProtocol
    private var revision = 0
    private var requestedDate: Date?
    private var requestedLocale: String?

    init(service: any CalendarDataServiceProtocol) { self.service = service }

    func load(for date: Date, locale: Locale) async {
        revision += 1
        let request = revision
        if requestedDate != date || requestedLocale != locale.identifier {
            month = nil
            groups = []
        }
        requestedDate = date
        requestedLocale = locale.identifier
        monthFailed = false
        notesFailed = false
        isLoading = true
        async let monthResult: Void = loadMonth(date, locale: locale, request: request)
        async let notesResult: Void = loadNotes(date, locale: locale, request: request)
        _ = await (monthResult, notesResult)
        if request == revision { isLoading = false }
    }

    func toggle(_ entry: HomeEntry) async -> Bool {
        guard !savingIDs.contains(entry.id) else { return false }
        savingIDs.insert(entry.id)
        defer { savingIDs.remove(entry.id) }
        do {
            try await service.setTodoCompleted(id: entry.id, completed: !entry.isCompleted, date: entry.date)
            // 只更新仍包含原实例的便签，切换周期后不能污染新实例。
            for groupIndex in groups.indices {
                let group = groups[groupIndex]
                guard let index = group.entries.firstIndex(where: { $0.id == entry.id }) else { continue }
                var entries = group.entries
                entries[index].isCompleted = !entry.isCompleted
                groups[groupIndex] = CalendarTodoGroup(period: group.period, entries: entries)
            }
            return true
        } catch {
            if !(error is CancellationError) {
                writeErrorMessage = error.localizedDescription
                showWriteError = true
            }
            return false
        }
    }

    func create(_ draft: CalendarEntryDraft) async -> Bool {
        guard draft.isValid, !isCreating else { return false }
        isCreating = true
        defer { isCreating = false }
        do {
            try await service.createEntry(draft)
            return true
        } catch {
            if !(error is CancellationError) {
                writeErrorMessage = error.localizedDescription
                showWriteError = true
            }
            return false
        }
    }

    private func loadMonth(_ date: Date, locale: Locale, request: Int) async {
        do {
            let result = try await service.month(for: date, locale: locale)
            guard request == revision, !Task.isCancelled else { return }
            month = result
        } catch {
            guard request == revision, !(error is CancellationError) else { return }
            monthFailed = true
        }
    }

    private func loadNotes(_ date: Date, locale: Locale, request: Int) async {
        do {
            let result = try await service.periodicTodos(for: date, locale: locale)
            guard request == revision, !Task.isCancelled else { return }
            groups = result
        } catch {
            guard request == revision, !(error is CancellationError) else { return }
            notesFailed = true
        }
    }
}
