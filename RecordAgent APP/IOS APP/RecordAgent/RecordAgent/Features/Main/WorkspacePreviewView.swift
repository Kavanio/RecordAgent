import SwiftUI

/// 各业务入口先复用当日只读投影；自动任务与日历待办保持独立语义。
struct WorkspacePreviewView: View {
    let tab: AppTab
    @ObservedObject var viewModel: HomeViewModel
    @Environment(\.locale) private var locale

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                VStack(alignment: .leading, spacing: 8) {
                    Label(tab.title, systemImage: tab.icon)
                        .font(.title2.weight(.semibold))
                    Text("workspace.preview").font(.footnote).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 24)
                .padding(.top, 20)
                if tab == .tasks {
                    ContentUnavailableView {
                        Label("workspace.tasks.title", systemImage: "bell.badge")
                    } description: {
                        Text("workspace.tasks.description")
                    }
                } else {
                    if tab == .calendar {
                        DatePicker(
                            "home.date.choose",
                            selection: Binding(
                                get: { viewModel.selectedDate },
                                set: { viewModel.selectDate($0) }
                            ), in: viewModel.dates.supportedDates, displayedComponents: .date
                        )
                        .datePickerStyle(.graphical)
                        .padding(.horizontal, 16)
                    } else {
                        WeekCalendarStripView(viewModel: viewModel)
                    }
                    HomeDayContentView(
                        viewModel: viewModel, fixedCategory: tab.category,
                        showsSummary: tab == .calendar)
                }
            }
        }
        .background { HomeCanvas() }
        .refreshable { if tab != .tasks { await viewModel.load(locale: locale) } }
    }
}
