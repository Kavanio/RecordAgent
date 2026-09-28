import CoreMotion
import SwiftUI

/// 日历只展示周期待办摘要；点击整张便签进入完整列表。
struct CalendarStickyTodoNote: View {
    let group: CalendarTodoGroup
    let onOpen: () -> Void
    @ObservedObject var motion: CalendarNoteMotion
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false
    @State private var hasEntered = false
    @State private var isSwaying = false
    private var swayDirection: Double { group.period == .weekly ? 1 : -1 }
    private var motionEnabled: Bool { isVisible && scenePhase == .active && !reduceMotion }
    private var motionScale: Double { group.period == .weekly ? 1 : 0.85 }

    var body: some View {
        Button(action: onOpen) {
            StickyNotePaper { size in
                VStack(alignment: .leading, spacing: 3) {
                    HStack(alignment: .firstTextBaseline, spacing: 5) {
                        Text(group.period.title).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
                        Spacer(minLength: 0)
                        Text("\(group.completedCount)/\(group.entries.count)")
                            .font(.subheadline.monospacedDigit()).fixedSize()
                        Image(systemName: "arrow.up.left.and.arrow.down.right")
                            .font(.system(size: 10, weight: .medium)).opacity(0.58)
                    }
                    .frame(minHeight: 33)
                    ForEach(group.entries.prefix(2)) { entry in
                        HStack(alignment: .center, spacing: 7) {
                            Image(systemName: entry.isCompleted ? "checkmark.square" : "square")
                                .font(.system(size: 17, weight: .light))
                                .frame(width: 18)
                            Text(entry.title).font(.subheadline).lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .frame(minHeight: 27)
                    }
                    if group.entries.count > 2 {
                        Text("+\(group.entries.count - 2)")
                            .font(.caption.weight(.medium))
                            .frame(maxWidth: .infinity, alignment: .trailing)
                            .padding(.top, 1)
                    }
                }
                .padding(.horizontal, size.width * 0.085)
                .padding(.top, size.height * 0.22)
                .frame(width: size.width, height: size.height, alignment: .topLeading)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(group.period.title))
        .accessibilityValue(Text("\(group.completedCount)/\(group.entries.count)"))
        .accessibilityHint("calendar.note.expand")
        .rotationEffect(
            .degrees(motionEnabled ? (isSwaying ? 1.2 : -1) * swayDirection + motion.rotation * motionScale : 0),
            anchor: .top
        )
        .offset(x: motionEnabled ? (isSwaying ? 2 : -2) * swayDirection : 0)
        .opacity(hasEntered ? 1 : 0)
        .scaleEffect(hasEntered ? 1 : 0.92, anchor: .top)
        .offset(y: (hasEntered ? 0 : 28) + (motionEnabled ? motion.lift * motionScale : 0))
        .onAppear(perform: enter)
        .onDisappear(perform: stop)
        .onChange(of: reduceMotion) { _, value in
            if value { stopSway() } else if isVisible { startSway() }
        }
        .onChange(of: scenePhase) { _, value in
            if value == .active && isVisible { startSway() } else { stopSway() }
        }
    }

    private func enter() {
        isVisible = true
        withAnimation(reduceMotion ? nil : .spring(response: 0.55, dampingFraction: 0.8)) {
            hasEntered = true
        }
        startSway()
    }

    private func startSway() {
        guard !reduceMotion, scenePhase == .active, !isSwaying else { return }
        let duration = group.period == .weekly ? 3.8 : 4.6
        let delay = group.period == .weekly ? 0.7 : 1.1
        withAnimation(.easeInOut(duration: duration).repeatForever(autoreverses: true).delay(delay)) {
            isSwaying = true
        }
    }

    private func stopSway() {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { isSwaying = false }
    }

    private func stop() {
        isVisible = false
        stopSway()
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { hasEntered = false }
    }
}

/// 模态纸张直接读取 ViewModel 的周期实例，勾选后立即刷新进度与内容。
struct CalendarStickyTodoDetail: View {
    let period: CalendarTodoPeriod
    @ObservedObject var model: CalendarViewModel
    let savingIDs: Set<String>
    let onToggle: (HomeEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    private var group: CalendarTodoGroup? { model.groups.first { $0.period == period } }

    var body: some View {
        NavigationStack {
            ScrollView {
                if let group {
                    StickyNotePaper { size in
                        VStack(alignment: .leading, spacing: 15) {
                            HStack(alignment: .firstTextBaseline) {
                                Text(period.title).font(.title2.weight(.semibold))
                                Spacer()
                                Text("\(group.completedCount)/\(group.entries.count)")
                                    .font(.headline.monospacedDigit())
                            }
                            Rectangle().fill(CalendarPalette.paperInk.opacity(0.12)).frame(height: 1)
                            ScrollView {
                                LazyVStack(alignment: .leading, spacing: 14) {
                                    ForEach(group.entries) { entry in
                                        Button {
                                            onToggle(entry)
                                        } label: {
                                            HStack(alignment: .top, spacing: 12) {
                                                if savingIDs.contains(entry.id) {
                                                    ProgressView().controlSize(.small).frame(width: 24)
                                                } else {
                                                    Image(systemName: entry.isCompleted ? "checkmark.square.fill" : "square")
                                                        .font(.system(size: 23, weight: .light))
                                                        .frame(width: 24)
                                                }
                                                VStack(alignment: .leading, spacing: 5) {
                                                    Text(entry.title).font(.body.weight(.medium))
                                                        .strikethrough(entry.isCompleted)
                                                    .frame(maxWidth: .infinity, alignment: .leading)
                                                    if !entry.detail.isEmpty {
                                                        Text(entry.detail).font(.subheadline)
                                                            .foregroundStyle(CalendarPalette.paperInk.opacity(0.7))
                                                            .frame(maxWidth: .infinity, alignment: .leading)
                                                    }
                                                }
                                            }
                                            .frame(maxWidth: .infinity, minHeight: 52, alignment: .leading)
                                            .contentShape(Rectangle())
                                        }
                                        .buttonStyle(.plain)
                                        .disabled(savingIDs.contains(entry.id))
                                        .accessibilityValue(Text(entry.isCompleted ? "home.todo.done" : "home.todo.pending"))
                                        .accessibilityHint(Text(entry.isCompleted ? "home.todo.undo" : "home.todo.complete"))
                                    }
                                }
                            }
                            .frame(height: size.height * 0.48)
                        }
                        .padding(.horizontal, size.width * 0.085)
                        .padding(.top, size.height * 0.22)
                        .frame(width: size.width, height: size.height, alignment: .topLeading)
                    }
                    .padding(.horizontal, 24)
                    .padding(.top, 28)
                    .padding(.bottom, 40)
                } else {
                    ProgressView("home.loading").frame(maxWidth: .infinity, minHeight: 200)
                }
            }
            .background(Color(uiColor: .systemGroupedBackground).ignoresSafeArea())
            .navigationTitle(Text(period.title))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("common.done") { dismiss() }
                }
            }
        }
    }
}

/// 直接使用设计底图；固定宽高比保留胶带、纸张边缘和卷角，文字只落在纸面内。
private struct StickyNotePaper<Content: View>: View {
    let content: (CGSize) -> Content

