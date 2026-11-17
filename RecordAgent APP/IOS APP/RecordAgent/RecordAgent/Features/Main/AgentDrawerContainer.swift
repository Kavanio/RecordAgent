import SwiftUI

/// 可向安全区上下外延的局部圆角卡片形状：
/// 让处于标准安全区内的主页面在展开抽屉时，从物理屏幕最顶端到最底端（含状态栏与 Home Indicator 区域）
/// 均能呈现出对称的左上/左下圆角、弧形阴影与细描边，同时绝不改动任何业务页面的系统安全区环境。
private struct ExtendedDrawerCardShape: Shape {
    var topExtension: CGFloat
    var bottomExtension: CGFloat
    var cornerRadius: CGFloat

    var animatableData: AnimatablePair<CGFloat, AnimatablePair<CGFloat, CGFloat>> {
        get { AnimatablePair(cornerRadius, AnimatablePair(topExtension, bottomExtension)) }
        set {
            cornerRadius = newValue.first
            topExtension = newValue.second.first
            bottomExtension = newValue.second.second
        }
    }

    func path(in rect: CGRect) -> Path {
        let extendedRect = CGRect(
            x: rect.minX,
            y: rect.minY - topExtension,
            width: rect.width,
            height: rect.height + topExtension + bottomExtension
        )
        return UnevenRoundedRectangle(
            topLeadingRadius: cornerRadius,
            bottomLeadingRadius: cornerRadius,
            bottomTrailingRadius: 0,
            topTrailingRadius: 0,
            style: .continuous
        )
        .path(in: extendedRect)
    }
}

/// 高性能侧滑抽屉容器：
/// - 以值属性持有 `main` 与 `drawer`，并隔离拖拽高频状态，避免手指拖动每帧重算聊天或列表树；
/// - 保持业务视图位于标准系统安全区内（零 Frame 1 跳变、不破坏键盘避让与各页安全区）；
/// - 使用全局坐标系 `.global` 与左边缘静止热区消除平滑移动时的坐标反馈抖动。
struct AgentDrawerContainer<MainContent: View, DrawerContent: View>: View {
    let isEnabled: Bool
    @Binding var isOpen: Bool
    let drawer: DrawerContent
    let main: MainContent

    @State private var dragTranslation: CGFloat?
    @State private var isDragging = false
    @State private var fullHeight: CGFloat = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(
        isEnabled: Bool,
        isOpen: Binding<Bool>,
        @ViewBuilder drawer: () -> DrawerContent,
        @ViewBuilder main: () -> MainContent
    ) {
        self.isEnabled = isEnabled
        self._isOpen = isOpen
        self.drawer = drawer()
        self.main = main()
    }

    private var drawerAnimation: Animation? {
        reduceMotion ? nil : .spring(response: 0.34, dampingFraction: 0.88)
    }

