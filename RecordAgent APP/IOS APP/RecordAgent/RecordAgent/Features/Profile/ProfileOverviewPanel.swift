import SwiftUI

/// 用一张轻量财务画布与两条生活刻度组织个人统计，不把每个数字做成独立卡片。
struct ProfileOverviewPanel: View {
    let state: ProfileOverviewViewModel.State
    @Environment(\.locale) private var locale

    private var overview: ProfileOverview? {
        if case .ready(let value) = state { return value }
        return nil
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .firstTextBaseline) {
                Text("profile.overview.title")
                    .font(.headline)
                    .foregroundStyle(AppColors.textPrimary)
                Spacer(minLength: 8)
                status
            }

            finance

            HStack(alignment: .top, spacing: 20) {
                recordTime
                    .frame(maxWidth: .infinity, alignment: .leading)
                Rectangle()
                    .fill(AppColors.inputBorder)
                    .frame(width: 1, height: 106)
                    .accessibilityHidden(true)
                library
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 8)

            completion
        }
    }

    @ViewBuilder
    private var status: some View {
        switch state {
        case .loading:
            ProgressView().controlSize(.mini)
                .accessibilityLabel(Text("profile.overview.loading"))
        case .ready(let value):
            if value.isDemo {
                Text("profile.overview.demo")
                    .font(.caption2)
                    .foregroundStyle(AppColors.textSecondary)
            }
        case .unavailable:
            Text("profile.overview.unavailable")
                .font(.caption2)
                .foregroundStyle(AppColors.textSecondary)
        }
    }

    private var finance: some View {
        VStack(alignment: .leading, spacing: 20) {
            Label("profile.overview.finance", systemImage: "circle.grid.2x2")
                .font(.caption.weight(.medium))
                .foregroundStyle(AppColors.textSecondary)

            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 22) {
                    assets
                    Spacer(minLength: 0)
                    totalExpense
                }
                VStack(alignment: .leading, spacing: 20) {
                    assets
                    totalExpense
                }
            }
        }
        .padding(22)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .fill(Color(uiColor: .secondarySystemBackground).opacity(0.8))
        }
    }

    private var assets: some View {
        metric(
            title: "profile.overview.assets",
            value: overview.map { HomeFormat.money($0.totalAssetsInCents, locale: locale) },
            color: AppColors.textPrimary,
            prominent: true,
            growthRate: overview?.assetsGrowthRate)
    }

    private var totalExpense: some View {
        metric(
            title: "profile.overview.totalExpense",
            value: overview.map { HomeFormat.money($0.totalExpenseInCents, locale: locale) },
            color: HomeCategory.finance.color,
            growthRate: overview?.expenseGrowthRate,
            growthInverse: true)
    }

    private var recordTime: some View {
        VStack(alignment: .leading, spacing: 9) {
            accent(HomeCategory.record.color)
            Text("profile.overview.recordTime")
                .font(.caption)
                .foregroundStyle(AppColors.textSecondary)
            HStack(alignment: .lastTextBaseline, spacing: 4) {
                Text(
                    verbatim: overview.map {
                        (Double($0.totalRecordMinutes) / 60)
                            .formatted(.number.precision(.fractionLength(0...1)).locale(locale))
                    } ?? "—"
                )
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(HomeCategory.record.color)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                Text("profile.overview.hours")
                    .font(.caption)
                    .foregroundStyle(AppColors.textSecondary)
                if let growth = overview?.recordMinutesGrowthRate {
                    growthBadge(rate: growth)
                        .padding(.leading, 2)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var library: some View {
        VStack(alignment: .leading, spacing: 9) {
            accent(HomeCategory.library.color)
            Text("profile.overview.documents")
                .font(.caption)
                .foregroundStyle(AppColors.textSecondary)
            Text(verbatim: overview?.documentCount.formatted(.number.locale(locale)) ?? "—")
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(HomeCategory.library.color)
            HStack(spacing: 4) {
                Text("profile.overview.words")
                Text(verbatim: overview?.documentWordCount.formatted(.number.locale(locale)) ?? "—")
                    .monospacedDigit()
                Text("profile.overview.characters")
            }
            .font(.caption2)
            .foregroundStyle(AppColors.textSecondary)
            .lineLimit(1)
            .minimumScaleFactor(0.75)
        }
        .accessibilityElement(children: .combine)
    }

    private var completion: some View {
        HStack(spacing: 17) {
            ZStack {
                Circle()
                    .stroke(AppColors.progressTrackBg, lineWidth: 6)
                Circle()
                    .trim(from: 0, to: min(max(overview?.monthlyTodoCompletionRate ?? 0, 0), 1))
                    .stroke(
                        AppColors.primaryBlue, style: StrokeStyle(lineWidth: 6, lineCap: .round)
                    )
                    .rotationEffect(.degrees(-90))
                Image(systemName: "checkmark")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(AppColors.primaryBlue)
            }
            .frame(width: 54, height: 54)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 5) {
                Text("profile.overview.todoRate")
                    .font(.caption)
                    .foregroundStyle(AppColors.textSecondary)
                if let overview, let rate = overview.monthlyTodoCompletionRate {
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Text(
                            verbatim: rate.formatted(
                                .percent.precision(.fractionLength(0)).locale(locale))
                        )
                        .font(.title2.weight(.semibold).monospacedDigit())
                        .foregroundStyle(AppColors.textPrimary)
                        Text(
                            verbatim:
                                "\(overview.monthlyCompletedTodos)/\(overview.monthlyTodoCount)"
                        )
                        .font(.caption)
                        .foregroundStyle(AppColors.textSecondary)
                    }
                } else if overview != nil {
                    Text("profile.overview.noTodos")
                        .font(.subheadline)
                        .foregroundStyle(AppColors.textSecondary)
                } else {
                    Text(verbatim: "—")
                        .font(.title2.weight(.semibold))
                        .foregroundStyle(AppColors.textPrimary)
                }
            }
            Spacer(minLength: 0)
            Image(systemName: "calendar")
                .font(.body.weight(.light))
                .foregroundStyle(AppColors.textTertiary)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 12)
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
    }

    private func accent(_ color: Color) -> some View {
        Capsule()
            .fill(color.opacity(0.8))
            .frame(width: 26, height: 3)
            .accessibilityHidden(true)
    }

    private func metric(
        title: LocalizedStringKey,
        value: String?,
        color: Color,
        prominent: Bool = false,
        growthRate: Double? = nil,
        growthInverse: Bool = false
    ) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(AppColors.textSecondary)
            HStack(alignment: .lastTextBaseline, spacing: 6) {
                Text(verbatim: value ?? "—")
                    .font(
                        prominent
                            ? .title.weight(.semibold).monospacedDigit()
                            : .title3.weight(.semibold).monospacedDigit()
                    )
                    .foregroundStyle(color)
                    .lineLimit(1)
                    .minimumScaleFactor(0.65)
                if let rate = growthRate {
                    growthBadge(rate: rate, growthInverse: growthInverse)
                }
            }
        }
        .accessibilityElement(children: .combine)
    }

    private func growthBadge(
        rate: Double,
        growthInverse: Bool = false
    ) -> some View {
        let isPositive = rate > 0.0001
        let isNegative = rate < -0.0001
        let isGood = growthInverse ? isNegative : isPositive
        let isBad = growthInverse ? isPositive : isNegative

        let tintColor: Color =
            isGood
            ? Color(red: 0.16, green: 0.65, blue: 0.40)
            : (isBad ? Color(red: 0.95, green: 0.38, blue: 0.32) : AppColors.textTertiary)

        let percentString = abs(rate).formatted(
            .percent.precision(.fractionLength(1)).locale(locale))
        let symbol = isPositive ? "arrow.up" : (isNegative ? "arrow.down" : "minus")
        let sign = isPositive ? "+" : (isNegative ? "-" : "")

        return HStack(spacing: 2) {
            Image(systemName: symbol)
                .font(.system(size: 8, weight: .bold))
            Text("profile.overview.vsLastMonth")
                .font(.system(size: 10, weight: .regular))
            Text(verbatim: "\(sign)\(percentString)")
                .font(.system(size: 10, weight: .semibold).monospacedDigit())
        }
        .foregroundStyle(tintColor)
    }
}
