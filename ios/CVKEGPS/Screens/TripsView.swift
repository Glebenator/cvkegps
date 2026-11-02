import CVKEGPSCore
import SwiftUI

/// The Trips tab: saved trips, newest first, as open rows without cards or dividers, as on Android. Swipe to delete.
/// A recording trip opens the current trip.
struct TripsView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        let read = model.library.trips
        let activeId = model.recorder.state.trip?.id
        List {
            Group {
                if let error = model.recorder.state.error {
                    ProblemBanner(message: error.message)
                }
                if model.library.deleteFailed {
                    ProblemBanner(message: String(localized: "The app could not delete the trip. Try again."))
                }
                if case .failed = read {
                    ProblemBanner(message: String(localized: "The app cannot read the saved trips. The trips stay on the phone.")) {
                        Button("Try again") { model.library.reload() }.buttonStyle(.glass).accessibilityIdentifier("read_retry")
                    }
                }
            }
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
            // As on Android: trips group under the day on which they started, newest first, so each row needs only its times.
            ForEach(TripDay.group(read.value ?? []), id: \.day) { group in
                Section {
                    ForEach(group.trips) { trip in
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
                        .listRowBackground(Color.clear)
                        .listRowSeparator(.hidden)
                        .accessibilityIdentifier("saved_trip")
                    }
                } header: {
                    Text(TripDay.title(group.day))
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.secondary)
                        .accessibilityAddTraits(.isHeader)
                        .accessibilityIdentifier("trip_day")
                }
                .listSectionSeparator(.hidden)
            }
        }
        .listStyle(.plain)
        .listRowSpacing(Space.s1)
        .scrollContentBackground(.hidden)
        .background(Palette.background)
        .overlay {
            // Before the store answers, the list stays empty instead of claiming that there are no trips.
            if case let .ready(trips) = read, trips.isEmpty { EmptyTrips() }
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

/// The trips of one day, newest first, and the day title: Today, Yesterday, a weekday in the last week, then a short date.
/// The year shows only for an earlier year.
struct TripDay {
    var day: Date
    var trips: [TripRecord]

    static func group(_ trips: [TripRecord], calendar: Calendar = .current) -> [TripDay] {
        var days: [TripDay] = []
        for trip in trips {
            let day = calendar.startOfDay(for: Date(timeIntervalSince1970: Double(trip.startedMillis) / 1000))
            if days.last?.day == day { days[days.count - 1].trips.append(trip) } else { days.append(TripDay(day: day, trips: [trip])) }
        }
        return days
    }

    static func title(_ day: Date, now: Date = .now, calendar: Calendar = .current) -> String {
        if calendar.isDateInToday(day) { return String(localized: "Today") }
        if calendar.isDateInYesterday(day) { return String(localized: "Yesterday") }
        let today = calendar.startOfDay(for: now)
        if let weekAgo = calendar.date(byAdding: .day, value: -7, to: today), day > weekAgo {
            return day.formatted(.dateTime.weekday(.wide))
        }
        if calendar.isDate(day, equalTo: now, toGranularity: .year) {
            return day.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated))
        }
        return day.formatted(.dateTime.weekday(.abbreviated).day().month(.abbreviated).year())
    }
}

/// A trip as on Android: an activity tile, the start and end times, the duration and the activity, a status for a trip that
/// is not finished, and the distance at the right in tabular digits. At accessibility text sizes the parts stack, so the
/// text has the full width.
private struct TripRow: View {
    var trip: TripRecord
    var live: Bool
    @Environment(\.displayFormat) private var format
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let stacked = typeSize.isAccessibilitySize
        let layout = stacked ? AnyLayout(VStackLayout(alignment: .leading, spacing: Space.s2))
            : AnyLayout(HStackLayout(alignment: .center, spacing: Space.s3))
        layout {
            IconTile(symbol: live ? "record.circle" : trip.mode.symbol)
            VStack(alignment: .leading, spacing: 2) {
                Text(times).font(.headline)
                // An unknown activity adds no words. The tile then shows the route icon.
                HStack(spacing: Space.s2) {
                    if trip.status != .finished {
                        StatusText(text: trip.status.title, color: trip.status == .recording ? Palette.danger : Palette.warning)
                    }
                    Text(trip.mode == .unknown ? format.duration(trip.durationNanos) : "\(format.duration(trip.durationNanos)) · \(trip.activityText)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .accessibilityIdentifier("trip_card_summary")
                }
            }
            .frame(maxWidth: stacked ? nil : .infinity, alignment: .leading)
            // The distance is stored with the trip, so the list does not calculate routes.
            Text(trip.distanceMeters.map(format.distance) ?? "—")
                .font(.headline.monospacedDigit())
                .accessibilityIdentifier("trip_row_distance")
        }
        .padding(.vertical, Space.s1)
        .accessibilityElement(children: .combine)
    }

    /// The start and end times in the clock format of the device. A trip that is not finished shows its start time.
    private var times: String {
        let start = Date(timeIntervalSince1970: Double(trip.startedMillis) / 1000)
        guard trip.status == .finished, let ended = trip.endedMillis, ended > trip.startedMillis else {
            return start.formatted(date: .omitted, time: .shortened)
        }
        let end = Date(timeIntervalSince1970: Double(ended) / 1000)
        return (start..<end).formatted(.interval.hour().minute())
    }
}

/// The empty list, as on Android: the brand slice art, a heading, and one sentence.
private struct EmptyTrips: View {
    var body: some View {
        VStack(spacing: Space.s3) {
            SliceArt(animated: true).padding(.bottom, Space.s2)
            Text("No trips").font(.title3.weight(.semibold))
            Text("To record a trip, select Start trip below.")
                .font(.body)
                .foregroundStyle(.secondary)
        }
        .multilineTextAlignment(.center)
        .padding(Space.s6)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("trips_empty")
    }
}
