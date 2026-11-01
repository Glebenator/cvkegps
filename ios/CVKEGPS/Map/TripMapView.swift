import CoreLocation
import CVKEGPSCore
import MapKit
import SwiftUI

/// The newest plotted position of a recording trip, or the replay position. `fresh` is false when that position is old.
struct MapPosition: Equatable {
    var sample: TrackSample
    var fresh: Bool
}

/// A numbered moment on a replay map. `description` is the text for VoiceOver.
struct MapMoment: Identifiable, Equatable {
    var number: Int
    var latitudeRadians: Double
    var longitudeRadians: Double
    var description: String

    var id: Int { number }
}

/// The kind of trip map. A live map and a replay follow a position. A saved map fits the route.
enum TripMapMode: Equatable {
    case live, saved, replay
}

/// How the map follows the position. A map gesture turns following off.
private enum FollowMode {
    case off, north, course
}

/// The trip map with Apple Maps. The route, the start and end markers, and the app's own position marker: a position,
/// an accuracy circle, and a direction arrow from the fixes that the app already receives, so an old position stays grey
/// and location has one request (as ADR 0002 decides for Android).
struct TripMapView: View {
    var lines: [[TrackSample]]
    var routeReady: Bool
    var mode: TripMapMode
    var position: MapPosition?
    var moments: [MapMoment] = []
    /// For each line, how many points the replay has passed. The map draws them in the route colour and the rest muted.
    var played: [Int]?
    /// True while a replay plays. The camera then moves evenly between the playhead steps.
    var playing = false
    /// The height of the panel over the bottom of the map.
    var bottomInset: CGFloat = 0

    @Environment(AppModel.self) private var model
    @Environment(\.colorScheme) private var scheme
    @State private var camera: MapCameraPosition = .automatic
    @State private var route = MapRoute()
    @State private var follow: FollowMode = .off
    @State private var userZoomed = false
    @State private var placed = false
    @State private var replayStart = true
    @State private var cameraState = MapCameraState()
    @State private var selectedMarker: String?
    @State private var size: CGSize = .zero

    private var settings: SettingsValues { model.settings.values }
    private var follows: Bool { mode != .saved }
    private var bySpeed: Bool { mode != .live && settings.routeColour == .speed }

