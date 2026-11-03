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

/// Google Drive mark (2020), drawn from the official 87.3×78 artwork geometry.
struct DriveLogo: View {
    var body: some View {
        Canvas { ctx, size in
            let sx = size.width / 87.3, sy = size.height / 78
            func poly(_ pts: [(CGFloat, CGFloat)], _ hex: UInt32) {
                var p = Path()
                p.addLines(pts.map { CGPoint(x: $0.0 * sx, y: $0.1 * sy) })
                p.closeSubpath()
                ctx.fill(p, with: .color(Color(hex: hex)))
            }
            poly([(6.6, 66.85), (10.45, 73.5), (13.75, 76.8), (27.5, 53), (0, 53), (1.2, 57.5)], 0x0066DA)
            poly([(43.65, 25), (29.9, 1.2), (26.6, 4.5), (1.2, 48.5), (0, 53), (27.5, 53)], 0x00AC47)
            poly([(73.55, 76.8), (76.85, 73.5), (78.45, 70.75), (86.1, 57.5), (87.3, 53), (59.8, 53), (65.65, 64.5)], 0xEA4335)
            poly([(43.65, 25), (57.4, 1.2), (52.9, 0), (34.4, 0), (29.9, 1.2)], 0x00832D)
            poly([(59.8, 53), (27.5, 53), (13.75, 76.8), (18.25, 78), (69.05, 78), (73.55, 76.8)], 0x2684FC)
            poly([(73.4, 26.5), (60.7, 4.5), (57.4, 1.2), (43.65, 25), (59.8, 53), (87.3, 53), (86.1, 48.5)], 0xFFBA00)
        }
        .aspectRatio(87.3 / 78, contentMode: .fit)
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