    var body: some View {
        ZStack {
            // 后台探针：仅用于获取包含底部 Home Indicator 区域的总高度（保留键盘避让）
            GeometryReader { fullProxy in
                Color.clear
                    .onAppear { updateFullHeight(fullProxy.size.height) }
                    .onChange(of: fullProxy.size.height) { _, newHeight in
                        updateFullHeight(newHeight)
                    }
            }
            .ignoresSafeArea(.container, edges: .all)

            GeometryReader { safeProxy in
                let safeFrame = safeProxy.frame(in: .global)
                let topExtension = max(safeFrame.minY, 0)
                let bottomExtension = fullHeight > 0
                    ? max(fullHeight - safeFrame.maxY, 0)
                    : (topExtension > 40 ? 34 : 0)

                let drawerWidth = min(max(safeProxy.size.width * 0.78, 268), 328)
                let baseOffset: CGFloat = (isEnabled && isOpen) ? drawerWidth : 0
                let currentOffset: CGFloat = isEnabled
                    ? min(max(baseOffset + (dragTranslation ?? 0), 0), drawerWidth)
                    : 0
                let progress: CGFloat = drawerWidth > 0 ? currentOffset / drawerWidth : 0
                let cornerRadius: CGFloat = 44 * progress
                let cardShape = ExtendedDrawerCardShape(
                    topExtension: topExtension,
                    bottomExtension: bottomExtension,
                    cornerRadius: cornerRadius
                )

                ZStack(alignment: .leading) {
                    if isEnabled {
                        drawer
                            .frame(width: drawerWidth, height: safeProxy.size.height)
                            .clipped()
                            .offset(x: -drawerWidth * 0.14 * (1 - progress))
                            .opacity(progress > 0.001 ? (0.55 + 0.45 * progress) : 0)
                            .allowsHitTesting(isOpen && dragTranslation == nil)
                            .accessibilityHidden(!isOpen)
                    }

                    ZStack {
                        cardShape
                            .fill(Color(uiColor: .systemBackground))
                            .shadow(
                                color: progress > 0.001 ? Color.black.opacity(0.16 * progress) : .clear,
                                radius: progress > 0.001 ? 22 : 0,
                                x: -6,
                                y: 0
                            )

                        main
                            .frame(width: safeProxy.size.width, height: safeProxy.size.height)
                            .overlay {
                                ExtendedDrawerCardShape(
                                    topExtension: topExtension,
                                    bottomExtension: bottomExtension,
                                    cornerRadius: cornerRadius
                                )
                                .fill(Color.black.opacity(0.05 * progress))
                                .contentShape(cardShape)
                                .allowsHitTesting(isEnabled && (isOpen || isDragging))
                                .onTapGesture {
                                    closeDrawer()
                                }
                                .gesture(closeDragGesture(drawerWidth: drawerWidth))
                            }
                            .clipShape(cardShape)

                        cardShape
                            .stroke(
                                Color(uiColor: .separator).opacity(0.30 * progress),
                                lineWidth: 0.5
                            )
                            .allowsHitTesting(false)
                    }
                    .frame(width: safeProxy.size.width, height: safeProxy.size.height)
                    .offset(x: currentOffset)

                    // 左边缘静止热区：仅在对话页且抽屉关闭时激活，不与列表/表格横向滚动冲突
                    if isEnabled && !isOpen {
                        Color.clear
                            .frame(width: 28, height: safeProxy.size.height)
                            .contentShape(Rectangle())
                            .gesture(openEdgeDragGesture(drawerWidth: drawerWidth))
                    }
                }
                .frame(width: safeProxy.size.width, height: safeProxy.size.height)
                .transaction { transaction in
                    if isDragging {
                        transaction.animation = nil
                    }
                }
            }
        }
        .background(Color(uiColor: .systemBackground).ignoresSafeArea(.container, edges: .all))
        .onChange(of: isEnabled) { _, enabled in
            if !enabled && (isOpen || dragTranslation != nil) {
                isDragging = false
                dragTranslation = nil
                isOpen = false
            }
        }
        .onChange(of: isOpen) { _, newValue in
            if newValue {
                dismissKeyboard()
            }
        }
    }

    private func updateFullHeight(_ height: CGFloat) {
        guard height > 0, abs(fullHeight - height) > 0.5 else { return }
        fullHeight = height
    }

    private func closeDrawer() {
        dismissKeyboard()
        isDragging = false
        withAnimation(drawerAnimation) {
            isOpen = false
            dragTranslation = nil
        }
    }

    private func dismissKeyboard() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }

    private func openEdgeDragGesture(drawerWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .onChanged { value in
                guard isEnabled, !isOpen else { return }
                if !isDragging {
                    guard value.translation.width > 0,
                          abs(value.translation.width) >= abs(value.translation.height) else {
                        return
                    }
                    isDragging = true
                    dismissKeyboard()
                }
                dragTranslation = min(max(value.translation.width, 0), drawerWidth)
            }
            .onEnded { value in
                guard isDragging else { return }
                let current = dragTranslation ?? 0
                let predicted = value.predictedEndTranslation.width
                let shouldOpen = current > drawerWidth * 0.30 || predicted > drawerWidth * 0.45
                isDragging = false
                withAnimation(drawerAnimation) {
                    isOpen = shouldOpen
                    dragTranslation = nil
                }
            }
    }

    private func closeDragGesture(drawerWidth: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .onChanged { value in
                guard isEnabled, isOpen else { return }
                if !isDragging {
                    guard value.translation.width < 0,
                          abs(value.translation.width) >= abs(value.translation.height) else {
                        return
                    }
                    isDragging = true
                    dismissKeyboard()
                }
                dragTranslation = max(min(value.translation.width, 0), -drawerWidth)
            }
            .onEnded { value in
                guard isDragging else { return }
                let current = dragTranslation ?? 0
                let predicted = value.predictedEndTranslation.width
                let shouldClose = current < -drawerWidth * 0.25 || predicted < -drawerWidth * 0.40
                isDragging = false
                withAnimation(drawerAnimation) {
                    isOpen = !shouldClose
                    dragTranslation = nil
                }
            }
    }
}
