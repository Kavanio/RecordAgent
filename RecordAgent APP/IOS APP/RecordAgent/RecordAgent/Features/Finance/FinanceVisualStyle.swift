import SwiftUI

/// 收支语义色与稳定分类色统一用于摘要、流水、图表及详情。
enum FinanceVisualStyle {
    // 活力珊瑚朱砂红（支出）：告别沉闷灰蓝，醒目有张力，符合消费与资金流出心理
    static let expense = adaptive(
        light: (0.95, 0.31, 0.26),  // #F24F42
        dark: (1.0, 0.42, 0.38)     // #FF6B61
    )
    
    // 清透流光翡翠莹绿（收入）：生机盎然，通透鲜明
    static let income = adaptive(
        light: (0.0, 0.73, 0.48),   // #00BA7A
        dark: (0.19, 0.82, 0.55)    // #30D18C
    )
    
    // 尊贵皇家电光蓝（资产与品牌强调）：沉稳深邃、尊荣财富感
    static let accent = adaptive(
        light: (0.12, 0.38, 0.94),  // #1F61F0
        dark: (0.35, 0.58, 1.0)     // #5994FF
    )

    /// 浅色保证文字对比，深色提升亮度；收支与分类映射保持稳定。
    private static func adaptive(light: (Double, Double, Double), dark: (Double, Double, Double)) -> Color {
        Color(uiColor: UIColor { traits in
            let value = traits.userInterfaceStyle == .dark ? dark : light
            return UIColor(red: value.0, green: value.1, blue: value.2, alpha: 1)
        })
    }

    /// 12 色高明度、高辨识度设计师现代生活调色板
    static let palette: [Color] = [
        adaptive(light: (1.0, 0.48, 0.0), dark: (1.0, 0.63, 0.26)),    // 0. 暖橙焦糖（美食）
        adaptive(light: (0.0, 0.48, 1.0), dark: (0.25, 0.60, 1.0)),    // 1. 蔚蓝电光（交通）
        adaptive(light: (0.52, 0.26, 0.88), dark: (0.65, 0.42, 0.95)),  // 2. 优雅紫罗兰（购物）
        adaptive(light: (0.97, 0.31, 0.62), dark: (0.98, 0.45, 0.70)),  // 3. 珊瑚玫瑰粉（娱乐）
        adaptive(light: (0.98, 0.55, 0.09), dark: (1.0, 0.69, 0.30)),    // 4. 暖金琥珀（居住/家居）
        adaptive(light: (0.0, 0.66, 0.66), dark: (0.21, 0.81, 0.79)),   // 5. 绿松石青（旅行/出行）
        adaptive(light: (0.60, 0.42, 0.26), dark: (0.73, 0.55, 0.39)),  // 6. 馥郁奶咖（奶茶/咖啡）
        adaptive(light: (0.95, 0.31, 0.26), dark: (1.0, 0.42, 0.38)),   // 7. 治愈朱砂（医疗）
        adaptive(light: (0.18, 0.33, 0.92), dark: (0.35, 0.49, 0.97)),  // 8. 智慧群青（教育）
        adaptive(light: (0.07, 0.76, 0.76), dark: (0.36, 0.86, 0.83)),  // 9. 极客数码青（数码/通讯）
        adaptive(light: (0.0, 0.73, 0.48), dark: (0.19, 0.82, 0.55)),   // 10. 生机翡翠（工资/成长）
        adaptive(light: (0.83, 0.53, 0.02), dark: (0.98, 0.74, 0.08)),  // 11. 辉煌纯金（理财/投资）
    ]

    /// 智能语义色彩映射：根据生活分类名称赋予专属高辨识度性格色，未匹配时按哈希散列
    static func category(_ name: String, type: String = "expense") -> Color {
        let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
        if type == "income" {
            switch trimmed {
            case "工资": return palette[10] // 生机翡翠
            case "奖金": return palette[0]  // 暖橙焦糖
            case "兼职": return palette[1]  // 蔚蓝电光
            case "理财": return palette[11] // 辉煌纯金
            case "退款": return palette[5]  // 绿松石青
            default: return income
            }
        }

        switch trimmed {
        case "餐饮", "美食", "外卖", "买菜", "吃":
            return palette[0]  // 暖橙焦糖
        case "交通", "地铁", "公交", "出行", "打车", "加油":
            return palette[1]  // 蔚蓝电光
        case "奶茶", "咖啡", "下午茶":
            return palette[6]  // 馥郁奶咖
        case "购物", "日用", "买", "衣服", "百货":
            return palette[2]  // 优雅紫罗兰
        case "居住", "房租", "水电", "物业":
            return palette[4]  // 暖金琥珀
        case "娱乐", "游戏", "电影", "休闲", "K歌":
            return palette[3]  // 珊瑚玫瑰粉
        case "旅行", "酒店", "机票", "门票":
            return palette[5]  // 绿松石青
        case "医疗", "药品", "挂号", "体检":
            return palette[7]  // 治愈朱砂
        case "教育", "学习", "买书", "培训":
            return palette[8]  // 智慧群青
        case "通讯", "话费", "网费", "数码":
            return palette[9]  // 极客数码青
        default:
            let hash = trimmed.utf8.reduce(UInt64(5381)) { ($0 &* 33) &+ UInt64($1) }
            return palette[Int(hash % UInt64(palette.count))]
        }
    }
}

/// 流水与分类微光方舱：提供精致饱满的柔光背景与高饱和度图标
struct FinanceCategoryBadge: View {
    let category: String
    let type: String
    var size: CGFloat = 40
    var cornerRadius: CGFloat = 12

    var body: some View {
        let color = FinanceVisualStyle.category(category, type: type)
        let symbol = FinanceCategoryCatalog.symbol(category: category, type: type)
        ZStack {
            RoundedRectangle(cornerRadius: cornerRadius, style: .continuous)
                .fill(color.opacity(0.13))
            Image(systemName: symbol)
                .font(.system(size: size * 0.44, weight: .semibold))
                .foregroundStyle(color)
        }
        .frame(width: size, height: size)
    }
}

extension FinanceChartRange {
    var title: LocalizedStringKey {
        switch self {
        case .week: return "finance.range.week"
        case .month: return "finance.range.month"
        case .year: return "finance.range.year"
        }
    }
}

/// 图表沿用页面白色画布，保留全部内容宽度，不再嵌套底色卡片。
struct FinanceChartSection<Content: View>: View {
    let title: LocalizedStringKey
    let symbol: String
    @ViewBuilder let content: () -> Content
    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(spacing: 10) {
                Image(systemName: symbol).foregroundStyle(FinanceVisualStyle.accent).accessibilityHidden(true)
                Text(title)
            }.font(.headline)
            content()
        }
        .frame(maxWidth: .infinity, alignment: .leading)

    }
}

/// 金额优先完整显示；名称和大额在窄屏无法同行时自动改为上下布局。
struct FinanceChartValueRow<Title: View>: View {
    let amount: String
    @ViewBuilder let title: () -> Title
    @Environment(\.dynamicTypeSize) private var typeSize
    var body: some View {
        if typeSize.isAccessibilitySize {
            vertical
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    title().font(.subheadline)
                    Spacer(minLength: 4)
                    Text(amount).font(.subheadline.weight(.semibold)).monospacedDigit().fixedSize()
                }
                vertical
            }
        }
    }
    private var vertical: some View {
        VStack(alignment: .leading, spacing: 6) {
            title().font(.subheadline)
            Text(amount).font(.subheadline.weight(.semibold)).monospacedDigit()
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}
