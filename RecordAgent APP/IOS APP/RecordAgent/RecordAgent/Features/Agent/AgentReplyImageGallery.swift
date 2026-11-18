import ImageIO
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// 通过鉴权读图，点击缩略图后以居中模态框展示并支持缩放、导出和分享。
struct AgentReplyImageGallery: View {
    let attachments: [AgentReplyImage]
    let load: (String) async throws -> Data
    @State private var preview: Preview?

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(attachments) { attachment in
                    Thumbnail(attachment: attachment, load: load) { image, data in
                        preview = Preview(attachment: attachment, image: image, data: data)
                    }
                }
            }
        }
        .scrollIndicators(.hidden)
        .background {
            CenteredModalPresenter(item: $preview) { item in
                PreviewModalView(item: item) {
                    preview = nil
                }
            }
        }
    }

    private struct Preview: Identifiable {
        var id: String { attachment.id }
        let attachment: AgentReplyImage
        let image: UIImage
        let data: Data

        var contentType: UTType {
            guard let source = CGImageSourceCreateWithData(data as CFData, nil),
                  let identifier = CGImageSourceGetType(source) else { return .image }
            return UTType(identifier as String) ?? .image
        }
    }

    /// 通过透明全屏淡入承载居中模态框，避免系统 sheet 自下而上的抽屉弹出动画。
    private struct CenteredModalPresenter<Item: Identifiable, ModalContent: View>: UIViewControllerRepresentable {
        @Binding var item: Item?
        @Environment(\.accessibilityReduceMotion) private var reduceMotion
        let content: (Item) -> ModalContent

        func makeCoordinator() -> Coordinator {
            Coordinator(parent: self)
        }

        func makeUIViewController(context: Context) -> UIViewController {
            let host = UIViewController()
            host.view.backgroundColor = .clear
            host.view.isUserInteractionEnabled = false
            return host
        }

        func updateUIViewController(_ uiViewController: UIViewController, context: Context) {
            context.coordinator.parent = self
            context.coordinator.syncPresentation(from: uiViewController, item: item, animated: !reduceMotion)
        }

        static func dismantleUIViewController(_ uiViewController: UIViewController, coordinator: Coordinator) {
            coordinator.dismissIfNeeded(animated: false)
        }

        final class Coordinator {
            var parent: CenteredModalPresenter
            private var presentedController: UIHostingController<ModalContent>?
            private var presentedId: Item.ID?

            init(parent: CenteredModalPresenter) {
                self.parent = parent
            }

            func syncPresentation(from host: UIViewController, item: Item?, animated: Bool) {
                if let item {
                    if let presentedController, presentedId == item.id {
                        presentedController.rootView = parent.content(item)
                        return
                    }
                    dismissIfNeeded(animated: false)
                    guard let presenter = topViewController(from: host) else { return }
                    let controller = UIHostingController(rootView: parent.content(item))
                    controller.modalPresentationStyle = .overFullScreen
                    controller.modalTransitionStyle = .crossDissolve
                    controller.view.backgroundColor = .clear
                    presentedController = controller
                    presentedId = item.id
                    presenter.present(controller, animated: animated)
                } else {
                    dismissIfNeeded(animated: animated)
                }
            }

            func dismissIfNeeded(animated: Bool) {
                guard let controller = presentedController else { return }
                presentedController = nil
                presentedId = nil
                if controller.presentingViewController != nil {
                    controller.dismiss(animated: animated)
                }
            }

            private func topViewController(from host: UIViewController) -> UIViewController? {
                var current = host.view.window?.rootViewController ?? host
                while let next = current.presentedViewController, !next.isBeingDismissed {
                    current = next
                }
                return current
            }
        }
    }

    private struct Thumbnail: View {
        let attachment: AgentReplyImage
        let load: (String) async throws -> Data
        let open: (UIImage, Data) -> Void
        @State private var loaded: (image: UIImage, data: Data)?
        @State private var failed = false
        @State private var attempt = 0

        var body: some View {
            Group {
                if let loaded {
                    Button {
                        open(loaded.image, loaded.data)
                    } label: {
                        Image(uiImage: loaded.image)
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 224, maxHeight: 172)
                            .clipShape(.rect(cornerRadius: 10))
                            .accessibilityLabel(Text(verbatim: attachment.title))
                    }
                    .buttonStyle(.plain)
                } else if failed {
                    Button("agent.image.reply.retry") { attempt += 1 }
                        .font(.caption)
                        .frame(minHeight: 44)
                } else {
                    ProgressView()
                        .frame(width: 88, height: 64)
                        .accessibilityLabel("agent.image.reply.loading")
                }
            }
            .task(id: attempt) {
                guard loaded == nil else { return }
                failed = false
                do {
                    let data = try await load(attachment.fileId)
                    try Task.checkCancellation()
                    guard let image = UIImage(data: data) else { throw AgentReplyImageError.unavailable }
                    loaded = (image, data)
                } catch {
                    if !Task.isCancelled { failed = true }
                }
            }
        }
    }

    private struct PreviewModalView: View {
        let item: Preview
        let onClose: () -> Void
        @State private var zoom = 1.0
        @GestureState private var pinch = 1.0
        @State private var exporting = false
        @State private var exportFailed = false

        private var effectiveZoom: Double {
            min(5, max(1, zoom * pinch))
        }

        private var showChrome: Bool {
            effectiveZoom <= 1.05
        }

        var body: some View {
            GeometryReader { outer in
                let imageRatio = item.image.size.height > 0 ? (item.image.size.width / item.image.size.height) : 1.0
                let availableWidth = max(1, outer.size.width - 32)
                let availableHeight = max(1, outer.size.height - outer.safeAreaInsets.top - outer.safeAreaInsets.bottom - 140)
                let baseWidth: CGFloat = {
                    if imageRatio <= 0 { return availableWidth }
                    let widthForMaxHeight = availableHeight * imageRatio
                    return min(availableWidth, widthForMaxHeight)
                }()
                let baseHeight = max(1, baseWidth / max(imageRatio, 0.1))

                ZStack {
                    Color.black.opacity(0.92)
                        .ignoresSafeArea()
                        .contentShape(Rectangle())
                        .onTapGesture {
                            if effectiveZoom > 1.05 {
                                withAnimation(.easeOut(duration: 0.2)) { zoom = 1.0 }
                            } else {
                                onClose()
                            }
                        }

                    ScrollView([.horizontal, .vertical]) {
                        ZStack {
                            Image(uiImage: item.image)
                                .resizable()
                                .scaledToFit()
                                .frame(width: baseWidth * effectiveZoom, height: baseHeight * effectiveZoom)
                                .clipShape(.rect(cornerRadius: effectiveZoom > 1.05 ? 0 : 12, style: .continuous))
                                .shadow(color: .black.opacity(0.45), radius: 24, y: 8)
                                .onTapGesture(count: 2) {
                                    withAnimation(.easeOut(duration: 0.2)) {
                                        zoom = zoom > 1 ? 1 : 2
                                    }
                                }
                                .gesture(MagnifyGesture()
                                    .updating($pinch) { value, state, _ in state = value.magnification }
                                    .onEnded { value in zoom = min(5, max(1, zoom * value.magnification)) })
                                .accessibilityLabel(Text(verbatim: item.attachment.title))
                        }
                        .frame(minWidth: outer.size.width, minHeight: outer.size.height)
                    }
                    .scrollIndicators(.hidden)

                    VStack {
                        HStack {
                            Button(action: onClose) {
                                Image(systemName: "xmark")
                                    .font(.body.weight(.semibold))
                                    .foregroundStyle(.white)
                                    .frame(width: 36, height: 36)
                                    .background(.ultraThinMaterial, in: Circle())
                                    .overlay {
                                        Circle()
                                            .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.5)
                                    }
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("agent.image.reply.close")

                            Spacer()
                        }
                        .padding(.horizontal, 18)
                        .padding(.top, 10)

                        Spacer()

                        if showChrome {
                            VStack(spacing: 12) {
                                let title = item.attachment.title.trimmingCharacters(in: .whitespacesAndNewlines)
                                if !title.isEmpty {
                                    Text(verbatim: title)
                                        .font(.footnote)
                                        .foregroundStyle(.white.opacity(0.82))
                                        .lineLimit(2)
                                        .multilineTextAlignment(.center)
                                        .padding(.horizontal, 28)
                                }

                                HStack(spacing: 2) {
                                    Button {
                                        exporting = true
                                    } label: {
                                        Label("agent.image.reply.save", systemImage: "square.and.arrow.down")
                                            .font(.footnote.weight(.medium))
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 10)
                                    }
                                    .buttonStyle(.plain)

                                    Divider()
                                        .frame(height: 16)
                                        .overlay(Color.white.opacity(0.2))

                                    ShareLink(
                                        item: Image(uiImage: item.image),
                                        preview: SharePreview(item.attachment.title, image: Image(uiImage: item.image))
                                    ) {
                                        Image(systemName: "square.and.arrow.up")
                                            .font(.footnote.weight(.medium))
                                            .padding(.horizontal, 14)
                                            .padding(.vertical, 10)
                                    }

                                    if let source = item.attachment.source {
                                        Divider()
                                            .frame(height: 16)
                                            .overlay(Color.white.opacity(0.2))

                                        Link(destination: source) {
                                            Label("agent.image.reply.source", systemImage: "safari")
                                                .font(.footnote.weight(.medium))
                                                .padding(.horizontal, 14)
                                                .padding(.vertical, 10)
                                        }
                                    }
                                }
                                .foregroundStyle(.white)
                                .background(.ultraThinMaterial, in: Capsule())
                                .overlay {
                                    Capsule()
                                        .strokeBorder(Color.white.opacity(0.16), lineWidth: 0.5)
                                }
                            }
                            .padding(.bottom, 14)
                            .transition(.opacity)
                        }
                    }
                    .environment(\.colorScheme, .dark)
                }
            }
            .fileExporter(isPresented: $exporting, document: ImageFile(data: item.data),
                          contentType: item.contentType, defaultFilename: "chat-image") { result in
                if case .failure = result { exportFailed = true }
            }
            .alert("agent.image.reply.save_error", isPresented: $exportFailed) {
                Button("agent.image.reply.close", role: .cancel) {}
            }
        }
    }

    private struct ImageFile: FileDocument {
        static let readableContentTypes: [UTType] = [.image]
        let data: Data

        init(data: Data) { self.data = data }
        init(configuration: ReadConfiguration) throws {
            guard let data = configuration.file.regularFileContents else {
                throw AgentReplyImageError.unavailable
            }
            self.data = data
        }

        func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
            FileWrapper(regularFileWithContents: data)
        }
    }
}
