import XCTest
@testable import RecordAgent

final class FinanceLibraryWorkspaceTests: XCTestCase {
    func testAmountConversionHasNoFloatingPointRoundingAndRejectsOverflow() {
        XCTAssertEqual(FinanceAmountInput.cents("0.29"), 29)
        XCTAssertEqual(FinanceAmountInput.cents("12,34"), 1234)
        XCTAssertEqual(FinanceAmountInput.cents(".50"), 50)
        XCTAssertEqual(FinanceAmountInput.cents("0", allowsZero: true), 0)
        XCTAssertNil(FinanceAmountInput.cents("0"))
        XCTAssertNil(FinanceAmountInput.cents("-1"))
        XCTAssertNil(FinanceAmountInput.cents("1.234"))
        XCTAssertNil(FinanceAmountInput.cents("92233720368547758.08"))
        XCTAssertEqual(FinanceAmountInput.cents("92233720368547758.07"), Int64.max)
    }

    func testTransactionDTOSeparatesNaturalDateTimeAndIntegerMoney() throws {
        let data = Data("""
        {"items":[{"id":"tx","accountId":"account","type":"expense","category":null,"note":"","amountInCents":29,"date":"2024-02-29","time":"09:45:30","balanceInCents":9007199254740993}],"nextOffset":50,"hasMore":true}
        """.utf8)
        let page = try APIClient.decoder.decode(FinanceWorkspacePage<FinanceTransaction>.self, from: data)
        let item = try XCTUnwrap(page.items.first)
        XCTAssertEqual(item.amountInCents, 29)
        XCTAssertEqual(item.category, "")
        XCTAssertEqual(item.balanceInCents, 9007199254740993)
        XCTAssertEqual(item.date, "2024-02-29")
        XCTAssertEqual(item.time, "09:45:30")
        XCTAssertEqual(page.nextOffset, 50)
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let time = try XCTUnwrap(FinanceTransactionTime.date(item.time, calendar: calendar))
        XCTAssertEqual(calendar.component(.hour, from: time), 9)
        XCTAssertEqual(calendar.component(.minute, from: time), 45)
        XCTAssertNil(FinanceTransactionTime.date("24:00:00"))
        XCTAssertNil(FinanceTransactionTime.date("09:45:99"))
    }

    func testLibraryDraftEncodesStableIdentityVersionAndFullBody() throws {
        let document = try APIClient.decoder.decode(LibraryDocument.self, from: Data("""
        {"id":"c75231b1-c18d-4f94-aa22-2b1885b55b26","date":"2026-09-30","type":"journal","title":"Today","body":"First line\\nSecond line","favorite":true,"trashed":false,"version":7,"createdAt":"2026-09-30T09:27:35.123Z","updatedAt":"2026-09-30T10:30:00Z"}
        """.utf8))
        let draft = LibraryDraft(document: document, timeZone: "Asia/Shanghai")
        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: APIClient.encoder.encode(draft)) as? [String: Any])
        XCTAssertEqual(payload["id"] as? String, document.id)
        XCTAssertEqual(payload["version"] as? Int, 7)
        XCTAssertEqual(payload["body"] as? String, "First line\nSecond line")
        XCTAssertEqual(payload["timeZone"] as? String, "Asia/Shanghai")
        XCTAssertNotNil(document.createdAt)
        XCTAssertNotNil(document.updatedAt)
        XCTAssertNotEqual(document.createdAt, document.updatedAt)
        XCTAssertNil(payload["createdAt"])
        XCTAssertNil(payload["updatedAt"])
        var tooLong = draft
        tooLong.title = String(repeating: "😀", count: 121)
        XCTAssertFalse(tooLong.isValid)
    }

    @MainActor
    func testVersionConflictAndComparisonKeepDraftUntilExplicitChoice() async throws {
        let latest = LibraryDocument(id: "old", date: "2026-09-30", type: .quick,
            title: "Server", body: "New server text", favorite: false, trashed: false, version: 8)
        let draft = LibraryDraft(id: "old", date: "2026-09-30", title: "Draft", body: "My unsaved text", version: 7)
        let model = LibraryEditorViewModel(draft: draft, creating: false, service: ConflictingLibraryService(latest: latest))
        do { _ = try await model.save(); XCTFail("A stale version must fail") }
        catch APIClientError.conflict { }
        await model.loadLatest()
        XCTAssertEqual(model.draft, draft)
        XCTAssertEqual(model.latest?.version, 8)
        XCTAssertTrue(model.hasConflict)
        model.keepDraftAsNew()
        XCTAssertNotEqual(model.draft.id, draft.id)
        XCTAssertEqual(model.draft.version, 0)
        XCTAssertEqual(model.draft.body, draft.body)
        XCTAssertTrue(model.creating)
        XCTAssertFalse(model.hasConflict)
    }
}

private struct ConflictingLibraryService: LibraryServiceProtocol {
    let latest: LibraryDocument
    func page(type: LibraryDocumentKind?, collection: LibraryCollection, query: String, offset: Int) async throws -> LibraryPage {
        LibraryPage(items: [], nextOffset: nil, hasMore: false)
    }
    func document(id: String) async throws -> LibraryDocument { latest }
    func save(_ draft: LibraryDraft, creating: Bool) async throws -> LibraryDocument { throw APIClientError.conflict }
    func flags(id: String, version: Int64, favorite: Bool, trashed: Bool) async throws -> LibraryDocument { throw APIClientError.conflict }
}
