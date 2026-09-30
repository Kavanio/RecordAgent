import SwiftUI

extension LibraryDocumentKind {
    var title: LocalizedStringKey {
        switch self {
        case .quick: return "library.type.quick"
        case .learning: return "library.type.learning"
        case .reading: return "library.type.reading"
        case .journal: return "library.type.journal"
        case .weeklyReview: return "library.type.weekly_review"
        }
    }
}

extension LibraryCollection {
    var title: LocalizedStringKey {
        switch self {
        case .all: return "library.collection.all"
        case .favorites: return "library.collection.favorites"
        case .trash: return "library.collection.trash"
        }
    }
}

struct LibraryItemRow: View {
    let item: LibraryItem

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Text(item.title).font(.body.weight(.medium)).lineLimit(2)
                Spacer(minLength: 8)
                if item.favorite {
                    Image(systemName: "star.fill").font(.caption).foregroundStyle(.secondary)
                        .accessibilityLabel("library.collection.favorites")
                }
            }
            if !item.excerpt.isEmpty {
                Text(item.excerpt).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
            }
            HStack(spacing: 12) {
                Text(item.type.title)
                if let date = WorkspaceFormat.date(item.date) {
                    Text(date, format: .dateTime.year().month(.abbreviated).day())
                }
                Spacer(minLength: 0)
            }.font(.caption).foregroundStyle(.secondary)
        }
        .padding(.vertical, 20)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}
