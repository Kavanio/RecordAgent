import SwiftUI

/// 四个工作区共用原生导航、白色画布与内容间距，业务内容由调用方编排。
struct WorkspaceScreen<Content: View>: View {
  let title: LocalizedStringKey
  var subtitle: LocalizedStringKey? = nil
  let onBack: () -> Void
  var actionTitle: LocalizedStringKey = "workspace.add"
  var actionSymbol = "plus"
  var onAdd: (() -> Void)? = nil
  @ViewBuilder let content: () -> Content

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 28) {
          if let subtitle {
            Text(subtitle)
              .font(.subheadline)
              .foregroundStyle(.secondary)
              .fixedSize(horizontal: false, vertical: true)
          }
          content()
        }
        .padding(.horizontal, 24)
        .padding(.top, 8)
        .padding(.bottom, 32)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
      .background(Color(uiColor: .systemBackground).ignoresSafeArea())
      .navigationTitle(title)
      .navigationBarTitleDisplayMode(.large)
      .toolbar {
        ToolbarItem(placement: .topBarLeading) {
          Button(action: onBack) {
            Label("tab.workspace", systemImage: "chevron.left")
          }
          .frame(minHeight: 44)
        }
        if let onAdd {
          ToolbarItem(placement: .topBarTrailing) {
            Button(action: onAdd) {
              Image(systemName: actionSymbol).font(.body.weight(.medium))
            }
            .frame(minWidth: 44, minHeight: 44)
            .accessibilityLabel(actionTitle)
          }
        }
      }
      .tint(AppColors.primaryBlue)
    }
  }
}

/// 搜索以轻量底线呈现，不给每个模块添加独立卡片。
struct WorkspaceSearchField: View {
  @Binding var text: String
  var prompt: LocalizedStringKey = "workspace.search"

  var body: some View {
    HStack(spacing: 12) {
      Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
      TextField(prompt, text: $text)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .submitLabel(.search)
      if !text.isEmpty {
        Button {
          text = ""
        } label: {
          Image(systemName: "xmark.circle.fill")
        }
        .foregroundStyle(.secondary)
        .frame(minWidth: 44, minHeight: 44)
        .accessibilityLabel("workspace.search.clear")
      }
    }
    .frame(minHeight: 44)
    .overlay(alignment: .bottom) { Divider() }
  }
}

struct WorkspaceFilters<Value: Hashable>: View {
  let options: [Value]
  @Binding var selection: Value
  let title: (Value) -> LocalizedStringKey

  var body: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 24) {
        ForEach(options, id: \.self) { option in
          Button {
            selection = option
          } label: {
            Text(title(option))
              .font(.subheadline.weight(selection == option ? .semibold : .regular))
              .foregroundStyle(selection == option ? Color.primary : Color.secondary)
              .padding(.vertical, 12)
              .overlay(alignment: .bottom) {
                if selection == option {
                  Rectangle().frame(height: 1.5).foregroundStyle(Color.primary)
                }
              }
          }
          .buttonStyle(.plain)
          .accessibilityAddTraits(selection == option ? .isSelected : [])
        }
      }
    }
  }
}

struct WorkspaceSection<Content: View>: View {
  let title: LocalizedStringKey
  @ViewBuilder let content: () -> Content

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      Text(title).font(.headline)
      content()
    }
    .frame(maxWidth: .infinity, alignment: .leading)
  }
}

struct WorkspaceEmptyState: View {
  let title: LocalizedStringKey
  let description: LocalizedStringKey
  let symbol: String

  var body: some View {
    ContentUnavailableView {
      Label(title, systemImage: symbol)
    } description: {
      Text(description)
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 16)
  }
}

struct WorkspaceErrorView: View {
  let message: String
  let onRetry: () -> Void

  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      Label(message, systemImage: "exclamationmark.circle")
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .fixedSize(horizontal: false, vertical: true)
      Button("workspace.retry", action: onRetry).frame(minHeight: 44)
    }
  }
}

struct WorkspaceValue: View {
  let label: LocalizedStringKey
  let value: String

  var body: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(label).font(.caption).foregroundStyle(.secondary)
      Text(value).font(.title3.weight(.medium)).monospacedDigit()
    }
  }
}

/// 自然月切换复用首页月份选择器，避免写死日期格式与跨月秒数。
struct WorkspaceMonthPicker: View {
  @Binding var date: Date
  @State private var isPresented = false
  private let dates = HomeCalendar(calendar: Calendar(identifier: .gregorian))

  var body: some View {
    HStack {
      Button {
        if let next = dates.shiftingMonth(-1, from: date) { date = next }
      } label: {
        Image(systemName: "chevron.left")
      }
      .frame(minWidth: 44, minHeight: 44)
      .accessibilityLabel("workspace.month.previous")
      Spacer(minLength: 8)
      Button {
        isPresented = true
      } label: {
        HStack(spacing: 8) {
          Text(date, format: .dateTime.year().month(.wide))
          Image(systemName: "chevron.down").font(.caption)
        }
        .font(.subheadline.weight(.medium))
      }
      .foregroundStyle(.primary)
      .frame(minHeight: 44)
      .accessibilityLabel("workspace.month.choose")
      Spacer(minLength: 8)
      Button {
        if let next = dates.shiftingMonth(1, from: date) { date = next }
      } label: {
        Image(systemName: "chevron.right")
      }
      .frame(minWidth: 44, minHeight: 44)
      .accessibilityLabel("workspace.month.next")
    }
    .sheet(isPresented: $isPresented) {
      HomeMonthPickerView(selectedDate: date, dates: dates) { year, month in
        if let next = dates.date(inYear: year, month: month, preservingDayOf: date) { date = next }
        isPresented = false
      }
    }
  }
}

/// 编辑共用保存/取消与就地错误；保存失败保留草稿并阻止并发提交。
struct WorkspaceEditor<Content: View>: View {
  let title: LocalizedStringKey
  let isValid: Bool
  let onSave: () async throws -> Void
  @ViewBuilder let content: () -> Content
  @Environment(\.dismiss) private var dismiss
  @State private var isSaving = false
  @State private var errorMessage: String?

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 28) {
          content().disabled(isSaving)
          if let errorMessage {
            Label(errorMessage, systemImage: "exclamationmark.circle")
              .font(.footnote)
              .foregroundStyle(.red)
              .fixedSize(horizontal: false, vertical: true)
          }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
      .background(Color(uiColor: .systemBackground).ignoresSafeArea())
      .navigationTitle(title)
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("workspace.cancel") { dismiss() }.disabled(isSaving)
        }
        ToolbarItem(placement: .confirmationAction) {
          if isSaving {
            ProgressView()
          } else {
            Button("workspace.save") {
              Task {
                isSaving = true
                errorMessage = nil
                do {
                  try await onSave()
                  dismiss()
                } catch is CancellationError {} catch { errorMessage = error.localizedDescription }
                isSaving = false
              }
            }
            .disabled(!isValid)
          }
        }
      }
      .interactiveDismissDisabled(isSaving)
    }
    .tint(AppColors.primaryBlue)
  }
}

struct WorkspaceTextField: View {
  let title: LocalizedStringKey
  @Binding var text: String
  var axis: Axis = .horizontal

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(title).font(.caption).foregroundStyle(.secondary)
      TextField(title, text: $text, axis: axis)
        .frame(minHeight: 44)
        .overlay(alignment: .bottom) { Divider() }
    }
  }
}
