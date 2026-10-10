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
                    let catColor = FinanceVisualStyle.category(preset.name, type: type)
                    let isSelected = category == preset.name
                    Button {
                        category = preset.name
                    } label: {
                        VStack(spacing: 8) {
                            Image(systemName: preset.symbol)
                                .font(.title3.weight(isSelected ? .bold : .semibold))
                                .foregroundStyle(isSelected ? tintColor : catColor)
                            Text(LocalizedStringKey("finance.category.preset." + preset.key))
                                .font(.caption.weight(isSelected ? .semibold : .regular))
                                .foregroundStyle(isSelected ? tintColor : .primary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, minHeight: 64).padding(6)
                        .background(
                            catColor.opacity(isSelected ? 0.18 : 0.08),
                            in: RoundedRectangle(cornerRadius: 14, style: .continuous)
                        )
                        .overlay(
                            RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(
                                isSelected ? tintColor : Color.clear, lineWidth: 1.5))
                    }.buttonStyle(.plain).accessibilityAddTraits(isSelected ? .isSelected : [])
                }
            }
            WorkspaceTextField(title: "finance.category.custom", text: $category)
        }
        .onChange(of: type) { old, _ in
            if FinanceCategoryCatalog.presets(type: old).contains(where: { $0.name == category }) { category = "" }
        }
    }
}
