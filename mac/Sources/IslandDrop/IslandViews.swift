import SwiftUI
import AppKit

struct IslandRootView: View {
    @ObservedObject var model: IslandModel

    var body: some View {
        let s = model.size
        let shape = NotchShape(topRadius: model.topRadius, bottomRadius: model.bottomRadius)
        IslandContent(model: model)
            .frame(width: s.width, height: s.height, alignment: .top)
            .padding(.horizontal, model.topRadius)
            .background(shape.fill(Color.black))
            .clipShape(shape)
            .contentShape(shape)
            .shadow(color: .black.opacity(model.isExpanded ? 0.55 : 0), radius: 22, x: 0, y: 12)
            .onTapGesture { model.tapped() }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .environment(\.colorScheme, .dark)
    }
}

private struct IslandContent: View {
    @ObservedObject var model: IslandModel

    var body: some View {
        ZStack(alignment: .top) {
            switch model.state {
            case .idle, .hover:
                Color.clear
            case .dragPeek:
                CompactView(model: model) {
                    Image(systemName: "tray.and.arrow.down.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white)
                        .symbolEffect(.pulse, options: .repeating)
                } trailing: {
                    Text("Drop")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.7))
                }
                .transition(.island)
            case .working:
                if let a = model.activity {
                    CompactView(model: model) {
                        Image(nsImage: a.icon).resizable().interpolation(.high).frame(width: 20, height: 20)
                    } trailing: {
                        ProgressRing(progress: a.progress, tint: a.tint).frame(width: 17, height: 17)
                    }
                    .transition(.island)
                }
            case .dropTargets:
                DropTargetsView(model: model).transition(.island)
            case .open:
                HomeView(model: model).transition(.island)
            case .result:
                if let r = model.result { ResultView(model: model, r: r).transition(.island) }
            case .message:
                if let t = model.toast { ToastView(model: model, toast: t).transition(.island).id(t.text) }
            case .incoming:
                if let r = model.incoming { IncomingView(model: model, r: r).transition(.island) }
            case .forward:
                if let r = model.forwardOffer {
                    IncomingView(model: model, r: r, badge: "dot.radiowaves.up.forward", badgeTint: Palette.airdropBlue,
                                 decline: "Not Now", accept: "Send") { model.respondForward($0) }
                        .transition(.island)
                }
            }
        }
    }
}

// MARK: - Compact (leading / trailing around the notch)

private struct CompactView<L: View, T: View>: View {
    @ObservedObject var model: IslandModel
    @ViewBuilder var leading: () -> L
    @ViewBuilder var trailing: () -> T

    var body: some View {
        let s = model.size(for: .working)
        HStack(spacing: 0) {
            leading().frame(width: model.ear, alignment: .center).padding(.leading, 2)
            Spacer(minLength: 0)
            trailing().frame(width: model.ear, alignment: .center).padding(.trailing, 2)
        }
        .frame(width: s.width, height: s.height)
    }
}

struct ProgressRing: View {
    var progress: Double?
    var tint: Color

    var body: some View {
        ZStack {
            Circle().stroke(tint.opacity(0.25), lineWidth: 2.8)
            if let p = progress {
                Circle()
                    .trim(from: 0, to: CGFloat(max(0.015, p)))
                    .stroke(tint, style: StrokeStyle(lineWidth: 2.8, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.25), value: p)
            } else {
                Spinner(tint: tint)
            }
        }
    }
}

private struct Spinner: View {
    var tint: Color
    @State private var spin = false
    var body: some View {
        Circle()
            .trim(from: 0, to: 0.28)
            .stroke(tint, style: StrokeStyle(lineWidth: 2.8, lineCap: .round))
            .rotationEffect(.degrees(spin ? 360 : 0))
            .onAppear { withAnimation(.linear(duration: 0.9).repeatForever(autoreverses: false)) { spin = true } }
    }
}

// MARK: - Drop targets

private struct DropTargetsView: View {
    @ObservedObject var model: IslandModel
    @ObservedObject private var phone = PhoneLink.shared

    var body: some View {
        let s = model.size(for: .dropTargets)
        VStack(spacing: 0) {
            Color.clear.frame(height: model.notchSize.height)
            HStack(spacing: 10) {
                DropTile(model: model, target: .airdrop, title: "AirDrop", subtitle: "Nearby devices", accent: Palette.airdropBlue) {
                    Image(nsImage: SystemIcons.airDrop).resizable().interpolation(.high).frame(width: 44, height: 44)
                }
                DropTile(model: model, target: .phone, title: phone.preferred?.name ?? "Android",
                         subtitle: phone.preferred == nil ? "Not nearby" : "Send to phone",
                         accent: Palette.green, enabled: phone.preferred != nil) {
                    Image(systemName: "smartphone")
                        .font(.system(size: 30, weight: .regular))
                        .foregroundStyle(Palette.green)
                }
            }
            .padding(.horizontal, 16)
            .padding(.top, 8)
        }
        .frame(width: s.width, height: s.height, alignment: .top)
    }
}

