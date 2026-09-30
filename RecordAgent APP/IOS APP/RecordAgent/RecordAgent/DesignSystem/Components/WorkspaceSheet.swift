import SwiftUI

/// 工作区面板统一背景、拖动条与圆角；短选择面板半屏起步，长编辑器可全屏展开。
struct WorkspaceSheetStyle: ViewModifier {
  var compact = false
  func body(content: Content) -> some View {
    content
      .presentationDetents(compact ? [.medium, .large] : [.large])
      .presentationDragIndicator(.visible)
      .presentationCornerRadius(30)
      .presentationBackground(Color(uiColor: .systemGroupedBackground))
  }
}

extension View {
  func workspaceSheet(compact: Bool = false) -> some View {
    modifier(WorkspaceSheetStyle(compact: compact))
  }
}

/// 标题与关闭入口留在内容安全区域，避免系统工具栏占据短面板的主要空间。
struct WorkspaceSheetHeader: View {
  let title: LocalizedStringKey
  var subtitle: LocalizedStringKey? = nil
  var disabled = false
  let onClose: () -> Void
  var body: some View {
    HStack(alignment: .top, spacing: 16) {
      VStack(alignment: .leading, spacing: 7) {
        Text(title).font(.title2.weight(.bold))
        if let subtitle { Text(subtitle).font(.subheadline).foregroundStyle(.secondary) }
      }
      Spacer(minLength: 0)
      Button(action: onClose) {
        Image(systemName: "xmark").font(.subheadline.weight(.semibold))
          .frame(width: 44, height: 44)
          .background(Color(uiColor: .tertiarySystemFill), in: Circle())
      }
      .buttonStyle(.plain).disabled(disabled).accessibilityLabel("workspace.cancel")
    }
  }
}

/// 只读详情使用与编辑器相同的标题、背景和内容分组，业务操作留在内容内。
struct WorkspaceDetailSheet<Content: View>: View {
  let title: LocalizedStringKey
  var isBusy = false
  var compact = false
  let onClose: () -> Void
  @ViewBuilder let content: () -> Content
  var body: some View {
    VStack(spacing: 0) {
      WorkspaceSheetHeader(title: title, disabled: isBusy, onClose: onClose)
        .padding(.horizontal, 24).padding(.top, 28).padding(.bottom, 20)
      ScrollView {
        content()
          .padding(20).frame(maxWidth: .infinity, alignment: .leading)
          .background(
            Color(uiColor: .secondarySystemGroupedBackground),
            in: RoundedRectangle(cornerRadius: 22)
          )
          .padding(.horizontal, 24).padding(.bottom, 24)
      }
    }
    .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
    .interactiveDismissDisabled(isBusy)
    .tint(AppColors.primaryBlue)
    .workspaceSheet(compact: compact)
  }
}
