import SwiftUI

/// 首页与业务预览复用按日加载、错误、空状态和内容展示。
struct HomeDayContentView: View {
    @ObservedObject var viewModel: HomeViewModel
    var fixedCategory: HomeCategory? = nil
    var showsSummary = true
    @State private var selectedEntry: HomeEntry?
    @State private var digestCategory: HomeCategory = .todo
    @ScaledMetric(relativeTo: .body) private var digestHeight = 190
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 28) {
            if viewModel.loadFailed {
                ContentUnavailableView {
                    Label("home.load.failed", systemImage: "wifi.slash")
                } description: {
                    Text("home.load.failed_detail")
                } actions: {
                    Button("common.retry") { Task { await viewModel.load(locale: locale) } }
                        .frame(minHeight: 44)
                }
            } else if let snapshot = viewModel.snapshot {
                if showsSummary {
                    HomeDaySummaryView(snapshot: snapshot, category: $viewModel.category)
                    HomeTrendChartView(points: snapshot.trend)
                } else {
                    HStack {
                        Text(snapshot.date, format: .dateTime.month().day().weekday())
                            .font(.subheadline).foregroundStyle(.secondary)
                        Spacer()
                        if snapshot.isDemo {
                            Text("home.demo.badge").font(.caption2).foregroundStyle(.secondary)
                        }
                    }
                    if fixedCategory == .finance || fixedCategory == .record {
                        HomeTrendChartView(
                            points: snapshot.trend,
                            metric: fixedCategory == .record ? .focus : .expense)
                    }
                }
                if fixedCategory == nil && viewModel.category == nil {
                    dayDigest(snapshot)
                } else {
                    VStack(alignment: .leading, spacing: 16) {
                        if fixedCategory == nil {
                            Button {
                                withAnimation(reduceMotion ? nil : .smooth(duration: 0.3)) {
                                    viewModel.category = nil
                                }
                            } label: {
                                Label("home.day.back", systemImage: "arrow.left")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                                    .frame(minHeight: 44)
                            }
                            .buttonStyle(.plain)
                        }
                        HomeEntryListView(
                            entries: viewModel.entries(in: fixedCategory ?? viewModel.category),
                            savingIDs: viewModel.savingTodoIDs,
                            onToggle: { entry in Task { await viewModel.toggleTodo(entry) } },
                            onOpen: { selectedEntry = $0 }
                        )
                    }
                }
                if snapshot.isDemo {
                    Text("home.demo.footer")
                        .font(.caption2).foregroundStyle(.tertiary)
                        .frame(maxWidth: .infinity, alignment: .center)
                        .padding(.top, 8)
                }
            } else {
                ProgressView("home.loading")
                    .frame(maxWidth: .infinity, minHeight: 200)
            }
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 28)
        .sheet(item: $selectedEntry) { entry in
            HomeEntryDetailView(entry: entry, viewModel: viewModel)
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .onChange(of: viewModel.snapshot) { _, _ in openRequestedEntry() }
        .onChange(of: viewModel.requestedEntryID) { _, _ in openRequestedEntry() }
        .onAppear { openRequestedEntry() }
        .alert(
            Text("home.write.failed"),
            isPresented: Binding(
                get: { viewModel.showWriteError && selectedEntry == nil },
                set: { viewModel.showWriteError = $0 }
            )
        ) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.write.failed_detail")
        }
    }

    private func openRequestedEntry() {
        guard let id = viewModel.requestedEntryID,
            let entry = viewModel.snapshot?.entries.first(where: { $0.id == id })
        else { return }
        selectedEntry = entry
        viewModel.consumeRequestedEntry()
    }

    private func dayDigest(_ snapshot: HomeDaySnapshot) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("home.day.digest")
                .font(.headline)
            TabView(selection: $digestCategory) {
                ForEach(HomeCategory.allCases) { category in
                    digestPage(category, snapshot: snapshot)
                        .tag(category)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .frame(height: digestHeight)
            HStack(spacing: 7) {
                ForEach(HomeCategory.allCases) { category in
                    Capsule()
                        .fill(
                            category == digestCategory
                                ? category.color : Color.secondary.opacity(0.22)
                        )
                        .frame(width: category == digestCategory ? 17 : 5, height: 5)
                }
            }
            .frame(maxWidth: .infinity)
            .accessibilityHidden(true)
        }
    }

    private func digestPage(_ category: HomeCategory, snapshot: HomeDaySnapshot) -> some View {
        let entries = snapshot.entries.filter { $0.kind.category == category }
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(category.title).font(.subheadline.weight(.medium)).foregroundStyle(
                    category.color)
                Spacer()
                Text("\((HomeCategory.allCases.firstIndex(of: category) ?? 0) + 1) / 4")
                    .font(.caption.monospacedDigit()).foregroundStyle(.tertiary)
            }
            if let entry = entries.last {
                Text(entry.title)
                    .font(.title3.weight(.medium))
                    .lineLimit(2)
                Text(entry.detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            } else {
                Text("home.day.empty_detail")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer(minLength: 0)
            Button {
                withAnimation(reduceMotion ? nil : .smooth(duration: 0.3)) {
                    viewModel.category = category
                }
            } label: {
                HStack(spacing: 5) {
                    Text("home.day.open_category")
                    Image(systemName: "arrow.up.right")
                }
                .font(.subheadline.weight(.medium))
                .foregroundStyle(category.color)
                .frame(minHeight: 44)
            }
            .buttonStyle(.plain)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 4)
    }
}