    init(@ViewBuilder content: @escaping (CGSize) -> Content) {
        self.content = content
    }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .topLeading) {
                Image("MemoPaper")
                    .resizable()
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .shadow(color: .black.opacity(0.12), radius: 10, x: 2, y: 7)
                    .accessibilityHidden(true)
                content(geometry.size)
                    .foregroundStyle(CalendarPalette.paperInk)
            }
        }
        .aspectRatio(1122.0 / 1402.0, contentMode: .fit)
    }
}

/// 只在日历可见且应用活跃时采样；传感器不可用时便签仍保留轻微静态摇摆。
@MainActor
final class CalendarNoteMotion: ObservableObject {
    @Published private(set) var rotation = 0.0
    @Published private(set) var lift = 0.0
    private let manager = CMMotionManager()

    func start() {
        guard manager.isDeviceMotionAvailable, !manager.isDeviceMotionActive else { return }
        manager.deviceMotionUpdateInterval = 1.0 / 30.0
        manager.startDeviceMotionUpdates(to: .main) { [weak self] sample, error in
            if let error {
                Task { @MainActor [weak self] in
                    NSLog("Calendar note motion stopped: %@", error.localizedDescription)
                    self?.stop()
                }
                return
            }
            guard let sample else { return }
            let response = Self.response(
                gravityX: sample.gravity.x,
                angularVelocityX: sample.rotationRate.x,
                angularVelocityZ: sample.rotationRate.z,
                accelerationX: sample.userAcceleration.x,
                accelerationY: sample.userAcceleration.y)
            Task { @MainActor [weak self] in self?.apply(response) }
        }
    }

    func stop() {
        manager.stopDeviceMotionUpdates()
        rotation = 0
        lift = 0
    }

    private func apply(_ response: (rotation: Double, lift: Double)) {
        guard manager.isDeviceMotionActive else { return }
        rotation += (response.rotation - rotation) * 0.35
        lift += (response.lift - lift) * 0.3
    }

    /// 将倾斜与瞬时晃动映射为有上限的纸张位移，避免大幅甩动影响阅读和点击。
    nonisolated static func response(
        gravityX: Double, angularVelocityX: Double, angularVelocityZ: Double,
        accelerationX: Double, accelerationY: Double
    ) -> (rotation: Double, lift: Double) {
        let rotation =
            gravityX * 1.2 + angularVelocityX * 1.5
            + angularVelocityZ * 3 + accelerationX * 3
        return (min(6, max(-6, rotation)), min(5, max(-5, -accelerationY * 5)))
    }
}
