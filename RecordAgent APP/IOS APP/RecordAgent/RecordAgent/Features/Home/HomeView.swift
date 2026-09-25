import SwiftUI

/// 首页采用连续画布，保留问候与插画氛围，中部只用留白组织信息。
struct HomeView: View {
    @ObservedObject var viewModel: HomeViewModel
    @Environment(\.locale) private var locale

    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                HomeHeaderView(viewModel: viewModel)
                WeekCalendarStripView(viewModel: viewModel)
                HomeBannerCardView()
                    .accessibilityHidden(true)
                HomeDayContentView(viewModel: viewModel)
            }
            .padding(.top, 4)
        }
        .background { HomeCanvas() }
        .refreshable { await viewModel.load(locale: locale) }
        .alert(Text("home.search.title"), isPresented: $viewModel.showSearchAlert) {
            Button("common.confirm", role: .cancel) {}
        } message: {
            Text("home.search.message")
        }
        .alert(Text("home.notifications.title"), isPresented: $viewModel.showNotificationAlert) {
            Button("home.notifications.dismiss", role: .cancel) {}
        } message: {
            Text("home.notifications.message")
        }
    }
}
