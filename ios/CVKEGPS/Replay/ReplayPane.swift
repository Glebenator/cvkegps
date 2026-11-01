import CVKEGPSCore
import SwiftUI

/// The replay panel: the loading state, then the glance view or the details with the charts.
struct ReplayPane: View {
    var replay: ReplayModel
    var heightOffset: Double
    var seaLevel: Bool
    /// True where three charts would be too small: a phone in landscape and the largest text sizes.
    var singleChart: Bool

    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format

    var body: some View {
        VStack(alignment: .leading, spacing: Space.s3) {
            switch replay.load {
            case .failed:
                ProblemBanner(message: String(localized: "The app cannot read the saved trips. The trips stay on the phone.")) {
                    Button("Try again") { Task { await replay.retry(from: model.library) } }.buttonStyle(.glass)
                }
            case .loading:
                HStack(spacing: Space.s2) {
                    ProgressView()
                    Text("Calculating the replay…")
                }
                .accessibilityIdentifier("replay_loading")
            case let .ready(trip):
                if let trip, !trip.fixes.isEmpty {
                    content(trip)
                } else {
                    Text("This trip has no positions to replay.")
                }
            }
        }
    }

    @ViewBuilder private func content(_ trip: TripReplay) -> some View {
        let values = trip.valuesAt(replay.playhead)
        ReplayValuesRow(replay: trip, values: values, playhead: replay.playhead, heightOffset: heightOffset, seaLevel: seaLevel)
        if !replay.details { SpeedProfile(trip: trip, playhead: replay.playhead, state: timelineState(trip, values), seek: replay.seek, pauseAt: replay.pause(at:)) }
        ReplayControls(replay: replay, trip: trip)
        MomentChips(trip: trip, playhead: replay.playhead, heightOffset: heightOffset) { moment in
            replay.pause(at: moment.timeNanos)
            if singleChart { replay.chart = moment.kind.chart }
        }
        if replay.details {
            ReplayCharts(trip: trip, playhead: replay.playhead, heightOffset: heightOffset, seaLevel: seaLevel,
                         state: timelineState(trip, values), single: singleChart ? replay.chart : nil,
                         select: { replay.chart = $0 }, seek: replay.seek, pauseAt: replay.pause(at:))
        }
        if !trip.accelerationFromSensors && !trip.acceleration.isEmpty {
            Text("This trip has no motion sensor record. Acceleration comes from the change of GPS speed.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    /// The time and the speed at the playhead, for VoiceOver.
    private func timelineState(_ trip: TripReplay, _ values: ReplayValues) -> String {
        let speed = values.speedMetersPerSecond.map(format.spokenSpeed)
            ?? (values.fix == nil ? String(localized: "No GPS signal") : String(localized: "Approximate position"))
        return String(localized: "\(format.duration(replay.playhead)) of \(format.duration(trip.durationNanos)). \(speed)")
    }
}

private struct ReplayValuesRow: View {
    var replay: TripReplay
    var values: ReplayValues
    var playhead: Int64
    var heightOffset: Double
    var seaLevel: Bool
    @Environment(\.displayFormat) private var format

    var body: some View {
        // A network position or a gap has no speed. The text says why.
        let speed: String? = if values.fix == nil { String(localized: "No GPS signal") }
            else if values.fix?.source == .network { String(localized: "Approximate position") }
            else { values.speedMetersPerSecond.map(format.speed) }
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .top, spacing: Space.s4) { items(speed) }
            VStack(alignment: .leading, spacing: Space.s2) { items(speed) }
        }
    }

    @ViewBuilder private func items(_ speed: String?) -> some View {
        MetricView(title: "Replay time", value: "\(format.clock(playhead)) / \(format.clock(replay.durationNanos))", identifier: "replay_time")
        MetricView(title: "Speed", value: speed, identifier: "replay_speed")
        if replay.heightSource != .none {
            MetricView(title: seaLevel ? "Altitude above sea level" : "GPS height (WGS 84)",
                       value: values.heightMeters.map { format.altitude($0 - heightOffset) }, identifier: "replay_height")
        }
        if !replay.acceleration.isEmpty {
            MetricView(title: "Acceleration", value: values.accelerationMetersPerSecondSquared.map(format.acceleration),
                       identifier: "replay_acceleration")
        }
    }
}

/// Previous moment, Play or Pause, Next moment, and the replay speed.
private struct ReplayControls: View {
    var replay: ReplayModel
    var trip: TripReplay
    @Environment(\.displayFormat) private var format

