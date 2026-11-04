import SwiftUI
import AppKit

/// The MacBook notch silhouette: concave flares into the menu bar at the top,
/// continuous (squircle-like) corners at the bottom. Both radii animate.
struct NotchShape: Shape {
    var topRadius: CGFloat
    var bottomRadius: CGFloat

    var animatableData: AnimatablePair<CGFloat, CGFloat> {
        get { AnimatablePair(topRadius, bottomRadius) }
        set { topRadius = newValue.first; bottomRadius = newValue.second }
    }

    func path(in rect: CGRect) -> Path {
        let t = min(topRadius, rect.width / 4)
        let b = min(bottomRadius, (rect.width - 2 * t) / 2, rect.height - t)
        let k: CGFloat = 0.45 // control-point factor: smoother than a circular arc
        var p = Path()
        p.move(to: CGPoint(x: rect.minX, y: rect.minY))
        p.addCurve(to: CGPoint(x: rect.minX + t, y: rect.minY + t),
                   control1: CGPoint(x: rect.minX + t * 0.62, y: rect.minY),
                   control2: CGPoint(x: rect.minX + t, y: rect.minY + t * 0.38))
        p.addLine(to: CGPoint(x: rect.minX + t, y: rect.maxY - b))
        p.addCurve(to: CGPoint(x: rect.minX + t + b, y: rect.maxY),
                   control1: CGPoint(x: rect.minX + t, y: rect.maxY - b * k),
                   control2: CGPoint(x: rect.minX + t + b * k, y: rect.maxY))
        p.addLine(to: CGPoint(x: rect.maxX - t - b, y: rect.maxY))
        p.addCurve(to: CGPoint(x: rect.maxX - t, y: rect.maxY - b),
                   control1: CGPoint(x: rect.maxX - t - b * k, y: rect.maxY),
                   control2: CGPoint(x: rect.maxX - t, y: rect.maxY - b * k))
        p.addLine(to: CGPoint(x: rect.maxX - t, y: rect.minY + t))
        p.addCurve(to: CGPoint(x: rect.maxX, y: rect.minY),
                   control1: CGPoint(x: rect.maxX - t, y: rect.minY + t * 0.38),
                   control2: CGPoint(x: rect.maxX - t * 0.62, y: rect.minY))
        p.closeSubpath()
        return p
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

enum SystemIcons {
    static let airDrop: NSImage = NSWorkspace.shared.icon(forFile: "/System/Library/CoreServices/Finder.app/Contents/Applications/AirDrop.app")
}

/// Blur + fade + scale – the content morph used by the Dynamic Island.
struct BlurFade: ViewModifier {
    let radius: CGFloat
    let opacity: Double
    let scale: CGFloat
    func body(content: Content) -> some View {
        content.blur(radius: radius).opacity(opacity).scaleEffect(scale, anchor: .top)
    }
}

extension AnyTransition {
    static var island: AnyTransition {
        .asymmetric(
            insertion: .modifier(active: BlurFade(radius: 12, opacity: 0, scale: 0.88),
                                 identity: BlurFade(radius: 0, opacity: 1, scale: 1))
                .animation(Motion.content.delay(0.07)),
            removal: .modifier(active: BlurFade(radius: 10, opacity: 0, scale: 0.94),
                               identity: BlurFade(radius: 0, opacity: 1, scale: 1))
                .animation(.easeOut(duration: 0.14)))
    }
}
