import CVKEGPSCore
import SwiftUI

/// A Liquid Glass card for values that float over the brand backdrop or a map.
struct GlassCard<Content: View>: View {
    var cornerRadius: CGFloat = 24
    var padding: CGFloat = Space.s4
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glassEffect(.regular, in: .rect(cornerRadius: cornerRadius))
    }
}

/// A coloured dot for a status. Text always says the same, so the colour is not the only sign.
struct StatusDot: View {
    var color: Color

    var body: some View {
        Circle().fill(color).frame(width: 10, height: 10).accessibilityHidden(true)
    }
}

/// A label and a value. A missing value shows as a dash and reads as "Unavailable".
struct MetricView: View {
    var title: LocalizedStringKey
    var value: String?
    var identifier: String?
    /// A short qualifier below the value, for example "Includes stops".
    var note: LocalizedStringKey? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value ?? "—")
                .font(.headline.monospacedDigit())
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .accessibilityIdentifier(identifier ?? "")
            if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
        }
        .accessibilityElement(children: .combine)
        .accessibilityValue(value == nil ? Text("Unavailable") : Text(""))
    }
}

/// A problem with an icon on a danger tint, and an optional recovery action.
struct ProblemBanner<Action: View>: View {
    var message: String
    @ViewBuilder var action: Action

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s2) {
            Label {
                Text(message).font(.subheadline)
            } icon: {
                Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Palette.danger)
            }
            action
        }
        .padding(Space.s3)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Palette.dangerSubtle, in: .rect(cornerRadius: 16))
        .accessibilityIdentifier("problem_banner")
    }
}

extension ProblemBanner where Action == EmptyView {
    init(message: String) {
        self.init(message: message) { EmptyView() }
    }
}

/// Recording, Paused, No GPS signal, or an approximate position. The icon and the text both show the state.
struct TripStatusChip: View {
    var status: TripStatus
    var noSignal: Bool
    var approximate = false

    var body: some View {
        let recording = status == .recording
        HStack(spacing: Space.s2) {
            Image(systemName: recording ? "record.circle.fill" : "pause.circle.fill")
                .foregroundStyle(recording ? Palette.danger : Palette.inkMuted)
            // No pulse: the owner's design language does not allow pulsing icons. The text and the tint show the state.
            Text(text).font(.subheadline.weight(.medium))
        }
        .padding(.horizontal, Space.s3)
        .padding(.vertical, Space.s1 + 2)
        // A lost signal is a problem, so the glass takes the danger tint.
        .glassEffect(recording && noSignal ? .regular.tint(Palette.danger.opacity(0.3)) : .regular, in: .capsule)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("recording_status")
    }

    private var text: String {
        if status == .recording && noSignal {
            return approximate ? String(localized: "No GPS: approximate position") : String(localized: "No GPS signal")
        }
        return status.title
    }
}

/// The accuracy bar: empty at 50 m or more, full at 1 m or less, with a notch at 5 m. The scale is logarithmic.
/// The fill uses the functional blue-to-apricot gradient. Data changes at once, so the bar does not animate.
struct AccuracyBar: View {
    var accuracyMeters: Double?
    var showsLabel = true
    var showsScale = false

    @Environment(\.displayFormat) private var format

