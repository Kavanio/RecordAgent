import PhotosUI
import SwiftUI
import UIKit

/// 昵称与两张图片一次保存；图片选择后先预览，保存成功才关闭页面。
struct ProfileEditView: View {
    let user: AuthUser
    let current: ProfileAppearance
    let onSave: (ProfileAppearance) async -> Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.locale) private var locale
    @State private var nickname: String
    @State private var avatarData: Data?
    @State private var coverData: Data?
    @State private var avatarSelection: PhotosPickerItem?
    @State private var coverSelection: PhotosPickerItem?
    @State private var isSaving = false
    @State private var imageError = false
    @State private var saveFailed = false

    init(
        user: AuthUser, current: ProfileAppearance,
        onSave: @escaping (ProfileAppearance) async -> Bool
    ) {
        self.user = user
        self.current = current
        self.onSave = onSave
        _nickname = State(initialValue: current.nickname ?? user.nickname ?? "")
        _avatarData = State(initialValue: current.avatarData)
        _coverData = State(initialValue: current.coverData)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("profile.edit.nickname") {
                    TextField(
                        "profile.edit.nicknamePlaceholder", text: $nickname,
                        prompt: Text(
                            verbatim: user.profileDisplayName(override: nil, locale: locale))
                    )
                    .textInputAutocapitalization(.words)
                    .autocorrectionDisabled()
                    .onChange(of: nickname) { _, value in
                        if value.count > 32 { nickname = String(value.prefix(32)) }
                    }
                    Text("profile.edit.nicknameHint")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section("profile.edit.images") {
                    PhotosPicker(selection: $avatarSelection, matching: .images) {
                        HStack(spacing: 12) {
                            thumbnail(avatarData, size: CGSize(width: 42, height: 42), circle: true)
                            Text("profile.edit.avatar")
                        }
                        .frame(minHeight: 48)
                    }
                    if avatarData != nil {
                        Button("profile.edit.removeAvatar", role: .destructive) { avatarData = nil }
                    }
                    PhotosPicker(selection: $coverSelection, matching: .images) {
                        HStack(spacing: 12) {
                            thumbnail(coverData, size: CGSize(width: 64, height: 42), circle: false)
                            Text("profile.edit.cover")
                        }
                        .frame(minHeight: 48)
                    }
                    if coverData != nil {
                        Button("profile.edit.removeCover", role: .destructive) { coverData = nil }
                    }
                }
                Section {
                    Text("profile.edit.localOnly")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("profile.edit.title")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("common.cancel") { dismiss() }.disabled(isSaving)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("common.save") { Task { await save() } }
                        .disabled(isSaving)
                }
            }
            .task(id: avatarSelection) { await importImage(avatarSelection, isCover: false) }
            .task(id: coverSelection) { await importImage(coverSelection, isCover: true) }
            .alert("profile.edit.imageFailed", isPresented: $imageError) {
                Button("common.confirm", role: .cancel) {}
            }
            .alert("profile.edit.saveFailed", isPresented: $saveFailed) {
                Button("common.confirm", role: .cancel) {}
            }
        }
    }

    @ViewBuilder private func thumbnail(_ data: Data?, size: CGSize, circle: Bool) -> some View {
        Group {
            if let data, let image = UIImage(data: data) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: circle ? "person.crop.circle" : "photo")
                    .resizable().scaledToFit().padding(9)
                    .foregroundStyle(.secondary)
                    .background(Color(uiColor: .secondarySystemBackground))
            }
        }
        .frame(width: size.width, height: size.height)
        .clipShape(RoundedRectangle(cornerRadius: circle ? size.height / 2 : 8))
    }

    private func importImage(_ selection: PhotosPickerItem?, isCover: Bool) async {
        guard let selection else { return }
        do {
            guard let data = try await selection.loadTransferable(type: Data.self),
                data.count <= 12_000_000,
                let image = UIImage(data: data),
                image.size.width > 0, image.size.height > 0,
                max(image.size.width, image.size.height) <= 20_000
            else { throw ProfileAppearanceError.invalidImage }
            let maxDimension: CGFloat = isCover ? 1600 : 600
            let scale = min(1, maxDimension / max(image.size.width, image.size.height))
            let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
            let jpeg = UIGraphicsImageRenderer(size: size).jpegData(withCompressionQuality: 0.78) {
                _ in
                image.draw(in: CGRect(origin: .zero, size: size))
            }
            guard jpeg.count <= 2_000_000 else { throw ProfileAppearanceError.imageTooLarge }
            if isCover { coverData = jpeg } else { avatarData = jpeg }
        } catch is CancellationError {
            return
        } catch {
            imageError = true
        }
    }

    private func save() async {
        guard !isSaving else { return }
        isSaving = true
        defer { isSaving = false }
        let updated = ProfileAppearance(
            nickname: nickname.trimmingCharacters(in: .whitespacesAndNewlines),
            avatarData: avatarData,
            coverData: coverData)
        if await onSave(updated) { dismiss() } else { saveFailed = true }
    }
}
