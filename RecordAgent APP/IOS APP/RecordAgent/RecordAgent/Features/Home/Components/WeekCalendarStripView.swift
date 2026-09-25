import SwiftUI

/// 首页横向周历选择条：左侧月份切换，右侧周一到周日日期列表
public struct WeekCalendarStripView: View {
    @ObservedObject var viewModel: HomeViewModel

    public init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        HStack(alignment: .center, spacing: 12) {
            // 左侧月份切换选择器
            Button(action: {
                // 点击可触发展开月历或切换月份
            }) {
                HStack(spacing: 4) {
                    Text(viewModel.currentMonthText)
                        .font(.system(size: 15, weight: .bold))
                        .foregroundColor(AppColors.textPrimary)

                    Image(systemName: "chevron.down")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(AppColors.textPrimary)
                }
                .padding(.vertical, 8)
                .padding(.horizontal, 4)
            }
            .buttonStyle(.plain)

            Spacer(minLength: 4)

            // 右侧周一至周日 7 天排布
            HStack(spacing: 0) {
                ForEach(viewModel.weekDays) { day in
                    let isSelected = (day.id == viewModel.selectedDateId)

                    Button(action: {
                        viewModel.selectDate(day)
                    }) {
                        VStack(spacing: 6) {
                            // 星期字样（一、二、三...）
                            Text(day.weekdaySymbol)
                                .font(.system(size: 11, weight: .regular))
                                .foregroundColor(isSelected ? AppColors.primaryBlue : AppColors.textSecondary)

                            // 日期数字与选中指示圆
                            ZStack {
                                if isSelected {
                                    Circle()
                                        .fill(AppColors.primaryBlue)
                                        .frame(width: 32, height: 32)
                                        .shadow(color: AppColors.primaryBlue.opacity(0.3), radius: 4, y: 2)
                                } else {
                                    Color.clear
                                        .frame(width: 32, height: 32)
                                }

                                Text("\(day.dayNumber)")
                                    .font(.system(size: 13, weight: isSelected ? .bold : .medium))
                                    .foregroundColor(isSelected ? .white : AppColors.textPrimary)
                            }

                            // 选中日期下方的小圆点指示器
                            if isSelected {
                                Circle()
                                    .fill(AppColors.primaryBlue)
                                    .frame(width: 3.5, height: 3.5)
                            } else {
                                Color.clear
                                    .frame(width: 3.5, height: 3.5)
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
    }
}
