import Foundation
import Combine

@MainActor
final class FinanceWorkspaceViewModel: ObservableObject {
    @Published var month: Date {
        didSet {
            guard month != oldValue else { return }
            if monthKey != WorkspaceFormat.monthKey(oldValue) {
                overviewGeneration += 1
                overview = nil
                overviewError = nil
                loadedOverviewMonth = nil
                isLoadingOverview = true
                invalidateTransactions()
            }
            let previousPeriod = FinanceAnalysisPeriod(anchor: oldValue, range: chartRange)
            if previousPeriod.key != analysisKey { invalidateInsights() }
        }
    }
    @Published var type: FinanceTransactionFilter = .all {
        didSet { if type != oldValue { invalidateTransactions() } }
    }
    @Published var selectedAccountID: String? {
        didSet { if selectedAccountID != oldValue { invalidateTransactions() } }
    }
    @Published var chartRange: FinanceChartRange = .month {
        didSet { if chartRange != oldValue { invalidateInsights() } }
    }
    @Published private(set) var insights: FinanceInsights?
    @Published private(set) var insightsError: String?
    @Published private(set) var isLoadingInsights = false
    private var insightsGeneration = 0
    private var insightsErrorKey: String?
    @Published var query = "" {
        didSet { if query != oldValue { invalidateTransactions() } }
    }
    @Published private(set) var overview: FinanceMonthOverview?
    @Published private(set) var accounts: [FinanceAccount] = []
    @Published private(set) var transactions: [FinanceTransaction] = []
    @Published private(set) var overviewError: String?
    @Published private(set) var accountsError: String?
    @Published private(set) var transactionsError: String?
    @Published private(set) var isLoadingOverview = false
    @Published private(set) var isLoadingAccounts = false
    @Published private(set) var isLoadingTransactions = false
    @Published private(set) var nextAccountOffset: Int?
    @Published private(set) var nextTransactionOffset: Int?
    private let service: any FinanceWorkspaceServiceProtocol
    private var overviewGeneration = 0
    private var accountsGeneration = 0
    private var transactionGeneration = 0
    private var loadedOverviewMonth: String?
    private var loadedTransactionFilter: String?

    init(date: Date, service: any FinanceWorkspaceServiceProtocol) {
        month = date
        self.service = service
    }

    var selectedAccount: FinanceAccount? { accounts.first { $0.id == selectedAccountID } }
    var monthKey: String { WorkspaceFormat.monthKey(month) }
    var filterKey: String { "\(monthKey)|\(type.rawValue)|\(selectedAccountID ?? "")|\(query)" }

    /// 筛选改变时立即撤下旧结果和游标，防止防抖期间用旧页码读取新月份。
    private func invalidateTransactions() {
        transactionGeneration += 1
        transactions = []
        nextTransactionOffset = nil
        loadedTransactionFilter = nil
        transactionsError = nil
        isLoadingTransactions = true
    }

    private func invalidateInsights() {
        insightsGeneration += 1
        insights = nil
        insightsError = nil
        insightsErrorKey = nil
        isLoadingInsights = true
    }

    func loadOverviewIfNeeded() async {
        if loadedOverviewMonth != monthKey || overviewError != nil { await loadOverview() }
    }

    func loadTransactionsIfNeeded() async {
        if loadedTransactionFilter != filterKey || transactionsError != nil { await loadTransactions(reset: true) }
    }

    var analysisPeriod: FinanceAnalysisPeriod { FinanceAnalysisPeriod(anchor: month, range: chartRange) }
    var analysisKey: String { analysisPeriod.key }
    var visibleInsightsError: String? { insightsErrorKey == analysisKey ? insightsError : nil }
    var visibleInsights: FinanceInsights? { insights?.period.key == analysisKey ? insights : nil }

    func loadInsights() async {
        insightsGeneration += 1
        let generation = insightsGeneration
        let period = analysisPeriod
        insights = nil
        insightsError = nil
        insightsErrorKey = nil
        isLoadingInsights = true
        defer { if generation == insightsGeneration { isLoadingInsights = false } }
        do {
            let result = try await service.insights(period: period)
            try Task.checkCancellation()
            guard generation == insightsGeneration, period.key == analysisKey else { return }
            insights = result
        } catch is CancellationError { }
        catch { if generation == insightsGeneration && period.key == analysisKey { insightsError = error.localizedDescription; insightsErrorKey = period.key } }
    }

    func refresh() async {
        async let summary: Void = loadOverview()
        async let accountList: Void = loadAccounts(reset: true)
        async let entries: Void = loadTransactions(reset: true)
        async let analysis: Void = loadInsights()
        _ = await (summary, accountList, entries, analysis)
    }

    func loadOverview() async {
        overviewGeneration += 1
        let generation = overviewGeneration
        let requestedMonth = monthKey
        overview = nil
        loadedOverviewMonth = nil
        overviewError = nil
        isLoadingOverview = true
        defer { if generation == overviewGeneration { isLoadingOverview = false } }
        do {
            let result = try await service.overview(month: requestedMonth)
            try Task.checkCancellation()
            guard generation == overviewGeneration, requestedMonth == monthKey else { return }
            overview = result
            loadedOverviewMonth = requestedMonth
        } catch is CancellationError { }
        catch { if generation == overviewGeneration && requestedMonth == monthKey { overviewError = error.localizedDescription } }
    }

    func loadAccounts(reset: Bool) async {
        if !reset && (isLoadingAccounts || nextAccountOffset == nil) { return }
        if reset { accountsGeneration += 1; accounts = []; nextAccountOffset = nil }
        let generation = accountsGeneration
        let offset = reset ? 0 : (nextAccountOffset ?? 0)
        accountsError = nil
        isLoadingAccounts = true
        defer { if generation == accountsGeneration { isLoadingAccounts = false } }
        do {
            let page = try await service.accounts(offset: offset)
            try Task.checkCancellation()
            guard generation == accountsGeneration else { return }
            let existing = Set(accounts.map(\.id))
            accounts.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            nextAccountOffset = page.hasMore ? page.nextOffset : nil
        } catch is CancellationError { }
        catch { if generation == accountsGeneration { accountsError = error.localizedDescription } }
    }

    func loadTransactions(reset: Bool) async {
        if !reset && (isLoadingTransactions || nextTransactionOffset == nil) { return }
        if reset { transactionGeneration += 1; transactions = []; nextTransactionOffset = nil; loadedTransactionFilter = nil }
        let generation = transactionGeneration
        let requestedFilter = filterKey
        let offset = reset ? 0 : (nextTransactionOffset ?? 0)
        transactionsError = nil
        isLoadingTransactions = true
        defer { if generation == transactionGeneration { isLoadingTransactions = false } }
        do {
            let page = try await service.transactions(month: monthKey, type: type,
                accountID: selectedAccountID, query: String(query.prefix(200)), offset: offset)
            try Task.checkCancellation()
            guard generation == transactionGeneration, requestedFilter == filterKey else { return }
            let existing = Set(transactions.map(\.id))
            transactions.append(contentsOf: page.items.filter { !existing.contains($0.id) })
            nextTransactionOffset = page.hasMore ? page.nextOffset : nil
            loadedTransactionFilter = requestedFilter
        } catch is CancellationError { }
        catch { if generation == transactionGeneration && requestedFilter == filterKey { transactionsError = error.localizedDescription } }
    }

    func accountName(for id: String) -> String? { accounts.first { $0.id == id }?.name }

    func accountCreated(_ account: FinanceAccount) {
        if let index = accounts.firstIndex(where: { $0.id == account.id }) { accounts[index] = account }
        else { accounts.insert(account, at: 0) }
    }
}
