import SwiftUI

/// 内置分类是选择元数据，选中后沿用原有 API 保存；不覆盖用户的自定义分类。
struct FinanceCategoryPreset: Identifiable {
    let name: String
    let symbol: String
    let key: String
    var id: String { name }
}

enum FinanceCategoryCatalog {
    static let expenses: [FinanceCategoryPreset] = [
        .init(name: "餐饮", symbol: "fork.knife", key: "food"),
        .init(name: "交通", symbol: "tram.fill", key: "transport"),
        .init(name: "奶茶", symbol: "mug.fill", key: "tea"),
        .init(name: "咖啡", symbol: "cup.and.saucer.fill", key: "coffee"),
        .init(name: "购物", symbol: "bag.fill", key: "shopping"),
        .init(name: "居住", symbol: "house.fill", key: "housing"),
        .init(name: "娱乐", symbol: "gamecontroller.fill", key: "entertainment"),
        .init(name: "旅行", symbol: "airplane", key: "travel"),
        .init(name: "医疗", symbol: "cross.case.fill", key: "health"),
        .init(name: "教育", symbol: "graduationcap.fill", key: "education"),
        .init(name: "通讯", symbol: "iphone", key: "phone"),
        .init(name: "其他", symbol: "tag.fill", key: "other"),
    ]
    static let incomes: [FinanceCategoryPreset] = [
        .init(name: "工资", symbol: "banknote.fill", key: "salary"),
        .init(name: "奖金", symbol: "gift.fill", key: "bonus"),
        .init(name: "兼职", symbol: "briefcase.fill", key: "freelance"),
        .init(name: "理财", symbol: "chart.line.uptrend.xyaxis", key: "investment"),
        .init(name: "退款", symbol: "arrow.uturn.backward.circle.fill", key: "refund"),
        .init(name: "其他收入", symbol: "plus.circle.fill", key: "other_income"),
    ]
    static func presets(type: String) -> [FinanceCategoryPreset] { type == "income" ? incomes : expenses }
    static func symbol(category: String, type: String) -> String {
        let name = category.trimmingCharacters(in: .whitespacesAndNewlines)
        if ["地铁", "公交", "出行"].contains(name) { return "tram.fill" }
        return presets(type: type).first { $0.name == name }?.symbol
            ?? (type == "income" ? "banknote.fill" : "tag.fill")
    }
}
