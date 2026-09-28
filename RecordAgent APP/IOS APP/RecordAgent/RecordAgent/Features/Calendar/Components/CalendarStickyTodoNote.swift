import SwiftUI

/// 两种周期共用纸张、胶带和卷角；进度只从当前周期实例计算。
struct CalendarStickyTodoNote: View {
    let group: CalendarTodoGroup
    let savingIDs: Set<String>
    let onToggle: (HomeEntry) -> Void
    @AppStorage("calendar.week.expanded") private var weekExpanded = false
    @AppStorage("calendar.month.expanded") private var monthExpanded = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    @State private var isVisible = false
    @State private var hasEntered = false
    @State private var isSwaying = false
    private var expanded: Bool { group.period == .weekly ? weekExpanded : monthExpanded }
    private var swayDirection: Double { group.period == .weekly ? 1 : -1 }
    private var motionEnabled: Bool { isVisible && scenePhase == .active && !reduceMotion }
    private var paper: Color {
        group.period == .weekly
            ? Color(red: 1, green: 0.95, blue: 0.69) : Color(red: 0.89, green: 0.96, blue: 0.76)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Button {
                withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                    if group.period == .weekly { weekExpanded.toggle() } else { monthExpanded.toggle() }
                }
            } label: {
                HStack(alignment: .firstTextBaseline, spacing: 5) {
                    Text(group.period.title).font(.headline).lineLimit(1).minimumScaleFactor(0.7)
                    Spacer(minLength: 0)
                    Text("\(group.completedCount)/\(group.entries.count)")
                        .font(.subheadline.monospacedDigit()).fixedSize()
                    Image(systemName: expanded ? "chevron.up" : "chevron.down")
                        .font(.system(size: 9, weight: .medium)).opacity(0.6)
                }
                .frame(minHeight: 44)
            }
            .accessibilityHint(Text(expanded ? "calendar.note.collapse" : "calendar.note.expand"))
            ForEach(expanded ? group.entries : Array(group.entries.prefix(2))) { entry in
                Button {
                    onToggle(entry)
                } label: {
                    HStack(alignment: .center, spacing: 7) {
                        if savingIDs.contains(entry.id) {
                            ProgressView().controlSize(.mini).frame(width: 18)
                        } else {
                            Image(systemName: entry.isCompleted ? "checkmark.square" : "square")
                                .font(.system(size: 18, weight: .light))
                        }
                        Text(entry.title).font(.subheadline)
                            .lineLimit(expanded ? nil : 1)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
                }
                .disabled(savingIDs.contains(entry.id))
                .accessibilityValue(Text(entry.isCompleted ? "home.todo.done" : "home.todo.pending"))
            }
        }
        .buttonStyle(.plain)
        .foregroundStyle(CalendarPalette.paperInk)
        .padding(.horizontal, 12)
        .padding(.top, 12)
        .padding(.bottom, 22)
        .frame(maxWidth: .infinity, alignment: .topLeading)
        .background {
            UnevenRoundedRectangle(
                topLeadingRadius: 5, bottomLeadingRadius: 5,
                bottomTrailingRadius: 28, topTrailingRadius: 5
            )
            .fill(
                LinearGradient(
                    colors: [paper.opacity(0.78), paper], startPoint: .topLeading, endPoint: .bottomTrailing)
            )
            .shadow(color: .black.opacity(0.1), radius: 5, x: 1, y: 5)
            .overlay(alignment: .bottomTrailing) {
                NoteFold().fill(
                    LinearGradient(
                        colors: [paper, .white.opacity(0.8), paper.opacity(0.5)],
                        startPoint: .topLeading, endPoint: .bottomTrailing)
                )
                .frame(width: 28, height: 28)
            }
        }
        .overlay(alignment: .top) {
            Rectangle().fill(Color(red: 0.81, green: 0.8, blue: 0.71).opacity(0.28))
                .frame(width: 45, height: 16)
                .rotationEffect(.degrees(group.period == .weekly ? -4 : 3))
                .offset(y: -7)
                .accessibilityHidden(true)
                .allowsHitTesting(false)
        }
        .rotationEffect(.degrees(motionEnabled ? (isSwaying ? 0.65 : -0.45) * swayDirection : 0), anchor: .top)
        .offset(x: motionEnabled ? (isSwaying ? 1 : -1) * swayDirection : 0)
        .opacity(hasEntered ? 1 : 0)
        .scaleEffect(hasEntered ? 1 : 0.97, anchor: .top)
        .offset(y: hasEntered ? 0 : 16)
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

private struct NoteFold: Shape {
    func path(in rect: CGRect) -> Path {
        Path { path in
            path.move(to: CGPoint(x: 0, y: rect.maxY))
            path.addQuadCurve(
                to: CGPoint(x: rect.maxX, y: 0), control: CGPoint(x: rect.midX, y: rect.midY))
            path.addQuadCurve(
                to: CGPoint(x: 0, y: rect.maxY), control: CGPoint(x: rect.maxX, y: rect.maxY))
        }
    }
}
