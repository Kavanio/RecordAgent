import XCTest
@testable import RecordAgent

final class MobileTaskTests: XCTestCase {
    func testWeeklyScheduleMapsISOWeekdayAndKeepsTimeZone() throws {
        var task = MobileTask.draft()
        task.recurrence = .weekly
        task.weekday = 7
        task.timeZone = "America/Los_Angeles"
        let sunday = try MobileTaskSchedule.components(for: task)
        XCTAssertEqual(sunday.weekday, 1)
        XCTAssertEqual(sunday.timeZone?.identifier, "America/Los_Angeles")
        task.weekday = 1
        XCTAssertEqual(try MobileTaskSchedule.components(for: task).weekday, 2)
    }

    func testNaturalDateRejectsNormalizedInvalidDayAndPastOnce() throws {
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        XCTAssertNil(MobileTaskSchedule.date("2026-02-30", in: zone))
        XCTAssertNotNil(MobileTaskSchedule.date("2024-02-29", in: zone))
        var task = MobileTask.draft(timeZone: zone)
        task.onceDate = "2026-01-01"
        task.hour = 9
        task.minute = 0
        let nextDay = try XCTUnwrap(MobileTaskSchedule.date("2026-01-02", in: zone))
        XCTAssertNil(MobileTaskSchedule.nextDate(for: task, after: nextDay))
    }

    func testMonthlyDateCannotDisappearInShortMonths() throws {
        var task = MobileTask.draft()
        task.recurrence = .monthly
        task.dayOfMonth = 28
        XCTAssertEqual(try MobileTaskSchedule.components(for: task).day, 28)
        task.dayOfMonth = 29
        XCTAssertThrowsError(try MobileTaskSchedule.components(for: task))
    }

    @MainActor
    func testSilentSynchronizationNeverRequestsPermissionOrInventsDelivery() async {
        let service = MobileTaskTestService()
        let notifications = MobileTaskTestNotifications(permission: .denied)
        let model = MobileTaskViewModel(service: service, notifications: notifications, accountID: "server|account")
        await model.synchronizeNotifications()
        let requested = await notifications.permissionRequests
        let observations = await service.observationCount
        XCTAssertEqual(requested, 0)
        XCTAssertEqual(observations, 0)
        XCTAssertEqual(model.permission, .denied)
    }

    @MainActor
    func testSavedConfigurationRemainsSeparateFromDeniedDeviceRegistration() async throws {
        let service = MobileTaskTestService()
        let notifications = MobileTaskTestNotifications(permission: .denied)
        let model = MobileTaskViewModel(service: service, notifications: notifications, accountID: "server|account")
        var task = MobileTask.draft()
        task.title = "Actual reminder configuration"
        task.recurrence = .daily
        try await model.save(task, creating: true)
        let savedCount = await service.savedCount
        let observedCount = await service.observationCount
        XCTAssertEqual(savedCount, 1)
        XCTAssertEqual(observedCount, 0)
        XCTAssertEqual(model.registration(for: task), .permissionDenied)
    }

    @MainActor
    func testLogoutDiscardsLateConfigurationWithoutReregistering() async {
        let began = expectation(description: "server configuration read began")
        let service = MobileTaskTestService(delayEnabled: true, began: { began.fulfill() })
        let notifications = MobileTaskTestNotifications(permission: .authorized)
        let model = MobileTaskViewModel(service: service, notifications: notifications, accountID: "server|account")
        let operation = Task { await model.synchronizeNotifications() }
        await fulfillment(of: [began], timeout: 1)
        await model.deactivate()
        await service.finishEnabled()
        await operation.value
        let registrations = await notifications.reconciliations
        XCTAssertEqual(registrations, 0)
        XCTAssertTrue(model.tasks.isEmpty)
    }
}

private actor MobileTaskTestService: MobileTaskServiceProtocol {
    var savedCount = 0
    var observationCount = 0
    private var saved: MobileTask?
    private let delayEnabled: Bool
    private let began: (@Sendable () -> Void)?
    private var continuation: CheckedContinuation<[MobileTask], Error>?
    init(delayEnabled: Bool = false, began: (@Sendable () -> Void)? = nil) {
        self.delayEnabled = delayEnabled
        self.began = began
    }
    func list(query: String, enabled: Bool?, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTask> {
        MobileTaskPage(items: saved.map { [$0] } ?? [], nextOffset: nil, hasMore: false)
    }
    func task(id: String) async throws -> MobileTask { saved ?? MobileTask.draft() }
    func enabledTasks() async throws -> [MobileTask] {
        if delayEnabled {
            return try await withCheckedThrowingContinuation {
                continuation = $0
                began?()
            }
        }
        return saved.map { [$0] } ?? []
    }
    func finishEnabled() { continuation?.resume(returning: []); continuation = nil }
    func save(_ task: MobileTask, creating: Bool) async throws -> MobileTask {
        savedCount += 1
        saved = task
        return task
    }
    func delete(id: String, version: Int64) async throws { saved = nil }
    func deliveries(taskID: String, offset: Int, limit: Int) async throws -> MobileTaskPage<MobileTaskDelivery> {
        MobileTaskPage(items: [], nextOffset: nil, hasMore: false)
    }
    func observe(_ observation: MobileTaskObservation) async throws { observationCount += 1 }
}

private actor MobileTaskTestNotifications: MobileTaskNotificationProtocol {
    var permissionRequests = 0
    var reconciliations = 0
    private let currentPermission: MobileNotificationPermission
    private var token = UUID()
    private var valid = true
    init(permission: MobileNotificationPermission) { currentPermission = permission }
    func activate(accountID: String) async -> UUID { token }
    func permission() async -> MobileNotificationPermission { currentPermission }
    func requestPermission() async throws -> MobileNotificationPermission { permissionRequests += 1; return currentPermission }
    func reconcile(tasks: [MobileTask], accountID: String, sessionToken: UUID) async -> MobileTaskNotificationSnapshot {
        if valid && sessionToken == token { reconciliations += 1 }
        return MobileTaskNotificationSnapshot(permission: currentPermission, registrations: [:])
    }
    func observations(accountID: String, sessionToken: UUID) async -> [MobileTaskObservation] { [] }
    func acknowledge(eventID: String) async {}
    func remove(taskID: String, accountID: String, sessionToken: UUID, includeDelivered: Bool) async {}
    func clearAll() async { valid = false; token = UUID() }
}
