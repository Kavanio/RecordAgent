import SwiftUI

/// 今日概览卡片：展示待办完成度、日程数、支出与专注学习等 4 列核心指标
public struct TodayOverviewCardView: View {
    let data: HomeOverviewData
    var onMoreTapped: (() -> Void)? = nil

    public init(data: HomeOverviewData, onMoreTapped: (() -> Void)? = nil) {
        self.data = data
        self.onMoreTapped = onMoreTapped
    }

    public var body: some View {
        VStack(spacing: 16) {
            // 顶部标题栏
            HStack {
                Text("home.overview.title")
                    .font(.system(size: 16, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)

                Spacer()

                Button(action: {
                    onMoreTapped?()
                }) {
                    HStack(spacing: 3) {
                        Text("common.more")
                            .font(.system(size: 13, weight: .regular))
                        Image(systemName: "chevron.right")
                            .font(.system(size: 10, weight: .semibold))
                    }
                    .foregroundColor(AppColors.textSecondary)
                }
                .buttonStyle(.plain)
            }

            // 四列指标行
            HStack(alignment: .top, spacing: 12) {
                // 1. 待办
                overviewColumn(
                    dotColor: AppColors.primaryBlue,
                    titleKey: "home.overview.todo_short",
                    valueView: AnyView(
                        HStack(alignment: .firstTextBaseline, spacing: 2) {
                            Text("\(data.todoCompleted)")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(AppColors.textPrimary)
                            Text(" / \(data.todoTotal)")
                                .font(.system(size: 12, weight: .regular))
                                .foregroundColor(AppColors.textSecondary)
                        }
                    ),
                    progress: data.todoProgress,
                    progressColor: AppColors.primaryBlue
                )

                // 2. 日程
                overviewColumn(
                    dotColor: AppColors.overviewSchedule,
                    titleKey: "home.overview.schedule_short",
                    valueView: AnyView(
                        Text("\(data.scheduleCount)")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(AppColors.textPrimary)
                    ),
                    progress: 0.4,
                    progressColor: AppColors.overviewSchedule
                )

                // 3. 支出
                overviewColumn(
                    dotColor: AppColors.overviewExpense,
                    titleKey: "home.overview.expense_short",
                    valueView: AnyView(
                        Text(data.formattedExpense)
                            .font(.system(size: 16, weight: .bold))
                            .foregroundColor(AppColors.overviewExpense)
                            .lineLimit(1)
                            .minimumScaleFactor(0.8)
                    ),
                    progress: 0.35,
                    progressColor: AppColors.overviewExpense
                )

                // 4. 学习
                overviewColumn(
                    dotColor: AppColors.overviewStudy,
                    titleKey: "home.overview.study_short",
                    valueView: AnyView(
                        HStack(alignment: .firstTextBaseline, spacing: 2) {
                            Text(data.formattedStudyHours)
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(AppColors.textPrimary)
                            Text(" h")
                                .font(.system(size: 12, weight: .regular))
                                .foregroundColor(AppColors.textSecondary)
                        }
                    ),
                    progress: 0.62,
                    progressColor: AppColors.overviewStudy
                )
            }
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .background(Color.white)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .shadow(color: Color.black.opacity(0.04), radius: 10, x: 0, y: 3)
        .padding(.horizontal, 16)
    }

    /// 单个概览列视图组件
    @ViewBuilder
    private func overviewColumn(
        dotColor: Color,
        titleKey: LocalizedStringKey,
        valueView: AnyView,
        progress: Double,
        progressColor: Color
    ) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // 顶部分类指示点与标签
            HStack(spacing: 5) {
                Circle()
                    .fill(dotColor)
                    .frame(width: 6, height: 6)

                Text(titleKey)
                    .font(.system(size: 12, weight: .regular))
                    .foregroundColor(AppColors.textSecondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
            }

            // 中间数值
            valueView
                .frame(height: 22, alignment: .leading)

            // 底部横向圆角进度条
            GeometryReader { geo in
                ZStack(alignment: .leading) {
                    Capsule()
                        .fill(AppColors.progressTrackBg)
                        .frame(height: 4)

                    Capsule()
                        .fill(progressColor)
                        .frame(width: max(geo.size.width * CGFloat(progress), 4), height: 4)
                }
            }
            .frame(height: 4)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}
