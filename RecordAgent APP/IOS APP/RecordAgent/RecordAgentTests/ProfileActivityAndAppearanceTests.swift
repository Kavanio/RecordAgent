import Foundation
import XCTest

@testable import RecordAgent

final class ProfileActivityAndAppearanceTests: XCTestCase {
    func testAppearanceIsIsolatedByAccountAndSupportsImageRemoval() async throws {
        let root = FileManager.default.temporaryDirectory.appendingPathComponent(
            UUID().uuidString, isDirectory: true)
        addTeardownBlock { try FileManager.default.removeItem(at: root) }
        let service = LocalProfileAppearanceService(root: root)
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0xD9])
        try await service.save(
            ProfileAppearance(nickname: "小林", avatarData: jpeg, coverData: jpeg),
            account: "first@example.com")

        let first = try await service.load(account: "FIRST@example.com")
        let second = try await service.load(account: "second@example.com")
        XCTAssertEqual(first.nickname, "小林")
        XCTAssertEqual(first.avatarData, jpeg)
        XCTAssertEqual(second, .empty)

        try await service.save(ProfileAppearance(nickname: "新昵称"), account: "first@example.com")
        let updated = try await service.load(account: "first@example.com")
        XCTAssertEqual(updated.nickname, "新昵称")
        XCTAssertNil(updated.avatarData)
        XCTAssertNil(updated.coverData)
    }

    func testActivityUsesHomeEntriesAndExcludesExpensesAndPendingTodos() async throws {
        let service = MockHomeDataService()
        let monthStart = Calendar.current.dateInterval(of: .month, for: .now)!.start
        let activity = try await HomeProfileActivityService(homeService: service).month(
            for: "sample@example.com", startingAt: monthStart, locale: Locale(identifier: "zh-Hans")
        )
        XCTAssertTrue(activity.isDemo)
        XCTAssertFalse(activity.days.isEmpty)
        XCTAssertTrue(
            activity.latest.allSatisfy {
                $0.kind == .document || $0.kind == .focus || ($0.kind == .todo && $0.isCompleted)
            })
        let today = try await service.snapshot(for: .now, locale: Locale(identifier: "zh-Hans"))
        XCTAssertTrue(
            Set(activity.latest.map(\.id)).isSuperset(
                of: today.entries.filter { $0.kind == .document }.map(\.id)))
    }

    @MainActor
    func testActivityEntryRoutesToItsOriginalDayAndCategory() async throws {
        let service = MockHomeDataService()
        let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: .now)!
        let snapshot = try await service.snapshot(for: yesterday, locale: .current)
        let entry = try XCTUnwrap(snapshot.entries.first { $0.kind == .document })
        let model = HomeViewModel(service: service)
        model.openFromProfile(entry)
        XCTAssertTrue(Calendar.current.isDate(model.selectedDate, inSameDayAs: entry.date))
        XCTAssertEqual(model.category, .library)
        XCTAssertEqual(model.requestedEntryID, entry.id)
        await model.load(locale: .current)
        XCTAssertTrue(model.snapshot?.entries.contains(where: { $0.id == entry.id }) == true)
        model.consumeRequestedEntry()
        XCTAssertNil(model.requestedEntryID)
    }

    func testContinuousRecordStreakDaysAndRecentDaysStrip() {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)

        // 验证 Demo 模式下的 15 天刻度与 12 天连续记录
        let demoMonth = ProfileActivityMonth(days: [], isDemo: true)
        XCTAssertEqual(demoMonth.streakDays, 12)
        let demoDays = demoMonth.recentStreakDays(calendar: calendar)
        XCTAssertEqual(demoDays.count, 15)
        XCTAssertEqual(demoDays.last?.isToday, true)

        // 验证非 Demo 模式下真实的连续打卡天数计算
        let entry = HomeEntry(
            id: "e-1",
            kind: .document,
            title: "学习笔记",
            detail: "正文内容",
            date: today,
            isCompleted: true
        )
        let day0 = ProfileActivityMonth.Day(date: today, entries: [entry])
        guard let yesterday = calendar.date(byAdding: .day, value: -1, to: today) else {
            XCTFail("无法计算昨天日期")
            return
        }
        let day1 = ProfileActivityMonth.Day(date: yesterday, entries: [entry])

        let activeMonth = ProfileActivityMonth(days: [day0, day1], isDemo: false)
        XCTAssertEqual(activeMonth.streakDays, 2)

        let realDays = activeMonth.recentStreakDays(calendar: calendar)
        XCTAssertEqual(realDays.count, 15)
        XCTAssertEqual(realDays.last?.isCompleted, true)
    }
}