    var body: some View {
        let progress = PositionAccuracyScale.progress(accuracyMeters)
        VStack(alignment: .leading, spacing: Space.s1) {
            if showsLabel { Text(description).font(.caption).foregroundStyle(.secondary) }
            if let progress {
                GeometryReader { geometry in
                    let width = geometry.size.width
                    let notch = (PositionAccuracyScale.progress(PositionAccuracyScale.referenceMeters) ?? 0) * width
                    ZStack(alignment: .leading) {
                        Capsule().fill(Palette.surfaceSunken).frame(height: 4)
                        Capsule()
                            .fill(LinearGradient(colors: [Palette.accent, Palette.gradientEnd], startPoint: .leading, endPoint: .trailing))
                            .frame(width: max(progress * width, 0), height: 4)
                        Rectangle().fill(.primary).frame(width: 2, height: 12).offset(x: notch - 1)
                    }
                    .frame(height: 12)
                    .frame(maxHeight: .infinity)
                }
                .frame(height: 12)
                .accessibilityHidden(true)
                if showsScale {
                    HStack {
                        Text("\(format.decimal(PositionAccuracyScale.emptyMeters)) m")
                        Spacer()
                        Text("\(format.decimal(PositionAccuracyScale.referenceMeters)) m")
                        Spacer()
                        Text("\(format.decimal(PositionAccuracyScale.fullMeters)) m")
                    }
                    .font(.caption2)
                    .foregroundStyle(.secondary)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(Text("Accuracy scale: empty at 50 metres, reference mark at 5 metres, full at 1 metre or better. Smaller estimates are better."))
                }
            }
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(Text(description))
        .accessibilityIdentifier("position_accuracy")
    }

    private var description: String {
        guard let accuracy = accuracyMeters, PositionAccuracyScale.progress(accuracy) != nil else {
            return String(localized: "Accuracy unavailable")
        }
        if accuracy > 0 && accuracy < 0.1 { return String(localized: "Position estimate below \(format.decimal(0.1)) m") }
        return String(localized: "Position estimate ±\(format.decimal(accuracy)) m")
    }
}

/// Pause or Resume, and Stop and save. Both buttons stand side by side when their labels fit, and stack otherwise.
struct TripActionsView: View {
    var trip: TripRecord
    var pause: () -> Void
    var resume: () -> Void
    var stop: () -> Void

    var body: some View {
        GlassEffectContainer(spacing: Space.s3) {
            // In a row, Pause or Resume takes the width of its label and Stop and save takes the rest, so no label is cut.
            // When the labels do not fit in one row, the buttons stack with the full width.
            ViewThatFits(in: .horizontal) {
                HStack(spacing: Space.s3) { buttons(row: true) }
                VStack(spacing: Space.s2) { buttons(row: false) }
            }
        }
    }

    @ViewBuilder private func buttons(row: Bool) -> some View {
        if trip.status == .recording {
            Button(action: pause) {
                Label("Pause trip", systemImage: "pause.fill").frame(maxWidth: row ? nil : .infinity, minHeight: 30)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .fixedSize(horizontal: row, vertical: false)
            .accessibilityIdentifier("trip_pause")
        } else {
            Button(action: resume) {
                Label("Resume trip", systemImage: "play.fill").frame(maxWidth: row ? nil : .infinity, minHeight: 30)
            }
            .buttonStyle(.glass)
            .controlSize(.large)
            .fixedSize(horizontal: row, vertical: false)
            .accessibilityIdentifier("trip_resume")
        }
        Button(action: stop) {
            Label("Stop and save", systemImage: "stop.fill").frame(maxWidth: .infinity, minHeight: 30)
        }
        .prominentButton()
        .controlSize(.large)
        .accessibilityIdentifier("trip_stop")
    }
}

/// A chip that opens the activity choice. As on Android, it can show the activity icon and a short name; then `spoken`
/// gives VoiceOver the full choice.
struct ActivityChip: View {
    var text: String
    var symbol: String? = nil
    var trailing = "pencil"
    var spoken: String? = nil
    /// The label height. A chip beside a large button uses the height of that button's label.
    var minHeight: CGFloat = 30
    var action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: Space.s2) {
                if let symbol { Image(systemName: symbol).foregroundStyle(.primary) }
                Text(text).lineLimit(1)
                Image(systemName: trailing).font(.footnote.weight(.semibold)).foregroundStyle(.secondary)
            }
            .font(.subheadline)
            .frame(minHeight: minHeight)
        }
        .buttonStyle(.glass)
        .accessibilityLabel(Text(spoken ?? text))
        .accessibilityIdentifier("activity_chip")
    }
}

/// The title first, then the icon.
struct TrailingIconLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: Space.s2) {
            configuration.title
            configuration.icon.foregroundStyle(.secondary)
        }
    }
}