    var body: some View {
        // Only the map goes under the status bar, the navigation bar, and the home indicator. The overlays stay in the
        // safe area, so they do not cover the clock or the toolbar buttons. The size is the free area for the route fit.
        GeometryReader { geometry in
            Map(position: $camera) { content }
                .mapStyle(style)
                .mapControls {
                    MapCompass()
                    MapScaleView()
                }
                .safeAreaPadding(.bottom, bottomInset)
                .ignoresSafeArea()
                .onMapCameraChange(frequency: .continuous) { context in cameraState.camera = context.camera }
                .onChange(of: camera.positionedByUser) { _, byUser in if byUser { follow = .off } }
                .onAppear {
                    size = geometry.size
                    if follows { follow = settings.mapStart == .course ? .course : .north }
                    route.set(lines, bySpeed: bySpeed, throttled: false)
                }
                .onChange(of: geometry.size) { _, new in size = new }
        }
        // The map controls and the legend stop growing at the largest standard text size, as in Apple Maps, so they do
        // not cover each other and the route. Their VoiceOver labels give the same information.
        .overlay(alignment: .topTrailing) {
            controls.padding(.trailing, Space.s3).padding(.top, Space.s2).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }
        .overlay(alignment: .topLeading) {
            legend.padding(.leading, Space.s3).padding(.top, Space.s2).dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }
        .overlay(alignment: .bottom) { note.padding(.bottom, bottomInset + Space.s6) }
        .onChange(of: lines) { _, new in route.set(new, bySpeed: bySpeed, throttled: mode == .live) }
        .onChange(of: bySpeed) { _, speed in route.set(lines, bySpeed: speed, throttled: false) }
        .onChange(of: route.builds) { _, _ in fitOnce() }
        .onChange(of: position) { _, _ in moveCamera() }
        .onChange(of: follow) { _, _ in moveCamera() }
        .onChange(of: mode) { _, new in
            // The saved trip map changes to replay mode and back. The map stays, so it does not load again.
            follow = new == .saved ? .off : (settings.mapStart == .course ? .course : .north)
            userZoomed = false
            replayStart = new == .replay
            moveCamera()
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel(Text("Recorded route. Use the trip values for measurements. Gaps are not connected."))
        .accessibilityValue(positionState)
        .accessibilityIdentifier("route_map")
    }

    // MARK: Map content

    @MapContentBuilder private var content: some MapContent {
        let routeColor = Palette.accent
        let outline = scheme == .dark || settings.mapLayer == .satellite ? Color.white : Color(rgb: SpeedRamp.outlineOnLight)
        ForEach(route.lines) { line in
            let count = min(max(played.flatMap { $0.indices.contains(line.id) ? $0[line.id] : nil } ?? line.coordinates.count, 0), line.coordinates.count)
            let start = max(count - 1, 0)
            let done = Array(line.coordinates.prefix(count))
            let rest = count < line.coordinates.count ? Array(line.coordinates[start...]) : []
            if let doneGradient = line.gradient(from: 0, to: max(count - 1, 0), alpha: 1), done.count > 1 {
                MapPolyline(coordinates: done).stroke(outline, style: Self.outlineStyle)
                MapPolyline(coordinates: done).stroke(doneGradient, style: Self.lineStyle)
            } else if line.positions == nil, done.count > 1 {
                MapPolyline(coordinates: done)
                    .stroke(line.approximate ? Palette.warning : routeColor, style: line.approximate ? Self.dashStyle : Self.lineStyle)
            }
            if rest.count > 1 {
                if let restGradient = line.gradient(from: start, to: line.coordinates.count - 1, alpha: 0.4) {
                    MapPolyline(coordinates: rest).stroke(outline.opacity(0.4), style: Self.outlineStyle)
                    MapPolyline(coordinates: rest).stroke(restGradient, style: Self.lineStyle)
                } else {
                    MapPolyline(coordinates: rest)
                        .stroke((line.approximate ? Palette.warning : routeColor).opacity(0.4), style: line.approximate ? Self.dashStyle : Self.lineStyle)
                }
            }
        }
        if let first = lines.first(where: { !$0.isEmpty })?.first {
            endpoint(first, id: "start", name: String(localized: "Start"), color: Palette.startMarker)
        }
        // While recording, the position marker shows the newest point instead of an end marker.
        if mode != .live, let last = lines.last(where: { !$0.isEmpty })?.last {
            endpoint(last, id: "end", name: String(localized: "End"), color: Palette.endMarker)
        }
        if let position {
            let coordinate = position.sample.coordinate
            if settings.mapAccuracyCircle, position.fresh, position.sample.usablePosition, let accuracy = position.sample.accuracyMeters, accuracy > 0 {
                MapCircle(center: coordinate, radius: accuracy).foregroundStyle(routeColor.opacity(0.18))
            }
            Annotation(coordinate: coordinate, anchor: .center) {
                PositionMarker(position: position, cameraState: cameraState)
            } label: {
                EmptyView()
            }
        }
        ForEach(moments) { moment in
            Annotation(coordinate: CLLocationCoordinate2D(latitude: moment.latitudeRadians * 180 / .pi, longitude: moment.longitudeRadians * 180 / .pi),
                       anchor: .center) {
                MomentMarker(number: moment.number).accessibilityLabel(Text(moment.description))
            } label: {
                EmptyView()
            }
        }
    }

    private static let lineStyle = StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
    private static let outlineStyle = StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round)
    /// A network position is approximate. Its line is dashed and has the warning colour, so the colour is not the only sign.
    private static let dashStyle = StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round, dash: [8, 6])

    private func endpoint(_ sample: TrackSample, id: String, name: String, color: Color) -> some MapContent {
        let time = Date(timeIntervalSince1970: Double(sample.timeMillis) / 1000).formatted(date: .abbreviated, time: .shortened)
        return Annotation(coordinate: sample.coordinate, anchor: .center) {
            EndpointMarker(name: name, time: time, color: color, selected: selectedMarker == id, opensMaps: mode == .saved,
                           coordinate: sample.coordinate) {
                selectedMarker = selectedMarker == id ? nil : id
            }
        } label: {
            EmptyView()
        }
    }

    private var style: MapStyle {
        let elevation: MapStyle.Elevation = settings.map3dBuildings ? .realistic : .flat
        // Traffic is current, so it shows only on the map of the current trip.
        let traffic = mode == .live && settings.mapTraffic
        switch settings.mapLayer {
        case .standard: return .standard(elevation: elevation, emphasis: .automatic, pointsOfInterest: .all, showsTraffic: traffic)
        case .muted: return .standard(elevation: elevation, emphasis: .muted, pointsOfInterest: .excludingAll, showsTraffic: traffic)
        case .satellite:
            return settings.mapSatelliteLabels ? .hybrid(elevation: elevation, pointsOfInterest: .all, showsTraffic: traffic)
                : .imagery(elevation: elevation)
        }
    }

    // MARK: Camera

    /// The camera distance for a Web Mercator zoom level, so the zoom levels of the Android app keep their meaning.
    private static func distance(zoom: Double) -> Double { 1_000 * pow(2, 16 - zoom) }

    /// A street scale at low speed and a wider view at road and plane speeds.
    private static func speedZoom(_ sample: TrackSample) -> Double? {
        guard let speed = sample.usableSpeed else { return nil }
        return speed < 3 ? 17 : speed < 10 ? 16 : speed < 25 ? 15 : speed < 60 ? 14 : 11
    }

    private func moveCamera() {
        guard follows, follow != .off, let sample = position?.sample else { return }
        let current = cameraState.camera
        let distance: Double
        if mode == .replay {
            // A replay keeps one zoom, so the map does not jump in and out with the speed. The zoom buttons change it.
            distance = replayStart || !placed ? Self.distance(zoom: 16) : (current?.distance ?? Self.distance(zoom: 16))
        } else if !placed {
            distance = settings.mapZoomWithSpeed ? Self.distance(zoom: Self.speedZoom(sample) ?? 15) : Self.distance(zoom: 16)
        } else if userZoomed || !settings.mapZoomWithSpeed {
            distance = current?.distance ?? Self.distance(zoom: 16)
        } else {
            distance = Self.speedZoom(sample).map(Self.distance(zoom:)) ?? current?.distance ?? Self.distance(zoom: 16)
        }
        let course = follow == .course
        let bearing = course ? (sample.courseRadians.map { $0 * 180 / .pi } ?? current?.heading ?? 0) : 0
        let pitch = course && settings.mapTilt ? 45.0 : 0
        let next = MapCameraPosition.camera(MapCamera(centerCoordinate: sample.coordinate, distance: distance, heading: bearing, pitch: pitch))
        if placed && (mode != .replay || replayStart) {
            // One short camera move for each new fix. There is no continuous animation.
            withAnimation(.easeInOut(duration: 0.8)) { camera = next }
        } else if mode == .replay && playing {
            // A playing replay moves evenly between the playhead steps.
            withAnimation(.linear(duration: 0.1)) { camera = next }
        } else {
            camera = next
        }
        placed = true
        replayStart = false
    }

    /// A review map fits the route once. Route updates must not reset a user pan.
    private func fitOnce() {
        guard !placed, !(follows && follow != .off && position != nil) else { return }
        fit()
    }

    private func fit() {
        let points = lines.flatMap { $0 }
        // The free map area is between the navigation bar and the panel. The route keeps a margin on each side that is
        // wider than the map controls, so the controls and the speed legend do not cover it.
        let freeWidth = max(size.width, 1)
        let freeHeight = max(size.height - bottomInset, 1)
        let margin = 2 * 48 + 64.0
        guard let frame = RouteMath.frame(points, width: max(freeWidth - margin, 48), height: max(freeHeight - margin, 48)) else { return }
        // The world is 256 points wide at zoom 0. MapKit fills the free area with the rect, so the rect has the size of
        // the whole free area at the frame's zoom, not the size of the route. Then the margins stay.
        let scale = MKMapSize.world.width / (256 * pow(2, frame.zoom))
        let centre = MKMapPoint(CLLocationCoordinate2D(latitude: frame.latitudeRadians * 180 / .pi,
                                                       longitude: frame.longitudeRadians * 180 / .pi))
        let rect = MKMapRect(x: centre.x - freeWidth * scale / 2, y: centre.y - freeHeight * scale / 2,
                             width: freeWidth * scale, height: freeHeight * scale)
        withAnimation(placed ? .easeInOut(duration: 0.5) : nil) { camera = .rect(rect) }
        placed = true
    }

    private func zoom(_ factor: Double) {
        guard let current = cameraState.camera else { return }
        userZoomed = true
        withAnimation(.easeInOut(duration: 0.3)) {
            camera = .camera(MapCamera(centerCoordinate: current.centerCoordinate, distance: min(max(current.distance * factor, 150), 40_000_000),
                                       heading: current.heading, pitch: current.pitch))
        }
    }

    // MARK: Overlays

    private var controls: some View {
        let hasRoute = lines.contains { !$0.isEmpty }
        return GlassEffectContainer(spacing: Space.s2) {
            VStack(spacing: Space.s2) {
                MapLayerMenu(live: mode == .live)
                if follows {
                    Button {
                        // The first selection follows with north up. The next selection turns the map to the direction of travel.
                        follow = follow == .north ? .course : .north
                        userZoomed = false
                    } label: {
                        Image(systemName: follow == .course ? "location.north.line.fill" : follow == .north ? "location.fill" : "location")
                            .frame(width: 24, height: 24)
                    }
                    .disabled(!hasRoute && position == nil)
                    .accessibilityLabel(follow == .course ? Text("Follow route with the direction of travel up") : Text("Follow route"))
                    .accessibilityValue(followState)
                    .accessibilityIdentifier("map_follow")
                } else {
                    Button(action: fit) { Image(systemName: "arrow.up.left.and.arrow.down.right").frame(width: 24, height: 24) }
                        .disabled(!hasRoute)
                        .accessibilityLabel(Text("Fit route"))
                        .accessibilityIdentifier("map_fit")
                }
                Button { zoom(0.5) } label: { Image(systemName: "plus").frame(width: 24, height: 24) }
                    .accessibilityLabel(Text("Zoom in"))
                    .accessibilityIdentifier("map_zoom_in")
                Button { zoom(2) } label: { Image(systemName: "minus").frame(width: 24, height: 24) }
                    .accessibilityLabel(Text("Zoom out"))
                    .accessibilityIdentifier("map_zoom_out")
            }
            .buttonStyle(.glass)
            .buttonBorderShape(.circle)
            .controlSize(.large)
        }
    }

    private var followState: Text {
        switch follow {
        case .off: Text("Not following")
        case .north: Text("Following with north up")
        case .course: Text("Following with the direction of travel up")
        }
    }

    private var positionState: Text {
        guard let position else { return Text("") }
        if !position.fresh { return Text("Position: old. The last position is more than 10 seconds old.") }
        if position.sample.source == .network { return Text("Position: approximate, from Wi-Fi and mobile networks") }
        return Text("Position: current")
    }

    @ViewBuilder private var legend: some View {
        if bySpeed, let scale = route.scale, route.lines.contains(where: { $0.positions != nil }) {
            SpeedLegend(scale: scale)
        }
    }

    @ViewBuilder private var note: some View {
        // A route that is still loading is not reported as empty.
        if routeReady && lines.allSatisfy(\.isEmpty) && position == nil {
            Text("This trip has no positions to show on the map.")
                .font(.footnote)
                .padding(.horizontal, Space.s3)
                .padding(.vertical, Space.s2)
                .glassEffect(.regular, in: .capsule)
        }
    }
}

