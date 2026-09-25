import SwiftUI

/// 近期待办卡片：支持待办项查看、勾选完成交互与时间提示
public struct RecentTodosCardView: View {
    @ObservedObject var viewModel: HomeViewModel
    var onAllTapped: (() -> Void)? = nil

    public init(viewModel: HomeViewModel, onAllTapped: (() -> Void)? = nil) {
        self.viewModel = viewModel
        self.onAllTapped = onAllTapped
    }

    public var body: some View {
        VStack(spacing: 16) {
            // 顶部标题与全部按钮
            HStack {
                Text("home.recent_todos")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)

                Spacer()

                Button(action: {
                    onAllTapped?()
                }) {
                    HStack(spacing: 3) {
                        Text("home.all_todos")
                            .font(.system(size: 13, weight: .regular))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            }

            // 待办清单
            if viewModel.recentTodos.isEmpty {
                Text("home.empty_todos")
                    .font(.system(size: 13))
                    .foregroundColor(AppColors.textSecondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                    .padding(.vertical, 16)
            } else {
                VStack(spacing: 14) {
                    ForEach(viewModel.recentTodos) { todo in
                        todoRow(todo)
                    }
                }
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: Color.black.opacity(0.04), radius: 10, x: 0, y: 3)
        .padding(.horizontal, 16)
    }

    /// 单个待办项行
    @ViewBuilder
    private func todoRow(_ todo: RecentTodoItem) -> some View {
        Button(action: {
            viewModel.toggleTodoCompletion(id: todo.id)
        }) {
            HStack(spacing: 12) {
                // 圆角方形勾选框
                ZStack {
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .stroke(todo.isCompleted ? AppColors.primaryBlue : AppColors.checkboxBorder, lineWidth: 1.5)
                        .frame(width: 20, height: 20)

                    if todo.isCompleted {
                        RoundedRectangle(cornerRadius: 6, style: .continuous)
                            .fill(AppColors.primaryBlue)
                            .frame(width: 20, height: 20)

                        Image(systemName: "checkmark")
                            .font(.system(size: 11, weight: .bold))
                            .foregroundColor(.white)
                    }
                }
                .animation(.easeInOut(duration: 0.2), value: todo.isCompleted)

                // 待办标题内容
                Text(todo.title)
                    .font(.system(size: 14, weight: .regular))
                    .foregroundColor(todo.isCompleted ? AppColors.textSecondary : AppColors.textPrimary)
                    .strikethrough(todo.isCompleted, color: AppColors.textSecondary)
                    .lineLimit(1)

                Spacer()

                // 时间标签
                Text(todo.timeString)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundColor(AppColors.textSecondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}
