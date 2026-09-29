import SwiftUI

/// 日历独立编排月格、周期便签与按时序排列的日记录；日期与首页保持一致。
struct CalendarView: View {
    @ObservedObject var home: HomeViewModel
    @StateObject private var model: CalendarViewModel
    @StateObject private var noteMotion = CalendarNoteMotion()
    @State private var showsMonthPicker = false
    @State private var showsCreate = false
    @State private var createPath: [CalendarEntryDraft.Kind] = []
    @State private var opensFinanceAfterCreate = false
    @State private var selectedEntry: HomeEntry?
    @State private var selectedNotePeriod: CalendarTodoPeriod?
    @State private var pendingTodoIDs: Set<String> = []
    @State private var monthDirection = 0
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    let onOpenFinance: () -> Void

    init(
        home: HomeViewModel, service: any CalendarDataServiceProtocol,
        onOpenFinance: @escaping () -> Void
    ) {
        self.home = home
        self.onOpenFinance = onOpenFinance
        _model = StateObject(wrappedValue: CalendarViewModel(service: service))
    }

    private struct LoadKey: Equatable {
        let date: Date
        let locale: String
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                header
                monthNavigation
                CalendarMonthGrid(
                    selectedDate: home.selectedDate, dates: home.dates,
                    markers: model.month?.days ?? [], onSelect: select, onShiftMonth: shiftMonth
                )
                .id(home.dates.startOfMonth(containing: home.selectedDate))
                .transition(.opacity.combined(with: .offset(x: CGFloat(monthDirection) * 28)))
                legend
                if model.monthFailed { retryRow("calendar.month.failed") }
                if model.notesFailed { retryRow("calendar.notes.failed") }
                if !model.groups.isEmpty {
                    let columns = Array(
                        repeating: GridItem(.flexible(), spacing: 14),
                        count: typeSize.isAccessibilitySize ? 1 : 2)
                    LazyVGrid(columns: columns, alignment: .leading, spacing: 20) {
                        ForEach(model.groups) { group in
                            CalendarStickyTodoNote(
                                group: group, onOpen: { showNote(group.period) }, motion: noteMotion)
                        }
                    }
                    .padding(.top, 8)
                }
                dayContent
                if home.snapshot?.isDemo == true {
                    Text("home.demo.footer").font(.caption2).foregroundStyle(CalendarPalette.secondary)
                        .frame(maxWidth: .infinity).padding(.top, 4)
                }
            }
            .padding(.horizontal, 22)
            .padding(.top, 12)
            .padding(.bottom, 24)
        }
        .background(Color(uiColor: .systemBackground).ignoresSafeArea())
        .tint(CalendarPalette.accent)
        .onAppear(perform: updateNoteMotion)
        .onDisappear { noteMotion.stop() }
        .onChange(of: scenePhase) { _, _ in updateNoteMotion() }
        .onChange(of: reduceMotion) { _, _ in updateNoteMotion() }
        .onChange(of: model.groups.count) { _, _ in updateNoteMotion() }
        .onChange(of: selectedNotePeriod) { _, _ in updateNoteMotion() }
        .task(id: LoadKey(date: home.selectedDate, locale: locale.identifier)) {
            await model.load(for: home.selectedDate, locale: locale)
        }
        .refreshable { await refresh() }
        .accessibilityHidden(selectedNotePeriod != nil)
        .overlay {
            if let period = selectedNotePeriod {
                CalendarStickyTodoDetail(
                    period: period, model: model, savingIDs: pendingTodoIDs,
                    onToggle: toggle, onClose: closeNote
                )
                .transition(.opacity.combined(with: .scale(scale: 0.94)))
                .zIndex(1)
            }
        }
        .sheet(isPresented: $showsMonthPicker) {
            HomeMonthPickerView(selectedDate: home.selectedDate, dates: home.dates) { year, month in
                monthDirection = 0
                home.selectMonth(year: year, month: month)
                showsMonthPicker = false
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(
            isPresented: $showsCreate,
            onDismiss: {
                createPath = []
                if opensFinanceAfterCreate {
                    opensFinanceAfterCreate = false
                    onOpenFinance()
                }
            }
        ) {
            NavigationStack(path: $createPath) {
                CalendarCreateChooserView(
                    onSelect: { createPath.append($0) },
                    onSelectFinance: {
                        opensFinanceAfterCreate = true
                        showsCreate = false
                    }
                )
                .navigationDestination(for: CalendarEntryDraft.Kind.self) { kind in
                    CalendarCreateEntryView(
                        model: model, kind: kind, date: home.selectedDate, dates: home.dates,
                        isDemo: home.snapshot?.isDemo == true
                    ) { draft in
                        let date =
                            draft.kind == .todo && draft.period != nil
                            ? home.dates.periodEnd(draft.period ?? .weekly, containing: draft.date) : draft.date
                        home.selectDate(date)
                        await refresh()
                        showsCreate = false
                    }
                }
            }
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
        }
        .sheet(
            item: $selectedEntry,
            onDismiss: {
                Task { await model.load(for: home.selectedDate, locale: locale) }
            }
        ) { entry in
            HomeEntryDetailView(
                entry: entry, viewModel: home, savingIDs: pendingTodoIDs, onToggle: toggle
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .alert(
            "home.write.failed",
            isPresented: Binding(
                get: {
                    !showsCreate && selectedEntry == nil && (model.showWriteError || home.showWriteError)
                },
                set: {
                    if !$0 {
                        model.showWriteError = false
                        home.showWriteError = false
                    }
                }
            )
        ) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.write.failed_detail")
        }
    }

    private func updateNoteMotion() {
        if scenePhase == .active && !reduceMotion && !model.groups.isEmpty && selectedNotePeriod == nil
        {
            noteMotion.start()
        } else {
            noteMotion.stop()
        }
    }

    private func showNote(_ period: CalendarTodoPeriod) {
        withAnimation(reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.82)) {
            selectedNotePeriod = period
        }
    }

    private func closeNote() {
        withAnimation(reduceMotion ? nil : .easeOut(duration: 0.18)) {
            selectedNotePeriod = nil
        }
    }

    private var header: some View {
        HStack {
            Text("tab.calendar").font(.largeTitle.weight(.medium)).foregroundStyle(CalendarPalette.ink)
            Spacer()
            if model.isLoading { ProgressView().controlSize(.small).accessibilityLabel("home.loading") }
            Button {
                showsCreate = true
            } label: {
                Image(systemName: "plus").font(.system(size: 25, weight: .light)).frame(
                    width: 44, height: 44)
            }
            .buttonStyle(.plain)
            .foregroundStyle(CalendarPalette.accent)
            .accessibilityLabel("calendar.create")
        }
    }

    private var monthNavigation: some View {
        HStack(spacing: 4) {
            Color.clear.frame(width: 44, height: 44).accessibilityHidden(true)
            Spacer(minLength: 0)
            Button {
                showsMonthPicker = true
            } label: {
                HStack(spacing: 6) {
                    Text(home.selectedDate, format: .dateTime.year().month(.wide))
                        .font(.headline).lineLimit(1).minimumScaleFactor(0.75)
                    Image(systemName: "chevron.down").font(.caption)
                }
                .frame(minHeight: 44)
            }
            .accessibilityHint("home.month.choose")
            Spacer(minLength: 0)
            Button("home.today") { select(.now) }
                .font(.subheadline).foregroundStyle(CalendarPalette.accent)
                .frame(minWidth: 44, minHeight: 44)
        }
        .buttonStyle(.plain)
        .foregroundStyle(CalendarPalette.ink)
    }

    private var legend: some View {
        let kinds: [HomeEntry.Kind] = [.todo, .schedule, .focus, .document, .expense]
        return ViewThatFits(in: .horizontal) {
            HStack(spacing: 13) { ForEach(kinds, id: \.self) { legendItem($0) } }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 76))], alignment: .leading, spacing: 8) {
                ForEach(kinds, id: \.self) { legendItem($0) }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func legendItem(_ kind: HomeEntry.Kind) -> some View {
        HStack(spacing: 5) {
            Circle().fill(kind.calendarColor).frame(width: 8, height: 8)
            Text(kind.title).font(.caption).foregroundStyle(CalendarPalette.secondary).fixedSize()
        }
    }

    @ViewBuilder private var dayContent: some View {
        if home.loadFailed {
            ContentUnavailableView {
                Label("home.load.failed", systemImage: "wifi.slash")
            } description: {
                Text("home.load.failed_detail")
            } actions: {
                Button("common.retry") { Task { await home.load(locale: locale) } }.frame(minHeight: 44)
            }
        } else if let snapshot = home.snapshot {
            CalendarDayTimeline(
                snapshot: snapshot, savingIDs: pendingTodoIDs,
                onOpen: { selectedEntry = $0 }, onToggle: toggle)
        } else {
            ProgressView("home.loading").frame(maxWidth: .infinity, minHeight: 120)
        }
    }

    private func retryRow(_ title: LocalizedStringKey) -> some View {
        HStack {
            Text(title).font(.caption).foregroundStyle(CalendarPalette.secondary)
            Spacer()
            Button("common.retry") { Task { await model.load(for: home.selectedDate, locale: locale) } }
                .font(.caption).frame(minHeight: 44)
        }
    }

    private func toggle(_ entry: HomeEntry) {
        guard !pendingTodoIDs.contains(entry.id) else { return }
        pendingTodoIDs.insert(entry.id)
        Task {
            defer { pendingTodoIDs.remove(entry.id) }
            if await model.toggle(entry) {
                await refresh()
            } else if model.showWriteError {
                model.showWriteError = false
                home.showWriteError = true
            }
        }
    }

    private func select(_ date: Date) {
        let selectedMonth = home.dates.startOfMonth(containing: home.selectedDate)
        let targetMonth = home.dates.startOfMonth(containing: date)
        if targetMonth != selectedMonth { monthDirection = targetMonth > selectedMonth ? 1 : -1 }
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { home.selectDate(date) }
    }

    private func shiftMonth(_ offset: Int) {
        guard let date = home.dates.shiftingMonth(offset, from: home.selectedDate) else { return }
        select(date)
    }

    private func refresh() async {
        async let day: Void = home.load(locale: locale)
        async let calendar: Void = model.load(for: home.selectedDate, locale: locale)
        _ = await (day, calendar)
    }
}