private struct DropTile<Icon: View>: View {
    @ObservedObject var model: IslandModel
    let target: DropTarget
    let title: String
    let subtitle: String
    let accent: Color
    var enabled = true
    @ViewBuilder var icon: () -> Icon
    private var targeted: Bool { model.hoveredTarget == target }

    var body: some View {
        VStack(spacing: 6) {
            ZStack {
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .fill(targeted ? accent.opacity(0.22) : Color.white.opacity(0.07))
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(targeted ? accent : Color.white.opacity(0.16),
                                  style: StrokeStyle(lineWidth: targeted ? 2 : 1, dash: targeted ? [] : [4, 4]))
                icon()
                    .scaleEffect(targeted ? 1.14 : 1)
                    .offset(y: targeted ? -2 : 0)
            }
            .frame(height: 72)
            VStack(spacing: 1) {
                Text(title).font(.system(size: 12, weight: .semibold)).foregroundStyle(.white)
                Text(subtitle).font(.system(size: 10.5, weight: .medium)).foregroundStyle(Palette.secondary)
            }
            .lineLimit(1)
        }
        .opacity(enabled ? 1 : 0.4)
        .scaleEffect(targeted ? 1.04 : 1)
        .animation(.spring(response: 0.28, dampingFraction: 0.6), value: targeted)
    }
}

// MARK: - Result

private struct ResultView: View {
    @ObservedObject var model: IslandModel
    let r: ResultInfo
    @ObservedObject private var phone = PhoneLink.shared

    var body: some View {
        let s = model.size(for: .result)
        VStack(spacing: 12) {
            Color.clear.frame(height: model.notchSize.height - 2)
            HStack(spacing: 12) {
                Image(nsImage: r.icon).resizable().interpolation(.high).frame(width: 38, height: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.title)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(.white)
                        .lineLimit(1).truncationMode(.middle)
                    Text(r.subtitle)
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(Palette.secondary)
                        .contentTransition(.opacity)
                        .id(r.subtitle)
                        .transition(.opacity)
                }
                Spacer(minLength: 8)
                SuccessCheck(tint: Palette.green)
            }
            if r.kind == .received {
                HStack(spacing: 8) {
                    Spacer()
                    PillButton(icon: "dot.radiowaves.up.forward", title: "AirDrop") { model.airDropReceived() }
                    PillButton(icon: "folder.fill", title: "Show in Finder") { model.revealReceived() }
                    PillButton(icon: "arrow.up.forward.app.fill", title: "Open", prominent: true) { model.openReceived() }
                }
            }
        }
        .padding(.horizontal, 22)
        .frame(width: s.width, height: s.height, alignment: .top)
    }
}

private struct SuccessCheck: View {
    var tint: Color
    @State private var shown = false

    var body: some View {
        ZStack {
            Circle().fill(tint)
                .frame(width: 26, height: 26)
                .scaleEffect(shown ? 1 : 0.2)
                .opacity(shown ? 1 : 0)
            CheckShape()
                .trim(from: 0, to: shown ? 1 : 0)
                .stroke(.white, style: StrokeStyle(lineWidth: 2.6, lineCap: .round, lineJoin: .round))
                .frame(width: 11, height: 9)
        }
        .onAppear {
            withAnimation(.spring(response: 0.45, dampingFraction: 0.55).delay(0.18)) { shown = true }
        }
    }
}

private struct CheckShape: Shape {
    func path(in r: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: r.minX, y: r.minY + r.height * 0.55))
        p.addLine(to: CGPoint(x: r.minX + r.width * 0.36, y: r.maxY))
        p.addLine(to: CGPoint(x: r.maxX, y: r.minY))
        return p
    }
}

struct PillButton: View {
    let icon: String?
    let title: String?
    var tint: Color = .white
    var prominent = false
    var fill: Color? = nil
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 5) {
                if let icon { Image(systemName: icon).font(.system(size: 11, weight: .semibold)) }
                if let title { Text(title).font(.system(size: 12, weight: .semibold)) }
            }
            .padding(.horizontal, title == nil ? 9 : 12)
            .frame(height: 28)
            .foregroundStyle(prominent ? Color.black : tint)
            .background(Capsule().fill(fill.map { $0.opacity(hover ? 0.85 : 1) }
                ?? (prominent ? Color.white.opacity(hover ? 0.85 : 1) : Color.white.opacity(hover ? 0.22 : 0.13))))
            .contentShape(Capsule())
        }
        .buttonStyle(Pressable())
        .onHover { hover = $0 }
        .animation(.easeOut(duration: 0.12), value: hover)
    }
}

