import SwiftUI

enum CalendarPalette {
    static let ink = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.87, green: 0.9, blue: 1, alpha: 1)
                : UIColor(red: 0.06, green: 0.1, blue: 0.29, alpha: 1)
        })
    static let secondary = Color(
        uiColor: UIColor { traits in
            traits.userInterfaceStyle == .dark
                ? UIColor(red: 0.63, green: 0.69, blue: 0.84, alpha: 1)
                : UIColor(red: 0.44, green: 0.51, blue: 0.69, alpha: 1)
        })
    static let accent = Color(red: 0.16, green: 0.53, blue: 1)
    static let paperInk = Color(red: 0.08, green: 0.13, blue: 0.25)
}

extension HomeEntry.Kind {
    /// 时间线用形状区分类型，颜色只作辅助；每种业务记录保持唯一图标。
    var calendarSymbol: String {
        switch self {
        case .todo: return "checkmark"
        case .schedule: return "calendar"
        case .focus: return "book.closed.fill"
        case .document: return "doc.text.fill"
        case .photo: return "camera.fill"
        case .expense: return "arrow.up.right"
        case .income: return "arrow.down.left"
        }
    }

    var calendarColor: Color {
        switch self {
        case .todo, .income: return Color(red: 0.29, green: 0.69, blue: 0.43)
        case .schedule: return CalendarPalette.accent
        case .focus: return Color(red: 0.97, green: 0.34, blue: 0.31)
        case .document: return Color(red: 1, green: 0.62, blue: 0.16)
        case .photo: return Color(red: 0.57, green: 0.4, blue: 0.85)
        case .expense: return Color(red: 0.37, green: 0.65, blue: 1)
        }
    }
}

extension CalendarEntryDraft.Kind {
    var title: LocalizedStringKey {
        switch self {
        case .todo: return "calendar.create.todo"
        case .schedule: return "calendar.create.schedule"
        case .note: return "calendar.create.note"
        }
    }
}

extension CalendarTodoPeriod {
    var title: LocalizedStringKey { self == .weekly ? "calendar.week.todos" : "calendar.month.todos" }
}