    var body: some View {
        let previous = trip.previousMoment(before: replay.playhead)
        let next = trip.nextMoment(after: replay.playhead)
        GlassEffectContainer(spacing: Space.s2) {
            HStack(spacing: Space.s2) {
                Button { if let previous { replay.pause(at: previous.timeNanos) } } label: {
                    Image(systemName: "backward.end.fill").frame(width: 24, height: 24)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .disabled(previous == nil)
                .accessibilityLabel(Text("Previous moment"))
                .accessibilityIdentifier("replay_previous")
                // Play and Pause is the one primary button.
                Button(action: replay.toggle) {
                    Label(replay.playing ? "Pause" : "Play", systemImage: replay.playing ? "pause.fill" : "play.fill")
                        .frame(maxWidth: .infinity, minHeight: 30)
                        .contentTransition(.symbolEffect(.replace))
                }
                .buttonStyle(.glassProminent)
                .accessibilityIdentifier("replay_play")
                Button { if let next { replay.pause(at: next.timeNanos) } } label: {
                    Image(systemName: "forward.end.fill").frame(width: 24, height: 24)
                }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .disabled(next == nil)
                .accessibilityLabel(Text("Next moment"))
                .accessibilityIdentifier("replay_next")
                Button(action: replay.nextRate) {
                    Text("\(format.whole(replay.rate))×").monospacedDigit().frame(minWidth: 36, minHeight: 30)
                }
                .buttonStyle(.glass)
                .accessibilityLabel(Text("Replay speed: \(format.whole(replay.rate)) times"))
                .accessibilityIdentifier("replay_rate")
            }
            .controlSize(.large)
        }
    }
}

/// One chip for each moment, with its number, name, and value. A chip moves the playhead there and pauses.
private struct MomentChips: View {
    var trip: TripReplay
    var playhead: Int64
    var heightOffset: Double
    var pick: (ReplayMoment) -> Void
    @Environment(\.displayFormat) private var format

    var body: some View {
        if trip.moments.isEmpty {
            Text("The app found no notable moments in this trip.").font(.footnote).foregroundStyle(.secondary)
        } else {
            ScrollView(.horizontal) {
                GlassEffectContainer(spacing: Space.s2) {
                    HStack(spacing: Space.s2) {
                        ForEach(trip.moments) { moment in
                            Button { pick(moment) } label: {
                                HStack(spacing: Space.s2) {
                                    MomentMarker(number: moment.number)
                                    Text("\(moment.kind.title) · \(momentValue(moment, heightOffset: heightOffset, format: format))")
                                        .font(.subheadline)
                                }
                                .frame(minHeight: 30)
                            }
                            .buttonStyle(.glass)
                            .tint(playhead == moment.timeNanos ? Palette.accent : nil)
                            .accessibilityAddTraits(playhead == moment.timeNanos ? .isSelected : [])
                            .accessibilityIdentifier("moment_\(moment.kind.rawValue)")
                        }
                    }
                }
            }
            .scrollIndicators(.hidden)
            .accessibilityIdentifier("replay_moments")
        }
    }
}

/// The moment value in display units. Braking shows as a positive deceleration.
func momentValue(_ moment: ReplayMoment, heightOffset: Double, format: DisplayFormat) -> String {
    switch moment.kind {
    case .fastestSpeed: format.speed(moment.value)
    case .highestPoint: format.altitude(moment.value - heightOffset)
    case .acceleration, .braking: format.acceleration(abs(moment.value))
    }
}

extension MomentKind {
    /// The chart that shows a moment.
    var chart: ReplayChartKind {
        switch self {
        case .acceleration, .braking: .acceleration
        case .fastestSpeed: .speed
        case .highestPoint: .height
        }
    }
}

/// The trip's speed over time. It is also the scrubber: a tap or a horizontal drag moves the playhead.
/// A hatched band is a GPS gap. A dashed warning line marks approximate positions. The numbers are the moments.
/// For VoiceOver it is one adjustable element; swipe up or down moves to the next or previous moment.
struct SpeedProfile: View {
    var trip: TripReplay
    var playhead: Int64
    var state: String
    var seek: (Int64) -> Void
    var pauseAt: (Int64) -> Void

