import SwiftUI

struct FinanceCategoryPicker: View {
    let type: String
    @Binding var category: String
    private var tintColor: Color {
        type == "income" ? FinanceVisualStyle.income : FinanceVisualStyle.expense
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("finance.category").font(.caption).foregroundStyle(.secondary)
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 76), spacing: 10)], spacing: 10) {
                ForEach(FinanceCategoryCatalog.presets(type: type)) { preset in
                    Button {
                        category = preset.name
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: preset.symbol).font(.title3)
                            Text(LocalizedStringKey("finance.category.preset." + preset.key))
                                .font(.caption).fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, minHeight: 64).padding(6)
                        .foregroundStyle(category == preset.name ? tintColor : .primary)
                        .background(
                            FinanceVisualStyle.category(preset.name).opacity(category == preset.name ? 0.16 : 0.06),
                            in: RoundedRectangle(cornerRadius: 14)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14).strokeBorder(
                                category == preset.name ? tintColor.opacity(0.5) : .clear))
                    }.buttonStyle(.plain).accessibilityAddTraits(category == preset.name ? .isSelected : [])
                }
            }
            WorkspaceTextField(title: "finance.category.custom", text: $category)
        }
        .onChange(of: type) { old, _ in
            if FinanceCategoryCatalog.presets(type: old).contains(where: { $0.name == category }) { category = "" }
        }
    }
}
