import CVKEGPSCore
import SwiftUI

/// The Trips tab: saved trips, newest first. Swipe to delete. A recording trip opens the current trip.
struct TripsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format

    var body: some View {
        let read = model.library.trips
        let activeId = model.recorder.state.trip?.id
        List {
            if let error = model.recorder.state.error {
                ProblemBanner(message: error.message).listRowBackground(Color.clear)
            }
            if model.library.deleteFailed {
                ProblemBanner(message: String(localized: "Recording stopped because the trip could not be saved. Make free space on the phone. The trip is saved up to that point."))
                    .listRowBackground(Color.clear)
            }
            if case .failed = read {
                ProblemBanner(message: String(localized: "The app cannot read the saved trips. The trips stay on the phone.")) {
                    Button("Try again") { model.library.reload() }.buttonStyle(.glass).accessibilityIdentifier("read_retry")
                }
                .listRowBackground(Color.clear)
            }
            ForEach(read.value ?? []) { trip in
                let live = trip.id == activeId
                NavigationLink(value: live ? AppRoute.liveTrip : AppRoute.savedTrip(trip.id)) {
                    TripRow(trip: trip, live: live)
                }
                .swipeActions(edge: .trailing) {
                    if trip.status == .finished || trip.status == .interrupted {
                        Button(role: .destructive) { model.trips.deleteCandidate = trip.id } label: {
                            Label("Delete trip", systemImage: "trash")
                        }
                    }
                }
                .accessibilityIdentifier("saved_trip")
            }
        }
        .overlay {
            // Before the store answers, the list stays empty instead of claiming that there are no trips.
            if case let .ready(trips) = read, trips.isEmpty {
                ContentUnavailableView {
                    Label("No trips", systemImage: "point.topleft.down.to.point.bottomright.curvepath")
                } description: {
                    Text("To record a trip, select Start trip on the Speed tab.")
                }
                .accessibilityIdentifier("trips_empty")
            }
        }
        .navigationTitle("Trips")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { model.showSettings = true } label: { Image(systemName: "gearshape") }
                    .accessibilityLabel(Text("Settings"))
                    .accessibilityIdentifier("open_settings")
            }
        }
        .refreshable { model.library.reload() }
        .onDisappear { model.library.deleteFailed = false }
    }
}

private struct TripRow: View {
    var trip: TripRecord
    var live: Bool
    @Environment(\.displayFormat) private var format

    var body: some View {
        HStack(spacing: Space.s3) {
            Image(systemName: live ? "record.circle.fill" : trip.mode.symbol)
                .font(.title3)
                .foregroundStyle(live ? Palette.danger : Palette.accent)
                .frame(width: 36, height: 36)
                .background(Palette.accentSubtle, in: .rect(cornerRadius: 10))
            VStack(alignment: .leading, spacing: 2) {
                Text(tripTime(trip.startedMillis)).font(.headline)
                // The distance is stored with the trip, so the list does not calculate routes.
                Text("\(trip.distanceMeters.map(format.distance) ?? "—") · \(format.duration(trip.durationNanos)) · \(trip.activityText)")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .accessibilityIdentifier("trip_card_summary")
            }
            Spacer(minLength: 0)
            if trip.status != .finished {
                HStack(spacing: Space.s1) {
                    StatusDot(color: trip.status == .recording ? Palette.danger : Palette.warning)
                    Text(trip.status.title).font(.caption.weight(.medium))
                }
            }
        }
        .padding(.vertical, Space.s1)
        .accessibilityElement(children: .combine)
    }
}
