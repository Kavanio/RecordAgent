import SwiftUI

/// 首页的显示语义集中于 Feature，领域模型不依赖 SwiftUI。
extension HomeCategory {
    var title: LocalizedStringKey {
        switch self {
        case .todo: return "home.category.todo"
        case .finance: return "tab.finance"
        case .library: return "tab.library"
        case .record: return "tab.record"
        }
    }
    var color: Color {
        switch self {
        case .todo: return AppColors.primaryBlue
        case .finance: return Color(red: 0.77, green: 0.35, blue: 0.26)
        case .library: return Color(red: 0.49, green: 0.43, blue: 0.70)
        case .record: return Color(red: 0.19, green: 0.52, blue: 0.47)
        }
    }
    var icon: String {
        switch self {
        case .todo: return "checkmark.circle"
        case .finance: return "yensign"
        case .library: return "doc.text"
        case .record: return "timer"
        }
    }
}

enum HomeFormat {
    static func money(_ cents: Int, locale: Locale) -> String {
        (Decimal(cents) / 100).formatted(.currency(code: "CNY").locale(locale))
    }
    static func minutes(_ minutes: Int, locale: Locale) -> String {
        AppLocalization.string("home.minutes \(minutes)", locale: locale)
    }
}

/// 同一张连续画布，以柔和色晕表达层次，不为信息块绘制卡片或边框。
struct HomeCanvas: View {
    @Environment(\.colorScheme) private var scheme
    var body: some View {
        ZStack(alignment: .topLeading) {
            Color(uiColor: .systemBackground)
            LinearGradient(
                colors: [AppColors.skyLightBlue.opacity(scheme == .dark ? 0.12 : 0.75), .clear],
                startPoint: .top, endPoint: .bottom
            )
            RadialGradient(
                colors: [HomeCategory.finance.color.opacity(0.055), .clear],
                center: .trailing, startRadius: 0, endRadius: 420
            )
        }
        .ignoresSafeArea()
    }
}
