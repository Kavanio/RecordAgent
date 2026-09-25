import SwiftUI

/// 底部导航主 Tab 枚举
public enum AppTab: Int, CaseIterable, Identifiable {
    case home = 0
    case discover = 1
    case profile = 2

    public var id: Int { rawValue }

    public var title: String {
        switch self {
        case .home: return "首页"
        case .discover: return "发现"
        case .profile: return "我的"
        }
    }

    public var iconName: String {
        switch self {
        case .home: return "house.fill"
        case .discover: return "safari"
        case .profile: return "person"
        }
    }

    public var selectedIconName: String {
        switch self {
        case .home: return "house.fill"
        case .discover: return "safari.fill"
        case .profile: return "person.fill"
        }
    }
}

/// 登录后的全局主容器视图：承载自定义底部 TabBar 与各主模块视图
@MainActor
public struct MainTabView: View {
    @State private var selectedTab: AppTab = .home
    let onLogout: () -> Void

    public init(onLogout: @escaping () -> Void) {
        self.onLogout = onLogout
    }

    public var body: some View {
        ZStack(alignment: .bottom) {
            // 内容区域
            Group {
                switch selectedTab {
                case .home:
                    HomeView()
                case .discover:
                    DiscoverPlaceholderView()
                case .profile:
                    ProfileView(onLogout: onLogout)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            // 自定义底部 TabBar
            customTabBar
        }
        .ignoresSafeArea(.keyboard, edges: .bottom)
    }

    /// 自定义底部导航栏
    private var customTabBar: some View {
        VStack(spacing: 0) {
            // 顶部微细分割线
            Divider()
                .background(Color(red: 0.92, green: 0.94, blue: 0.96))

            HStack {
                ForEach(AppTab.allCases) { tab in
                    let isSelected = (selectedTab == tab)

                    Button(action: {
                        withAnimation(.easeInOut(duration: 0.18)) {
                            selectedTab = tab
                        }
                    }) {
                        VStack(spacing: 4) {
                            Image(systemName: isSelected ? tab.selectedIconName : tab.iconName)
                                .font(.system(size: 20, weight: isSelected ? .semibold : .regular))
                                .foregroundColor(isSelected ? AppColors.primaryBlue : AppColors.tabInactive)

                            Text(tab.title)
                                .font(.system(size: 11, weight: isSelected ? .medium : .regular))
                                .foregroundColor(isSelected ? AppColors.primaryBlue : AppColors.tabInactive)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 8)
                        .padding(.bottom, 4)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 24)
            .background(
                Color.white.opacity(0.96)
                    .background(.ultraThinMaterial)
            )
        }
    }
}

/// 发现页面占位（待后续功能扩展）
struct DiscoverPlaceholderView: View {
    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                Spacer()

                Image(systemName: "sparkles.rectangle.stack")
                    .font(.system(size: 54))
                    .foregroundColor(AppColors.primaryBlue)

                Text("发现精彩")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundColor(AppColors.textPrimary)

                Text("更多精选记录模板、生活灵感与数据洞察\n正在精心准备中。")
                    .font(.system(size: 14))
                    .foregroundColor(AppColors.textSecondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 32)

                Spacer()
            }
            .background(AppColors.pageBackground)
            .navigationTitle("发现")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// 个人中心/我的页面
struct ProfileView: View {
    let onLogout: () -> Void

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                // 用户简要资料卡片
                HStack(spacing: 16) {
                    Circle()
                        .fill(AppColors.segmentSelectedBg)
                        .frame(width: 60, height: 60)
                        .overlay(
                            Image(systemName: "person.fill")
                                .font(.system(size: 28))
                                .foregroundColor(AppColors.primaryBlue)
                        )

                    VStack(alignment: .leading, spacing: 4) {
                        Text("王俊")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundColor(AppColors.textPrimary)

                        Text("已记录 128 天 · 持续发光中")
                            .font(.system(size: 13))
                            .foregroundColor(AppColors.textSecondary)
                    }

                    Spacer()
                }
                .padding(20)
                .background(Color.white)
                .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
                .shadow(color: Color.black.opacity(0.03), radius: 8, y: 2)
                .padding(.horizontal, 16)
                .padding(.top, 16)

                Spacer()

                // 退出登录按钮
                Button(action: onLogout) {
                    Text("退出登录")
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(Color.red.opacity(0.9))
                        .frame(maxWidth: .infinity)
                        .frame(height: 48)
                        .background(Color.red.opacity(0.08))
                        .clipShape(RoundedRectangle(cornerRadius: 14, style: .continuous))
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 90) // 避开底部 TabBar
            }
            .background(AppColors.pageBackground)
            .navigationTitle("我的")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