/// The newest map camera. Only the position marker reads it in its body, so a camera gesture redraws only the marker.
@MainActor
@Observable
final class MapCameraState {
    var camera: MapCamera?
}

extension TrackSample {
    var coordinate: CLLocationCoordinate2D { CLLocationCoordinate2D(latitude: latitudeDegrees, longitude: longitudeDegrees) }
}

/// The map type, live traffic on the current trip map, and the route colour on other maps. The settings store the choice.
private struct MapLayerMenu: View {
    var live: Bool
    @Environment(AppModel.self) private var model

    var body: some View {
        let settings = model.settings
        Menu {
            Picker(selection: Binding(get: { settings.values.mapLayer }, set: { settings.set(\.mapLayer, $0) })) {
                Text("Standard").tag(MapLayer.standard)
                Text("Muted").tag(MapLayer.muted)
                Text("Satellite").tag(MapLayer.satellite)
            } label: {
                Text("Map type")
            }
            if live {
                Toggle("Traffic", isOn: Binding(get: { settings.values.mapTraffic }, set: { settings.set(\.mapTraffic, $0) }))
            } else {
                Picker(selection: Binding(get: { settings.values.routeColour }, set: { settings.set(\.routeColour, $0) })) {
                    Text("Route coloured by speed").tag(RouteColour.speed)
                    Text("Route in one colour").tag(RouteColour.single)
                } label: {
                    Text("Route colour")
                }
            }
        } label: {
            Image(systemName: "square.3.layers.3d").frame(width: 24, height: 24)
        }
        .accessibilityLabel(Text("Map type"))
        .accessibilityIdentifier("map_layers")
    }
}

