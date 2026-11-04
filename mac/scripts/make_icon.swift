import AppKit
// Renders the 1024px app icon: dark squircle, glowing island pill, upload arrow.
let size: CGFloat = 1024
let img = NSImage(size: NSSize(width: size, height: size))
img.lockFocus()
let ctx = NSGraphicsContext.current!.cgContext
let inset: CGFloat = 100
let rect = CGRect(x: inset, y: inset, width: size - inset*2, height: size - inset*2)
let squircle = NSBezierPath(roundedRect: rect, xRadius: 185, yRadius: 185)
ctx.saveGState()
let shadow = NSShadow(); shadow.shadowBlurRadius = 30; shadow.shadowOffset = NSSize(width: 0, height: -12); shadow.shadowColor = NSColor.black.withAlphaComponent(0.45); shadow.set()
NSColor.black.setFill(); squircle.fill()
ctx.restoreGState()
squircle.addClip()
let bg = NSGradient(colors: [NSColor(calibratedRed: 0.13, green: 0.14, blue: 0.20, alpha: 1), NSColor(calibratedRed: 0.03, green: 0.03, blue: 0.06, alpha: 1)])!
bg.draw(in: rect, angle: -90)
// glow
let glow = NSGradient(colors: [NSColor(calibratedRed: 0.15, green: 0.52, blue: 0.99, alpha: 0.55), NSColor(calibratedRed: 0.15, green: 0.52, blue: 0.99, alpha: 0)])!
glow.draw(fromCenter: NSPoint(x: 512, y: 640), radius: 0, toCenter: NSPoint(x: 512, y: 640), radius: 420, options: [])
// island pill
let pill = NSBezierPath(roundedRect: CGRect(x: 262, y: 600, width: 500, height: 150), xRadius: 75, yRadius: 75)
NSColor.black.setFill(); pill.fill()
NSColor.white.withAlphaComponent(0.10).setStroke(); pill.lineWidth = 4; pill.stroke()
// progress ring in pill
let ringC = NSPoint(x: 682, y: 675)
let track = NSBezierPath(); track.appendArc(withCenter: ringC, radius: 38, startAngle: 0, endAngle: 360)
track.lineWidth = 14; NSColor(calibratedRed: 0.19, green: 0.82, blue: 0.35, alpha: 0.3).setStroke(); track.stroke()
let arc = NSBezierPath(); arc.appendArc(withCenter: ringC, radius: 38, startAngle: 90, endAngle: -160, clockwise: true)
arc.lineWidth = 14; arc.lineCapStyle = .round; NSColor(calibratedRed: 0.19, green: 0.82, blue: 0.35, alpha: 1).setStroke(); arc.stroke()
// dot in pill (camera)
NSColor(calibratedWhite: 0.18, alpha: 1).setFill(); NSBezierPath(ovalIn: CGRect(x: 318, y: 650, width: 50, height: 50)).fill()
// arrow
let arrow = NSBezierPath()
arrow.move(to: NSPoint(x: 512, y: 520)); arrow.line(to: NSPoint(x: 512, y: 250))
arrow.move(to: NSPoint(x: 400, y: 410)); arrow.line(to: NSPoint(x: 512, y: 522)); arrow.line(to: NSPoint(x: 624, y: 410))
arrow.lineWidth = 56; arrow.lineCapStyle = .round; arrow.lineJoinStyle = .round
NSColor.white.setStroke(); arrow.stroke()
img.unlockFocus()
let rep = NSBitmapImageRep(data: img.tiffRepresentation!)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