    var body: some View {
        let duration = max(trip.durationNanos, 1)
        let maximum = max(trip.speed.compactMap { $0.values.max() }.max() ?? 1, 1)
        Canvas { context, size in
            let radius: CGFloat = 10
            let top = radius * 2 + 4
            let graph = size.height - top
            func x(_ time: Int64) -> CGFloat { CGFloat(Double(time) / Double(duration)) * size.width }
            context.fill(Path(roundedRect: CGRect(x: 0, y: top, width: size.width, height: graph), cornerRadius: 6),
                         with: .color(Palette.surfaceSunken))
            // GPS gaps: diagonal hatching, so the gap does not depend on colour.
            for gap in trip.gpsGaps {
                let left = x(gap.lowerBound)
                let right = x(gap.upperBound)
                var hatch = context
                hatch.clip(to: Path(CGRect(x: left, y: top, width: max(right - left, 1), height: graph)))
                var line = left - graph
                var path = Path()
                while line < right {
                    path.move(to: CGPoint(x: line, y: size.height))
                    path.addLine(to: CGPoint(x: line + graph, y: top))
                    line += 6
                }
                hatch.stroke(path, with: .color(Palette.inkMuted.opacity(0.45)), lineWidth: 1)
            }
            // The speed, one path for each run. Gaps stay open.
            for run in trip.speed where !run.times.isEmpty {
                var path = Path()
                for (index, time) in run.times.enumerated() {
                    let point = CGPoint(x: x(time), y: size.height - CGFloat(run.values[index] / maximum) * graph * 0.9)
                    if index == 0 { path.move(to: point) } else { path.addLine(to: point) }
                }
                var area = path
                area.addLine(to: CGPoint(x: x(run.times[run.times.count - 1]), y: size.height))
                area.addLine(to: CGPoint(x: x(run.times[0]), y: size.height))
                area.closeSubpath()
                context.fill(area, with: .color(Palette.accent.opacity(0.18)))
                context.stroke(path, with: .color(Palette.accent), style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            // Approximate positions: a dashed warning line at the base.
            for range in trip.approximate {
                var path = Path()
                path.move(to: CGPoint(x: x(range.lowerBound), y: size.height - 2))
                path.addLine(to: CGPoint(x: x(range.upperBound), y: size.height - 2))
                context.stroke(path, with: .color(Palette.warning), style: StrokeStyle(lineWidth: 3, dash: [6, 4]))
            }
            // A pause between segments is not replayed. A thin line marks where the next segment starts.
            for start in trip.segmentStarts {
                context.stroke(Path { $0.move(to: CGPoint(x: x(start), y: top)); $0.addLine(to: CGPoint(x: x(start), y: size.height)) },
                               with: .color(.primary.opacity(0.5)), lineWidth: 1)
            }
            // Numbered moments above the profile, with the same numbers as the map and the chips.
            for moment in trip.moments {
                let centre = CGPoint(x: min(max(x(moment.timeNanos), radius), size.width - radius), y: radius)
                context.fill(Path(ellipseIn: CGRect(x: centre.x - radius, y: centre.y - radius, width: radius * 2, height: radius * 2)),
                             with: .color(Palette.accent))
                context.draw(Text("\(moment.number)").font(.caption2.weight(.bold)).foregroundStyle(.white), at: centre)
                context.stroke(Path { $0.move(to: CGPoint(x: x(moment.timeNanos), y: top)); $0.addLine(to: CGPoint(x: x(moment.timeNanos), y: size.height)) },
                               with: .color(Palette.accent), lineWidth: 1)
            }
            // The playhead.
            let head = x(playhead)
            context.stroke(Path { $0.move(to: CGPoint(x: head, y: top - 2)); $0.addLine(to: CGPoint(x: head, y: size.height)) },
                           with: .color(.primary), lineWidth: 2)
            context.fill(Path(ellipseIn: CGRect(x: head - 5, y: top - 5, width: 10, height: 10)), with: .color(.primary))
        }
        .frame(height: 76)
        .contentShape(.rect)
        .gesture(SpatialTapGesture().onEnded { seek(time($0.location.x, duration)) })
        // A horizontal drag moves the playhead. A vertical drag scrolls the panel.
        .simultaneousGesture(DragGesture(minimumDistance: 6).onChanged { value in
            if abs(value.translation.width) > abs(value.translation.height) { seek(time(value.location.x, duration)) }
        })
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .accessibilityElement()
        .accessibilityLabel(Text("Trip timeline with speed"))
        .accessibilityValue(Text(state))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if let next = trip.nextMoment(after: playhead) { pauseAt(next.timeNanos) }
            case .decrement: if let previous = trip.previousMoment(before: playhead) { pauseAt(previous.timeNanos) }
            @unknown default: break
            }
        }
        .accessibilityIdentifier("replay_profile")
    }

    @State private var width: CGFloat = 1

    private func time(_ x: CGFloat, _ duration: Int64) -> Int64 {
        Int64(Double(min(max(x / max(width, 1), 0), 1)) * Double(duration))
    }
}