/// The speeds at the two ends of the route colours, with the colour scale between them.
private struct SpeedLegend: View {
    var scale: SpeedScale
    @Environment(\.displayFormat) private var format

    var body: some View {
        let slow = format.speed(scale.slowMetersPerSecond)
        let fast = format.speed(scale.fastMetersPerSecond)
        HStack(spacing: Space.s2) {
            Text(slow)
            LinearGradient(colors: SpeedRamp.stops.map(Color.init(rgb:)), startPoint: .leading, endPoint: .trailing)
                .frame(width: 72, height: 8)
                .clipShape(.capsule)
            Text(fast)
        }
        .font(.caption.weight(.medium).monospacedDigit())
        .padding(.horizontal, Space.s3)
        .padding(.vertical, Space.s2)
        .glassEffect(.regular, in: .capsule)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Route colour shows speed: dark is \(slow) or slower, yellow is \(fast) or faster."))
        .accessibilityIdentifier("speed_legend")
    }
}

/// The position: a dot with a white edge, grey when old, a ring for a network position, and an arrow for the direction
/// of travel while moving with a current fix. The arrow keeps the true direction when the map turns.
private struct PositionMarker: View {
    var position: MapPosition
    var cameraState: MapCameraState

    var body: some View {
        let approximate = position.sample.source == .network
        let color = position.fresh ? Palette.accent : Palette.oldPosition
        ZStack {
            if position.fresh, let course = position.sample.courseRadians {
                Arrow()
                    .fill(color)
                    .stroke(.white, lineWidth: 2)
                    .frame(width: 18, height: 18)
                    .offset(y: -20)
                    .rotationEffect(.degrees(course * 180 / .pi - (cameraState.camera?.heading ?? 0)))
            }
            Circle().fill(.white).frame(width: 22, height: 22).shadow(radius: 2)
            if approximate {
                Circle().stroke(color, lineWidth: 4).frame(width: 12, height: 12)
            } else {
                Circle().fill(color).frame(width: 16, height: 16)
            }
        }
        .frame(width: 64, height: 64)
        .accessibilityElement()
        .accessibilityLabel(!position.fresh ? Text("Position: old") : approximate ? Text("Position: approximate") : Text("Position: current"))
        .accessibilityIdentifier("map_position")
    }
}

