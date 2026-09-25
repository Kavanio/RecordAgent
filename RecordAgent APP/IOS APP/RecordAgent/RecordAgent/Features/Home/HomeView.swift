import SwiftUI

/// 首页主界面视图：整合问候栏、周历条、Banner 卡片、今日概览、近期待办及灵感卡片
@MainActor
public struct HomeView: View {
    @StateObject private var viewModel: HomeViewModel

    public init(viewModel: HomeViewModel? = nil) {
        _viewModel = StateObject(wrappedValue: viewModel ?? HomeViewModel())
    }

    public var body: some View {
        ZStack(alignment: .bottomTrailing) {
            // 背景渐变：顶部微蓝天空到底部纯净页面底色
            LinearGradient(
                colors: [
                    Color(red: 0.93, green: 0.96, blue: 1.0),
                    Color(red: 0.97, green: 0.985, blue: 1.0),
                    AppColors.pageBackground
                ],
                startPoint: .top,
                endPoint: .bottom
            )
            .ignoresSafeArea()

            // 主滚动内容区域
            ScrollView(.vertical, showsIndicators: false) {
                VStack(spacing: 16) {
                    // 1. 顶部问候栏（用户名、问候语、搜索、通知）
                    HomeHeaderView(viewModel: viewModel)

                    // 2. 周历日期选择条（月份切换与 7 天日期）
                    WeekCalendarStripView(viewModel: viewModel)

                    // 3. 主插画横幅卡片（生活不在别处语录与背景插画）
                    HomeBannerCardView()

                    // 4. 今日概览卡片（待办、日程、支出、学习指标与进度条）
                    TodayOverviewCardView(data: viewModel.overviewData) {
                        // 点击“更多”可触发展开日历/概览详情
                    }

                    // 5. 近期待办卡片与悬浮按钮区域
                    ZStack(alignment: .bottomTrailing) {
                        RecentTodosCardView(viewModel: viewModel)

                        // 浮动在待办卡片右下角区域的快捷加号按钮
                        FloatingActionButton {
                            viewModel.showQuickAddSheet = true
                        }
                        .offset(x: -8, y: 16)
                    }
                    .padding(.bottom, 8)

                    // 6. 底部语录与拍立得照片卡片
                    InspirationCardView()

                    // 底部为自定义 TabBar 留出空间
                    Spacer()
                        .frame(height: 70)
                }
                .padding(.top, 4)
            }

            // 搜索占位提示弹窗
            .alert("搜索", isPresented: $viewModel.showSearchAlert) {
                Button("确定", role: .cancel) {}
            } message: {
                Text("搜索功能正在准备中，后续将支持快速检索待办、日程与记录。")
            }

            // 消息通知占位提示弹窗
            .alert("通知中心", isPresented: $viewModel.showNotificationAlert) {
                Button("我知道了", role: .cancel) {
                    viewModel.unreadNotificationCount = 0
                }
            } message: {
                Text("暂无未读系统提醒。每天记录一点点，生活更清晰。")
            }

            // 快捷新增待办弹窗
            .sheet(isPresented: $viewModel.showQuickAddSheet) {
                quickAddSheet
                    .presentationDetents([.fraction(0.35)])
                    .presentationDragIndicator(.visible)
            }
        }
    }

    /// 快捷新增待办底部弹出卡片
    private var quickAddSheet: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 18) {
                Text("新建待办")
                    .font(.system(size: 18, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)
                    .padding(.top, 8)

                TextField("想记录什么待办事项？", text: $viewModel.quickAddTitle)
                    .font(.system(size: 15))
                    .padding(14)
                    .background(Color(red: 0.96, green: 0.97, blue: 0.98))
                    .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))

                HStack(spacing: 12) {
                    Button(action: {
                        viewModel.showQuickAddSheet = false
                        viewModel.quickAddTitle = ""
                    }) {
                        Text("取消")
                            .font(.system(size: 15, weight: .medium))
                            .foregroundColor(AppColors.textSecondary)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(Color(red: 0.94, green: 0.95, blue: 0.97))
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }

                    Button(action: {
                        viewModel.addQuickTodo()
                    }) {
                        Text("添加")
                            .font(.system(size: 15, weight: .semibold))
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .frame(height: 44)
                            .background(AppColors.primaryBlue)
                            .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                    }
                    .disabled(viewModel.quickAddTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    .opacity(viewModel.quickAddTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? 0.5 : 1.0)
                }

                Spacer()
            }
            .padding(.horizontal, 20)
            .background(Color.white)
        }
    }
}
