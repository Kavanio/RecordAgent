import SwiftUI

struct HomeEntryDetailView: View {
    let entry: HomeEntry
    @ObservedObject var viewModel: HomeViewModel
    var savingIDs: Set<String> = []
    var onToggle: ((HomeEntry) -> Void)?
    @Environment(\.locale) private var locale
    @Environment(\.dismiss) private var dismiss

    private var current: HomeEntry {
        viewModel.snapshot?.entries.first(where: { $0.id == entry.id }) ?? entry
    }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    Label(current.kind.title, systemImage: current.kind.category.icon)
                        .font(.subheadline)
                        .foregroundStyle(current.kind.category.color)
                    Text(current.title).font(.title2.weight(.semibold))
                    Text(current.date, format: .dateTime.month().day().hour().minute())
                        .font(.subheadline).foregroundStyle(.secondary)
                    if current.kind == .expense || current.kind == .income {
                        Text(current.kind == .income ? "home.daily.income" : "home.daily.expense")
                            .font(.caption).foregroundStyle(.secondary)
                        Text(HomeFormat.money(current.amountInCents, locale: locale))
                            .font(.largeTitle.weight(.medium).monospacedDigit())
                            .foregroundStyle(current.kind.category.color)
                    }
                    if current.kind == .focus {
                        Text(HomeFormat.minutes(current.durationMinutes, locale: locale))
                            .font(.largeTitle.weight(.medium).monospacedDigit())
                            .foregroundStyle(current.kind.category.color)
                    }
                    Text(current.detail).font(.body).lineSpacing(7)
                    if !current.sampleImageNames.isEmpty {
                        HomePhotoStrip(names: current.sampleImageNames)
                    }
                    if current.kind == .todo {
                        Button {
                            if let onToggle { onToggle(current) } else { Task { await viewModel.toggleTodo(current) } }
                        } label: {
                            Label(
                                current.isCompleted ? "home.todo.undo" : "home.todo.complete",
                                systemImage: current.isCompleted ? "arrow.uturn.backward" : "checkmark"
                            )
                            .frame(minHeight: 44)
                        }
                        .disabled(viewModel.savingTodoIDs.contains(current.id) || savingIDs.contains(current.id))
                    }
                    if viewModel.snapshot?.isDemo == true {
                        Text("home.demo.detail").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(24)
            }
            .background { HomeCanvas() }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.done") { dismiss() }
                }
            }
        }
        .tint(AppColors.primaryBlue)
        .alert(Text("home.write.failed"), isPresented: $viewModel.showWriteError) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.write.failed_detail")
        }
    }
}
