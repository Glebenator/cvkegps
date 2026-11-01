import CoreTransferable
import CVKEGPSCore
import SwiftUI
import UniformTypeIdentifiers

/// A saved trip: a full-screen map and a glass panel with the totals. Replay changes the panel and keeps the same map,
/// so the map does not load again. Back leaves the charts, then the replay (ADR 0004).
struct SavedTripView: View {
    var id: String

    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.verticalSizeClass) private var verticalSize
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var detail: StoredRead<TripDetail?> = .loading
    @State private var replaying = false
    @State private var replay: ReplayModel
    @State private var details = false
    @State private var panelHeight: CGFloat = 0
    @State private var contentHeight: CGFloat = 0
    @State private var showSaved = false

    init(id: String) {
        self.id = id
        _replay = State(initialValue: ReplayModel(tripId: id))
    }

    var body: some View {
        let shown = detail.value ?? nil
        // The list record is already in memory. The screen uses it until the route summary arrives.
        let trip = shown?.trip ?? model.library.trips.value?.first { $0.id == id }
        let replayTrip = replaying ? replay.replay : nil
        GeometryReader { geometry in
            ZStack(alignment: .bottom) {
                TripMapView(lines: replayTrip?.lines ?? shown?.summary.lines ?? [],
                            routeReady: replayTrip != nil || shown != nil,
                            mode: replaying ? .replay : .saved,
                            position: replayPosition(replayTrip),
                            moments: moments(replayTrip),
                            played: replayTrip.map { trip in trip.lines.indices.map { trip.playedPoints(line: $0, at: replay.playhead) } },
                            playing: replay.playing,
                            bottomInset: panelHeight)
                let panel = VStack(alignment: .leading, spacing: Space.s3) {
                    if replaying {
                        handle
                        ReplayPane(replay: replay, heightOffset: heightOffset(trip), seaLevel: seaLevel(trip), singleChart: singleChart)
                    } else {
                        savedPanel(trip, shown)
                    }
                }
                .padding(Space.s4)
                // The panel takes the height of its content. Content that is too tall scrolls inside the panel.
                let limit = geometry.size.height * (replay.details && replaying ? 0.72 : 0.55)
                VStack(spacing: Space.s2) {
                    // Near the panel, so it does not cover the map controls at the top.
                    if showSaved { savedBanner }
                    ScrollView {
                        panel.onGeometryChange(for: CGFloat.self) { $0.size.height } action: { contentHeight = $0 }
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    .frame(height: min(max(contentHeight, 1), limit))
                    .glassEffect(.regular, in: .rect(cornerRadius: 32))
                }
                .padding(.horizontal, Space.s3)
                .padding(.bottom, Space.s2)
                .frame(maxWidth: 640)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { panelHeight = $0 + Space.s4 }
            }
        }
        .navigationTitle(replaying ? String(localized: "Trip replay") : trip.map { tripTime($0.startedMillis) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(.hidden, for: .tabBar)
        .navigationBarBackButtonHidden(replaying)
        .toolbar { toolbar(trip, shown) }
        .sheet(isPresented: $details) {
            if let shown { SavedDetailsSheet(detail: shown).presentationDetents([.medium, .large]) }
        }
        // The trip reads again after a delete, and when the recorder starts, pauses, or ends a trip. Stop and save opens
        // this screen before the recorder has closed the trip.
        .task(id: ReadKey(revision: model.library.revision, activeTrip: model.recorder.state.trip?.id,
                          status: model.recorder.state.trip?.status)) { await read() }
        .task(id: replaying) { if replaying { await replay.load(from: model.library) } }
        .onChange(of: scenePhase) { _, phase in if phase != .active { replay.pause() } }
        .onChange(of: replaying) { _, active in
            if !active {
                replay.pause()
                replay.details = false
            }
            // The replay shows the saved trip, so it closes the offer to continue the trip.
            if active { showSaved = false }
        }
        .task(id: model.trips.justSaved) {
            // Stop and save opens this screen. For about 10 seconds the trip can continue, which undoes an accidental stop.
            guard model.trips.justSaved == id else { return }
            showSaved = true
            try? await Task.sleep(for: .seconds(10))
            showSaved = false
            if model.trips.justSaved == id { model.trips.justSaved = nil }
        }
    }

    private struct ReadKey: Equatable {
        var revision: Int
        var activeTrip: String?
        var status: TripStatus?
    }

    private var singleChart: Bool { verticalSize == .compact || typeSize >= .accessibility1 }

    private func read() async {
        let result = await model.library.detail(id)
        detail = result
        if case .ready(nil) = result { dismiss() }
    }

    /// Subtract it from a replay height to get a height above the chosen reference.
    private func heightOffset(_ trip: TripRecord?) -> Double {
        seaLevel(trip) ? trip?.seaLevelOffsetMeters ?? 0 : 0
    }

    private func seaLevel(_ trip: TripRecord?) -> Bool {
        model.settings.values.altitudeReference == .seaLevel && trip?.seaLevelOffsetMeters != nil
    }

    /// The position on the route line, with the direction of travel turned evenly between fixes. In a GPS gap there is
    /// no position, so the map shows no marker.
    private func replayPosition(_ trip: TripReplay?) -> MapPosition? {
        guard let trip else { return nil }
        let values = trip.valuesAt(replay.playhead)
        guard var sample = values.fix, let latitude = values.latitudeRadians, let longitude = values.longitudeRadians else { return nil }
        sample.latitudeRadians = latitude
        sample.longitudeRadians = longitude
        sample.bearingRadians = values.bearingRadians
        return MapPosition(sample: sample, fresh: true)
    }

    private func moments(_ trip: TripReplay?) -> [MapMoment] {
        guard let trip else { return [] }
        let offset = heightOffset(detail.value??.trip)
        return trip.moments.compactMap { moment in
            guard let latitude = moment.latitudeRadians, let longitude = moment.longitudeRadians else { return nil }
            return MapMoment(number: moment.number, latitudeRadians: latitude, longitudeRadians: longitude,
                             description: String(localized: "Moment \(moment.number): \(moment.kind.title), \(momentValue(moment, heightOffset: offset, format: format))"))
        }
    }

    /// A drag up shows the charts, a drag down shows the glance view. The button does the same, so a drag is not the only way.
    private var handle: some View {
        HStack {
            Capsule().fill(.secondary.opacity(0.6)).frame(width: 36, height: 5)
                .frame(maxWidth: .infinity, minHeight: 24)
                .contentShape(.rect)
                .gesture(DragGesture(minimumDistance: 12).onEnded { value in
                    withAnimation(.snappy) {
                        if value.translation.height < -24 { replay.details = true } else if value.translation.height > 24 { replay.details = false }
                    }
                })
                .accessibilityElement()
                .accessibilityLabel(replay.details ? Text("Show the map") : Text("Show the charts"))
                .accessibilityAddTraits(.isButton)
                .accessibilityAction { withAnimation(.snappy) { replay.details.toggle() } }
            if replay.replay != nil {
                Button {
                    withAnimation(.snappy) { replay.details.toggle() }
                } label: {
                    // At the largest text sizes, the word does not fit beside the handle. The symbol keeps the same label.
                    if typeSize.isAccessibilitySize {
                        Image(systemName: replay.details ? "map" : "chart.xyaxis.line")
                    } else {
                        Label(replay.details ? "Map" : "Charts", systemImage: replay.details ? "map" : "chart.xyaxis.line")
                    }
                }
                .buttonStyle(.glass)
                .accessibilityLabel(replay.details ? Text("Map") : Text("Charts"))
                .accessibilityIdentifier("replay_charts")
            }
        }
    }

    @ViewBuilder private func savedPanel(_ trip: TripRecord?, _ shown: TripDetail?) -> some View {
        if let error = model.recorder.state.error { ProblemBanner(message: error.message) }
        if case .failed = detail {
            ProblemBanner(message: String(localized: "The app cannot read the saved trips. The trips stay on the phone.")) {
                Button("Try again") { Task { await read() } }.buttonStyle(.glass).accessibilityIdentifier("read_retry")
            }
        }
        if let trip {
            HStack {
                ActivityChip(text: trip.activityText) { model.trips.activityChoice = .trip(trip.id) }
                Spacer()
                if trip.status != .finished { Text(trip.status.title).font(.subheadline.weight(.medium)).foregroundStyle(Palette.warning) }
            }
        }
        let distance = shown?.summary.distanceMeters ?? trip?.distanceMeters
        let duration = trip?.durationNanos
        Grid(alignment: .leading, horizontalSpacing: Space.s6, verticalSpacing: Space.s3) {
            GridRow {
                MetricView(title: "Distance", value: distance.map(format.distance), identifier: "trip_distance")
                MetricView(title: "Recording time", value: duration.map(format.duration))
            }
            GridRow {
                MetricView(title: "Average speed, including stops",
                           value: duration.flatMap { averageSpeed(distanceMeters: distance, durationNanos: $0) }.map(format.speed),
                           identifier: "average_speed")
                MetricView(title: "Maximum speed", value: shown?.summary.maximumSpeedMetersPerSecond.map(format.speed))
            }
        }
        if trip?.status == .interrupted, let trip {
            Text("Recording stopped unexpectedly. The trip is saved up to that point. You can resume it or end it.").font(.footnote)
            HStack(spacing: Space.s3) {
                Button { model.trips.resume(trip.id, fromSavedTrip: true) } label: {
                    Label("Resume trip", systemImage: "play.fill").frame(maxWidth: .infinity)
                }
                .buttonStyle(.glassProminent)
                .disabled(model.recorder.state.trip != nil)
                Button { model.recorder.send(.finish(id: trip.id)) } label: { Text("End trip").frame(maxWidth: .infinity) }
                    .buttonStyle(.glass)
            }
            .controlSize(.large)
        }
    }

    private var savedBanner: some View {
        HStack(spacing: Space.s3) {
            Image(systemName: "checkmark.circle.fill").foregroundStyle(Palette.success)
            Text("Trip saved").font(.subheadline.weight(.semibold))
            Spacer()
            Button("Resume trip") {
                showSaved = false
                model.trips.reopen(id)
            }
            .buttonStyle(.glassProminent)
            .accessibilityIdentifier("trip_reopen")
            Button { showSaved = false; model.trips.justSaved = nil } label: { Image(systemName: "xmark") }
                .buttonStyle(.glass)
                .buttonBorderShape(.circle)
                .accessibilityLabel(Text("Close"))
        }
        .padding(.horizontal, Space.s4)
        .padding(.vertical, Space.s2)
        .glassEffect(.regular, in: .capsule)
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    @ToolbarContentBuilder private func toolbar(_ trip: TripRecord?, _ shown: TripDetail?) -> some ToolbarContent {
        if replaying {
            ToolbarItem(placement: .topBarLeading) {
                Button {
                    // Back in the charts returns to the glance view, and then to the trip.
                    withAnimation(.snappy) { if replay.details { replay.details = false } else { replaying = false } }
                } label: {
                    Image(systemName: "chevron.backward")
                }
                .accessibilityLabel(Text("Back"))
                .accessibilityIdentifier("replay_back")
            }
        } else if let trip {
            // A recording trip still changes, so only a saved trip with positions can replay.
            let replayable = (trip.status == .finished || trip.status == .interrupted) && !(shown?.summary.lines.isEmpty ?? true)
            ToolbarItemGroup(placement: .topBarTrailing) {
                if replayable {
                    Button { withAnimation(.snappy) { replaying = true } } label: { Image(systemName: "play.fill") }
                        .accessibilityLabel(Text("Replay trip"))
                        .accessibilityIdentifier("open_replay")
                }
                Button { details = true } label: { Image(systemName: "info.circle") }
                    .disabled(shown == nil)
                    .accessibilityLabel(Text("Trip details"))
                    .accessibilityIdentifier("trip_details")
                Menu {
                    if replayable {
                        Button { withAnimation(.snappy) { replaying = true } } label: { Label("Replay trip", systemImage: "play") }
                    }
                    Button { model.trips.activityChoice = .trip(trip.id) } label: { Label("Change activity", systemImage: "pencil") }
                    // Only a route with positions can be shared.
                    if !(shown?.summary.lines.isEmpty ?? true) {
                        ShareLink(item: GpxExport(tripId: trip.id, name: tripTime(trip.startedMillis), library: model.library),
                                  preview: SharePreview(Text("Trip as GPX"), image: Image(systemName: "point.topleft.down.to.point.bottomright.curvepath"))) {
                            Label("Share as GPX", systemImage: "square.and.arrow.up")
                        }
                    }
                    // Delete is destructive, so it is last.
                    if trip.status == .finished || trip.status == .interrupted {
                        Divider()
                        Button(role: .destructive) { model.trips.deleteCandidate = trip.id } label: { Label("Delete trip", systemImage: "trash") }
                    }
                } label: {
                    Image(systemName: "ellipsis")
                }
                .accessibilityLabel(Text("More options"))
                .accessibilityIdentifier("trip_menu")
            }
        }
    }
}

/// The trip's details, technical values, and privacy note.
private struct SavedDetailsSheet: View {
    var detail: TripDetail
    @Environment(\.displayFormat) private var format

    var body: some View {
        NavigationStack {
            List {
                Section {
                    LabeledContent("Activity", value: detail.trip.activityText)
                    LabeledContent("Status", value: detail.trip.status.title)
                    TripTotalsRows(distance: detail.summary.distanceMeters, duration: detail.trip.durationNanos,
                                   maximum: detail.summary.maximumSpeedMetersPerSecond)
                }
                Section {
                    LabeledContent("Saved positions", value: format.whole(detail.positions))
                    LabeledContent("Recording segments", value: format.whole(detail.segments))
                    LabeledContent("Signal gaps", value: format.whole(detail.summary.gapCount))
                } header: {
                    Text("Technical details")
                } footer: {
                    VStack(alignment: .leading, spacing: Space.s2) {
                        Text("Distance is an estimate. It does not include gaps in the signal.")
                        Text("Green shows the start. Red shows the end. Select a marker to see its time. A break in the line is a gap in the recording.")
                        if detail.summary.lines.contains(where: { $0.first?.source == .network }) {
                            Text("A dashed line shows an approximate position from Wi-Fi and mobile networks, where the trip had no GPS signal.")
                        }
                    }
                }
                Section {
                    Text("Your trips stay on this device. Apple Maps receives the map area that you look at. Share as GPX sends positions and times only to the app that you select.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Trip details")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

extension UTType {
    /// GPX 1.1, as other apps declare it.
    static let gpx = UTType(importedAs: "com.topografix.gpx", conformingTo: .xml)
}

/// A trip as a GPX file. The file is written only when the user shares it.
struct GpxExport: Transferable {
    var tripId: String
    var name: String
    var library: TripLibrary

    static var transferRepresentation: some TransferRepresentation {
        FileRepresentation(exportedContentType: .gpx) { export in
            guard let file = await export.library.exportGpx(export.tripId, name: export.name) else { throw CocoaError(.fileWriteUnknown) }
            return SentTransferredFile(file)
        }
    }
}
