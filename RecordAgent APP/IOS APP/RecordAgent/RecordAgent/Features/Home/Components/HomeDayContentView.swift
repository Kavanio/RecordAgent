import SwiftUI

/// 首页与业务预览复用按日加载、错误、空状态和内容展示。
struct HomeDayContentView: View {
    @ObservedObject var viewModel: HomeViewModel
    var fixedCategory: HomeCategory? = nil
    var showsSummary = true
    @State private var selectedEntry: HomeEntry?
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
                        if snapshot.isDemo { Text("home.demo.badge").font(.caption2).foregroundStyle(.secondary) }
                    }
                    if fixedCategory == .finance || fixedCategory == .record {
                        HomeTrendChartView(points: snapshot.trend, metric: fixedCategory == .record ? .focus : .expense)
                    }
                }
                VStack(alignment: .leading, spacing: 16) {
                    if fixedCategory == nil { filters }
                    HomeEntryListView(
                        entries: viewModel.entries(in: fixedCategory ?? viewModel.category),
                        savingIDs: viewModel.savingTodoIDs,
                        onToggle: { entry in Task { await viewModel.toggleTodo(entry) } },
                        onOpen: { selectedEntry = $0 }
                    )
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

    private var filters: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 22) {
                filter(nil, title: "home.filter.all")
                ForEach(HomeCategory.allCases) { item in filter(item, title: item.title) }
            }
        }
    }
    private func filter(_ item: HomeCategory?, title: LocalizedStringKey) -> some View {
        Button {
            withAnimation(reduceMotion ? nil : .smooth(duration: 0.25)) { viewModel.category = item }
        } label: {
            Text(title)
                .font(.subheadline.weight(viewModel.category == item ? .semibold : .regular))
                .foregroundStyle(viewModel.category == item ? Color.primary : .secondary)
                .frame(minWidth: 32, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(viewModel.category == item ? .isSelected : [])
    }
}
