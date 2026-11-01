import Charts
import CVKEGPSCore
import SwiftUI

/// Speed, height, and along-track acceleration with Swift Charts and one shared playhead. A tap or a horizontal drag on any
/// chart moves the playhead for all charts, the values, and the map. A vertical drag scrolls the panel.
/// With `single`, a selector shows one chart at a time.
struct ReplayCharts: View {
    var trip: TripReplay
    var playhead: Int64
    var heightOffset: Double
    var seaLevel: Bool
    var state: String
    var single: ReplayChartKind?
    var select: (ReplayChartKind) -> Void
    var seek: (Int64) -> Void
    var pauseAt: (Int64) -> Void

    @Environment(\.displayFormat) private var format

    var body: some View {
        let specs = ChartSpec.all(trip, heightOffset: heightOffset, format: format)
        let shown = single.map { kind in specs.filter { $0.kind == kind }.prefix(1) }.map(Array.init) ?? specs
        VStack(alignment: .leading, spacing: Space.s3) {
            if single != nil && specs.count > 1 {
                Picker("Chart", selection: Binding(get: { shown.first?.kind ?? .speed }, set: select)) {
                    ForEach(specs) { spec in Text(title(spec.kind)).tag(spec.kind) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("chart_selector")
            }
            ForEach(shown.isEmpty ? Array(specs.prefix(1)) : shown) { spec in
                if single == nil { Text(title(spec.kind)).font(.subheadline.weight(.semibold)) }
                ReplayChart(spec: spec, trip: trip, playhead: playhead, state: state, seek: seek, pauseAt: pauseAt)
                    .frame(height: 120)
            }
        }
    }

    private func title(_ kind: ReplayChartKind) -> LocalizedStringKey {
        switch kind {
        case .speed: "Speed"
        case .height: seaLevel ? "Altitude above sea level" : "GPS height (WGS 84)"
        case .acceleration: "Acceleration"
        }
    }
}

/// One chart's values and how to show them. `label` formats a value in display units.
struct ChartSpec: Identifiable {
    var kind: ReplayChartKind
    var runs: [ReplayRun]
    /// Hatched times: GPS gaps for speed, gaps in the record for height and acceleration.
    var gaps: [ClosedRange<Int64>]
    var approximate: [ClosedRange<Int64>]
    var moments: [ReplayMoment]
    var low: Double
    var high: Double
    var zeroLine: Bool
    var summary: String
    var label: (Double) -> String

    var id: ReplayChartKind { kind }

    static func all(_ trip: TripReplay, heightOffset: Double, format: DisplayFormat) -> [ChartSpec] {
        var specs: [ChartSpec] = []
        let speedHigh = max(trip.speed.compactMap { $0.values.max() }.max() ?? 1, 1)
        specs.append(ChartSpec(kind: .speed, runs: trip.speed, gaps: trip.gpsGaps, approximate: trip.approximate,
                               moments: trip.moments.filter { $0.kind == .fastestSpeed }, low: 0, high: speedHigh * 1.1, zeroLine: false,
                               summary: summary(.speed, trip.speed, format, heightOffset), label: { format.speed($0) }))
        if !trip.height.isEmpty {
            let low = trip.height.compactMap { $0.values.min() }.min() ?? 0
            let high = trip.height.compactMap { $0.values.max() }.max() ?? 0
            // A flat trip still shows a band of at least 10 m, so noise does not fill the chart.
            let middle = (low + high) / 2
            let half = max((high - low) / 2 * 1.1, 5)
            specs.append(ChartSpec(kind: .height, runs: trip.height, gaps: ReplayChartData.uncovered(trip.height, durationNanos: trip.durationNanos),
                                   approximate: [], moments: trip.moments.filter { $0.kind == .highestPoint },
                                   low: middle - half, high: middle + half, zeroLine: false,
                                   summary: summary(.height, trip.height, format, heightOffset),
                                   label: { format.altitude($0 - heightOffset) }))
        }
        if !trip.acceleration.isEmpty {
            let extreme = max((trip.acceleration.compactMap { $0.values.map(abs).max() }.max() ?? 1) * 1.1, 1)
            specs.append(ChartSpec(kind: .acceleration, runs: trip.acceleration,
                                   gaps: ReplayChartData.uncovered(trip.acceleration, durationNanos: trip.durationNanos), approximate: [],
                                   moments: trip.moments.filter { $0.kind == .acceleration || $0.kind == .braking },
                                   low: -extreme, high: extreme, zeroLine: true,
                                   summary: summary(.acceleration, trip.acceleration, format, heightOffset),
                                   label: { format.acceleration($0) }))
        }
        return specs
    }

    /// A text summary for VoiceOver.
    private static func summary(_ kind: ReplayChartKind, _ runs: [ReplayRun], _ format: DisplayFormat, _ offset: Double) -> String {
        let values = runs.flatMap { run in run.times.indices.map { (run.times[$0], run.values[$0]) } }
        guard let low = values.map(\.1).min(), let high = values.max(by: { $0.1 < $1.1 }) else { return String(localized: "No values") }
        switch kind {
        case .speed:
            return String(localized: "Speed from \(format.speed(low)) to \(format.speed(high.1)). Fastest at \(format.duration(high.0)).")
        case .height:
            return String(localized: "Height from \(format.altitude(low - offset)) to \(format.altitude(high.1 - offset)). Highest at \(format.duration(high.0)).")
        case .acceleration:
            return String(localized: "Braking up to \(format.acceleration(abs(min(low, 0)))). Acceleration up to \(format.acceleration(max(high.1, 0))).")
        }
    }
}

private struct ChartPoint: Identifiable {
    var id: Int
    var run: Int
    var seconds: Double
    var value: Double
}

private struct ReplayChart: View {
    var spec: ChartSpec
    var trip: TripReplay
    var playhead: Int64
    var state: String
    var seek: (Int64) -> Void
    var pauseAt: (Int64) -> Void

    @Environment(\.displayFormat) private var format
    @State private var width: Int = 300

    var body: some View {
        let duration = Double(max(trip.durationNanos, 1)) / 1e9
        // The values are reduced to one minimum and one maximum for each column, so a long trip draws as fast as a short one.
        let points = Self.points(spec.runs, durationNanos: max(trip.durationNanos, 1), columns: width)
        Chart {
            if spec.zeroLine {
                RuleMark(y: .value("Zero", 0)).foregroundStyle(.secondary.opacity(0.5)).lineStyle(StrokeStyle(lineWidth: 1))
            }
            ForEach(spec.approximate, id: \.lowerBound) { range in
                RuleMark(xStart: .value("Start", Double(range.lowerBound) / 1e9), xEnd: .value("End", Double(range.upperBound) / 1e9),
                         y: .value("Base", spec.low))
                    .foregroundStyle(Palette.warning)
                    .lineStyle(StrokeStyle(lineWidth: 3, dash: [6, 4]))
            }
            ForEach(trip.segmentStarts, id: \.self) { start in
                RuleMark(x: .value("Segment", Double(start) / 1e9)).foregroundStyle(.primary.opacity(0.4)).lineStyle(StrokeStyle(lineWidth: 1))
            }
            ForEach(points) { point in
                LineMark(x: .value("Time", point.seconds), y: .value("Value", point.value), series: .value("Run", point.run))
                    .foregroundStyle(Palette.accent)
                    .lineStyle(StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
            }
            ForEach(spec.moments) { moment in
                RuleMark(x: .value("Moment", Double(moment.timeNanos) / 1e9))
                    .foregroundStyle(Palette.accent.opacity(0.6))
                    .lineStyle(StrokeStyle(lineWidth: 1))
                    .annotation(position: .top, spacing: 2) { MomentMarker(number: moment.number) }
            }
            RuleMark(x: .value("Playhead", Double(playhead) / 1e9))
                .foregroundStyle(.primary)
                .lineStyle(StrokeStyle(lineWidth: 2))
        }
        .chartXScale(domain: 0...duration)
        .chartYScale(domain: spec.low...spec.high)
        .chartXAxis {
            AxisMarks(values: .automatic(desiredCount: 4)) { value in
                AxisGridLine()
                AxisValueLabel { if let seconds = value.as(Double.self) { Text(format.clock(Int64(seconds * 1e9))) } }
            }
        }
        .chartYAxis {
            AxisMarks(position: .leading, values: .automatic(desiredCount: 3)) { value in
                AxisGridLine()
                AxisValueLabel { if let number = value.as(Double.self) { Text(spec.label(number)) } }
            }
        }
        .chartBackground { proxy in
            GeometryReader { geometry in
                if let anchor = proxy.plotFrame {
                    let frame = geometry[anchor]
                    // Gaps: diagonal hatching, so the gap does not depend on colour.
                    Canvas { context, _ in
                        for gap in spec.gaps {
                            guard let left = proxy.position(forX: Double(gap.lowerBound) / 1e9),
                                  let right = proxy.position(forX: Double(gap.upperBound) / 1e9) else { continue }
                            let rect = CGRect(x: frame.minX + left, y: frame.minY, width: max(right - left, 1), height: frame.height)
                            var hatch = context
                            hatch.clip(to: Path(rect))
                            var path = Path()
                            var line = rect.minX - rect.height
                            while line < rect.maxX {
                                path.move(to: CGPoint(x: line, y: rect.maxY))
                                path.addLine(to: CGPoint(x: line + rect.height, y: rect.minY))
                                line += 6
                            }
                            hatch.stroke(path, with: .color(Palette.inkMuted.opacity(0.4)), lineWidth: 1)
                        }
                    }
                }
            }
        }
        .chartOverlay { proxy in
            GeometryReader { geometry in
                Rectangle().fill(.clear).contentShape(.rect)
                    .gesture(SpatialTapGesture().onEnded { value in
                        guard let time = time(at: value.location.x, proxy, geometry) else { return }
                        // A tap near a moment number goes to that moment and pauses. Other taps move the playhead.
                        if let near = spec.moments.min(by: { abs($0.timeNanos - time) < abs($1.timeNanos - time) }),
                           let x = proxy.position(forX: Double(near.timeNanos) / 1e9),
                           let anchor = proxy.plotFrame, abs(geometry[anchor].minX + x - value.location.x) <= 16 {
                            pauseAt(near.timeNanos)
                        } else {
                            seek(time)
                        }
                    })
                    .simultaneousGesture(DragGesture(minimumDistance: 6).onChanged { value in
                        if abs(value.translation.width) > abs(value.translation.height), let time = time(at: value.location.x, proxy, geometry) {
                            seek(time)
                        }
                    })
            }
        }
        .onGeometryChange(for: Int.self) { Int($0.size.width) } action: { width = max($0, 1) }
        .padding(.top, spec.moments.isEmpty ? 0 : 20)
        .accessibilityElement()
        .accessibilityLabel(Text(spec.summary))
        .accessibilityValue(Text(state))
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: if let next = trip.nextMoment(after: playhead) { pauseAt(next.timeNanos) }
            case .decrement: if let previous = trip.previousMoment(before: playhead) { pauseAt(previous.timeNanos) }
            @unknown default: break
            }
        }
        .accessibilityIdentifier("chart_\(spec.kind)")
    }

    private func time(at x: CGFloat, _ proxy: ChartProxy, _ geometry: GeometryProxy) -> Int64? {
        guard let anchor = proxy.plotFrame else { return nil }
        let frame = geometry[anchor]
        guard let seconds: Double = proxy.value(atX: x - frame.minX) else { return nil }
        return Int64(min(max(seconds, 0), Double(trip.durationNanos) / 1e9) * 1e9)
    }

    private static func points(_ runs: [ReplayRun], durationNanos: Int64, columns: Int) -> [ChartPoint] {
        var points: [ChartPoint] = []
        let duration = Double(durationNanos) / 1e9
        for (run, columnsOfRun) in ReplayChartData.columns(runs, durationNanos: durationNanos, columns: columns).enumerated() {
            for column in columnsOfRun {
                let seconds = (Double(column.index) + 0.5) / Double(columns) * duration
                points.append(ChartPoint(id: points.count, run: run, seconds: seconds, value: column.minimum))
                if column.maximum != column.minimum {
                    points.append(ChartPoint(id: points.count, run: run, seconds: seconds, value: column.maximum))
                }
            }
        }
        return points
    }
}