/// The dates at which a recording state changes without a new fix: the signal-loss time and the end of an approximate
/// position. A `TimelineView` with these dates redraws only at those times.
extension RecordingState {
    func changeDates(now: Int64 = MonotonicClock.nanos()) -> [Date] {
        [signalLostAtNanos, approximateUntilNanos].compactMap { $0 }.filter { $0 > now }
            .map { Date.now.addingTimeInterval(Double($0 - now) / 1e9 + 0.01) }
    }
}

/// The recording time. While recording, the system counts it without redraws of the screen.
struct TripTimeText: View {
    var state: RecordingState
    @Environment(\.displayFormat) private var format

    var body: some View {
        let now = MonotonicClock.nanos()
        let elapsed = state.elapsedNanos(at: now)
        if state.trip?.status == .recording {
            Text(Date.now.addingTimeInterval(-Double(elapsed) / 1e9), style: .timer)
                .monospacedDigit()
        } else {
            Text(format.duration(elapsed))
        }
    }
}

extension View {
    /// A prominent glass button with the fill token. In dark mode, white text on the fill keeps a contrast of 4.5:1 or more.
    func prominentButton() -> some View {
        buttonStyle(.glassProminent).tint(Palette.accentFill)
    }
}

/// The 32-point `accentSubtle` tile at the start of a list row, as on Android. The symbol keeps its size at all text
/// sizes, so it stays inside the tile.
struct IconTile: View {
    var symbol: String

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: 16, weight: .semibold))
            .foregroundStyle(Palette.accent)
            .frame(width: 32, height: 32)
            .background(Palette.accentSubtle, in: .rect(cornerRadius: 8))
            .accessibilityHidden(true)
    }
}

/// A status as a dot and text, as on Android. The text says the same as the colour.
struct StatusText: View {
    var text: String
    var color: Color

    var body: some View {
        HStack(spacing: Space.s2) {
            StatusDot(color: color)
            Text(text).font(.caption.weight(.medium)).foregroundStyle(Palette.ink)
        }
    }
}

/// Brand art for empty states, as on Android: a disc with a 70° slice cut out, the slice beside it, a half-disc, and two
/// dots. Flat fills only. It is decoration, so VoiceOver skips it. Only one art on a screen moves. The slice drifts slowly
/// only while the art is on screen, and stays at rest when Reduce Motion is on.
struct SliceArt: View {
    var animated = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var out = false

    var body: some View {
        let moving = animated && !reduceMotion
        ZStack(alignment: .topLeading) {
            Sector(start: 35, sweep: 290).fill(Palette.accent).frame(width: 44, height: 44).offset(x: 4, y: 10)
            Sector(start: -35, sweep: 70).fill(Palette.brandApricot).frame(width: 44, height: 44)
                .offset(x: 4 + (moving ? (out ? 11 : 5) : 5.5), y: 10)
            Sector(start: 180, sweep: 180).fill(Palette.brandApricotSubtle).frame(width: 28, height: 28).offset(x: 64, y: 44)
            Circle().fill(Palette.brandApricot).frame(width: 8, height: 8).offset(x: 74, y: 48)
            Circle().fill(Palette.ink).frame(width: 8, height: 8).offset(x: 80, y: 8)
        }
        .frame(width: 96, height: 64, alignment: .topLeading)
        .clipped()
        .accessibilityHidden(true)
        .onAppear {
            guard moving else { return }
            withAnimation(.easeInOut(duration: 2.4).repeatForever(autoreverses: true)) { out = true }
        }
        .onDisappear { out = false }
    }
}

/// A filled circle sector from `start` degrees, clockwise on screen, over `sweep` degrees. Zero degrees points right.
private struct Sector: Shape {
    var start: Double
    var sweep: Double

    func path(in rect: CGRect) -> Path {
        let centre = CGPoint(x: rect.midX, y: rect.midY)
        var path = Path()
        path.move(to: centre)
        path.addArc(center: centre, radius: min(rect.width, rect.height) / 2, startAngle: .degrees(start),
                    endAngle: .degrees(start + sweep), clockwise: false)
        path.closeSubpath()
        return path
    }
}