private struct Arrow: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.midX, y: rect.maxY - rect.height * 0.25))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.maxY))
        path.closeSubpath()
        return path
    }
}

/// A start or end marker. Selecting it shows its time. On a saved trip, it can open the place in Apple Maps.
private struct EndpointMarker: View {
    var name: String
    var time: String
    var color: Color
    var selected: Bool
    var opensMaps: Bool
    var coordinate: CLLocationCoordinate2D
    var toggle: () -> Void

    var body: some View {
        VStack(spacing: Space.s1) {
            if selected {
                HStack(spacing: Space.s2) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(name).font(.caption.weight(.semibold))
                        Text(time).font(.caption2).foregroundStyle(.secondary)
                    }
                    if opensMaps {
                        Button {
                            let item = MKMapItem(location: CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude), address: nil)
                            item.name = name
                            item.openInMaps()
                        } label: {
                            Image(systemName: "map")
                        }
                        .buttonStyle(.glass)
                        .accessibilityLabel(Text("Open in Maps"))
                    }
                }
                .padding(.horizontal, Space.s3)
                .padding(.vertical, Space.s2)
                .glassEffect(.regular, in: .capsule)
                .fixedSize()
            }
            Button(action: toggle) {
                Circle().fill(color).frame(width: 14, height: 14)
                    .overlay(Circle().stroke(.white, lineWidth: 3))
                    .frame(width: 44, height: 44)
                    .contentShape(.circle)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(Text("\(name), \(time)"))
        }
    }
}

/// A white number on an accent circle with a white edge, as on the profile and the chips.
struct MomentMarker: View {
    var number: Int

    var body: some View {
        Text("\(number)")
            .font(.caption.weight(.bold))
            .foregroundStyle(.white)
            .frame(minWidth: 24, minHeight: 24)
            .background(Palette.accent, in: .circle)
            .overlay(Circle().stroke(.white, lineWidth: 2))
    }
}