struct Pressable: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.9 : 1)
            .animation(.spring(response: 0.22, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

// MARK: - Home

private struct HomeView: View {
    @ObservedObject var model: IslandModel
    @ObservedObject private var phone = PhoneLink.shared

    var body: some View {
        let s = model.size(for: .open)
        VStack(spacing: 10) {
            HStack {
                HStack(spacing: 6) {
                    Image(systemName: "capsule.fill").font(.system(size: 8, weight: .bold))
                    Text("Island Drop").font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(.white.opacity(0.85))
                Spacer()
                if let a = model.activity {
                    ProgressRing(progress: a.progress, tint: a.tint).frame(width: 14, height: 14)
                }
                Button { SettingsWindow.show() } label: {
                    Image(systemName: "gearshape.fill")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(.white.opacity(0.75))
                        .frame(width: 26, height: 22)
                        .contentShape(Rectangle())
                }
                .buttonStyle(Pressable())
            }
            .padding(.horizontal, 22)
            .frame(height: model.notchSize.height)

            Card {
                HStack(spacing: 10) {
                    Image(systemName: "smartphone")
                        .font(.system(size: 22))
                        .foregroundStyle(phone.preferred == nil ? Palette.secondary : Palette.green)
                        .frame(width: 26)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(phone.preferred?.name ?? "No phone found")
                            .font(.system(size: 12.5, weight: .semibold)).foregroundStyle(.white).lineLimit(1)
                        Text(phone.preferred == nil ? "Open Island Drop on Android (same Wi-Fi)"
                                                    : "Drag files onto the notch to send")
                            .font(.system(size: 11)).foregroundStyle(Palette.secondary).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    if phone.preferred != nil {
                        Circle().fill(Palette.green).frame(width: 7, height: 7)
                            .shadow(color: Palette.green, radius: 4)
                    }
                }
            }
            .padding(.horizontal, 18)
        }
        .frame(width: s.width, height: s.height, alignment: .top)
    }

}

private struct Card<Content: View>: View {
    @ViewBuilder var content: () -> Content
    var body: some View {
        content()
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 60, maxHeight: 60)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Palette.fill))
    }
}

// MARK: - Toast

private struct ToastView: View {
    @ObservedObject var model: IslandModel
    let toast: Toast

    var body: some View {
        let s = model.size(for: .message)
        HStack(spacing: 9) {
            Image(systemName: toast.icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(toast.tint)
                .symbolEffect(.bounce, value: toast.text)
            Text(toast.text)
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(.white)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
        }
        .padding(.horizontal, 20)
        .padding(.top, model.notchSize.height + 2)
        .frame(width: s.width, height: s.height, alignment: .top)
    }
}

// MARK: - Incoming (phone → Mac), AirDrop-style prompt

private struct IncomingView: View {
    @ObservedObject var model: IslandModel
    let r: IncomingRequest
    var badge = "smartphone"
    var badgeTint = Palette.green
    var decline = "Decline"
    var accept = "Accept"
    var respond: ((Bool) -> Void)? = nil
    @State private var pulse = false

    var body: some View {
        let s = model.size(for: .incoming)
        VStack(spacing: 12) {
            Color.clear.frame(height: model.notchSize.height - 4)
            HStack(spacing: 12) {
                ZStack(alignment: .bottomTrailing) {
                    Image(nsImage: r.icon).resizable().interpolation(.high).frame(width: 40, height: 40)
                    Image(systemName: badge)
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(.white)
                        .frame(width: 18, height: 18)
                        .background(Circle().fill(badgeTint))
                        .overlay(Circle().stroke(Color.black, lineWidth: 2))
                        .offset(x: 4, y: 4)
                        .scaleEffect(pulse ? 1.12 : 1)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(r.sender)
                        .font(.system(size: 13.5, weight: .semibold))
                        .foregroundStyle(.white).lineLimit(1)
                    Text(respond == nil ? "wants to send \(r.title) · \(r.detail)" : "\(r.title) · \(r.detail)")
                        .font(.system(size: 11.5, weight: .medium))
                        .foregroundStyle(Palette.secondary)
                        .lineLimit(1).truncationMode(.middle)
                }
                Spacer(minLength: 0)
            }
            HStack(spacing: 8) {
                Spacer()
                PillButton(icon: nil, title: decline) { (respond ?? model.respondIncoming)(false) }
                PillButton(icon: respond == nil ? "star.fill" : nil, title: respond == nil ? "Accept & Remember" : accept,
                           fill: Palette.airdropBlue) { (respond ?? model.respondIncoming)(true) }
            }
        }
        .padding(.horizontal, 22)
        .frame(width: s.width, height: s.height, alignment: .top)
        .onAppear {
            withAnimation(.easeInOut(duration: 0.8).repeatForever(autoreverses: true)) { pulse = true }
        }
    }
}
