import SwiftUI

/// 四类摘要直接排在同一画布，按可用宽度换行，无独立卡片容器。
struct HomeDaySummaryView: View {
    let snapshot: HomeDaySnapshot
    @Binding var category: HomeCategory?
    @Environment(\.locale) private var locale
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                Text(snapshot.date, format: .dateTime.month().day().weekday(.wide))
                    .font(.headline)
                Spacer(minLength: 8)
                if snapshot.isDemo {
                    Text("home.demo.badge").font(.caption2).foregroundStyle(.secondary)
                }
            }
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 18) { metrics }
                VStack(alignment: .leading, spacing: 20) {
                    HStack(alignment: .top, spacing: 24) {
                        metric(.todo)
                        metric(.finance)
                    }
                    HStack(alignment: .top, spacing: 24) {
                        metric(.library)
                        metric(.record)
                    }
                }
                VStack(alignment: .leading, spacing: 20) {
                    ForEach(HomeCategory.allCases) { metric($0, allowsCompression: true) }
                }
            }
        }
    }

    private var metrics: some View {
        ForEach(HomeCategory.allCases) { metric($0) }
    }

    private func metric(_ item: HomeCategory, allowsCompression: Bool = false) -> some View {
        Button {
            withAnimation(reduceMotion ? nil : .smooth(duration: 0.25)) {
                category = category == item ? nil : item
            }
        } label: {
            VStack(alignment: .leading, spacing: 7) {
                Text(item == .finance ? "home.daily.expense" : item.title)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text(value(item))
                    .font(.title3.weight(.semibold).monospacedDigit())
                    .foregroundStyle(item.color)
                    .contentTransition(.numericText())
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                    .fixedSize(horizontal: !allowsCompression, vertical: false)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .frame(minHeight: 54)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(category == item ? .isSelected : [])
        .accessibilityHint(Text("home.filter.hint"))
    }

    private func value(_ category: HomeCategory) -> String {
        switch category {
        case .todo: return "\(snapshot.completedTodos)/\(snapshot.todos.count)"
        case .finance: return HomeFormat.money(snapshot.expenseInCents, locale: locale)
        case .library: return snapshot.documentCount.formatted(.number.locale(locale))
        case .record: return HomeFormat.minutes(snapshot.focusMinutes, locale: locale)
        }
    }
}
