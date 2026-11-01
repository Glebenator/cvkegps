import CVKEGPSCore
import SwiftUI

/// Reading quality and the location request. It shares the Speed tab's request. iOS reports no satellites or receiver
/// events, so this screen has no satellite sky map.
struct GpsDetailsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.displayFormat) private var format

    var body: some View {
        let speed = model.speed
        let reading = speed.reading
        List {
            Section {
                AccuracyBar(accuracyMeters: reading?.accuracyMeters, showsScale: true)
                    .padding(.vertical, Space.s1)
            } footer: {
                Text("The bar is empty at 50 m or more and full at 1 m or less. The notch marks 5 m. These are display references, not receiver limits. A full bar does not guarantee accuracy.")
            }
            Section {
                LabeledContent("Source", value: String(localized: "Core Location"))
                LabeledContent("Requested accuracy", value: model.location.highAccuracy
                    ? String(localized: "Best for navigation") : String(localized: "Best"))
                LabeledContent("Location access", value: model.location.preciseAccess
                    ? String(localized: "Precise") : String(localized: "Approximate"))
                LabeledContent("Readings received in this request", value: format.whole(speed.receivedCount))
                TimelineView(.periodic(from: .now, by: 1)) { _ in
                    LabeledContent("Last reading age", value: age(speed.lastFixNanos) ?? String(localized: "Unavailable"))
                        .accessibilityIdentifier("details_fix_age")
                }
            } header: {
                Text("Location request")
            } footer: {
                Text("The count resets when the location request restarts. Location stops when the app is not on screen unless a trip records.")
            }
            Section {
                LabeledContent("Speed check", value: speedCheck)
                LabeledContent("Horizontal accuracy estimate", value: metres(reading?.accuracyMeters))
                LabeledContent("Speed uncertainty", value: reading?.speedAccuracyMetersPerSecond.map { String(localized: "±\(format.decimal($0)) m/s") } ?? unavailable)
                LabeledContent("Altitude uncertainty", value: metres(reading?.verticalAccuracyMeters))
                LabeledContent("Direction of travel", value: degrees(reading?.bearingRadians))
                LabeledContent("Direction uncertainty", value: degrees(reading?.bearingAccuracyRadians))
                LabeledContent("Simulated location", value: reading.map { $0.isMock ? String(localized: "Yes") : String(localized: "No") } ?? unavailable)
            } header: {
                Text("Current reading quality")
            } footer: {
                Text("Accuracy values are iOS estimates, not guaranteed error limits. Direction is measured clockwise from true north. It is not the phone compass. Values clear after 10 seconds without a fresh reading.")
            }
            Section {
                Text("iOS does not report satellites, signal levels, or receiver events to apps. The satellite ring and the sky map of the Android app are therefore not available.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } header: {
                Text("Satellites")
            }
        }
        .navigationTitle("GPS details")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.location.display(true) }
        .onDisappear { model.location.display(false) }
    }

    private var unavailable: String { String(localized: "Unavailable") }

    private var speedCheck: String {
        let speed = model.speed
        guard let reading = speed.reading else {
            return speed.status == .stale ? String(localized: "Reading is too old") : String(localized: "No current reading")
        }
        switch reading.speedIssue {
        case nil: return String(localized: "Accepted for display")
        case .positionAccuracy: return String(localized: "Position accuracy is missing or exceeds 50 m")
        case .speedAccuracy: return String(localized: "Speed uncertainty is invalid or exceeds 3 m/s")
        case .missing: return String(localized: "The reading has no speed")
        case .invalid: return String(localized: "The speed value is invalid")
        }
    }

    private func metres(_ value: Double?) -> String {
        value.map { String(localized: "±\(format.decimal($0)) m") } ?? unavailable
    }

    private func degrees(_ radians: Double?) -> String {
        radians.map { String(localized: "\(format.decimal($0 * 180 / .pi))°") } ?? unavailable
    }

    private func age(_ nanos: Int64?) -> String? {
        guard let nanos else { return nil }
        let seconds = Double(MonotonicClock.nanos() - nanos) / 1e9
        return seconds >= 0 ? String(localized: "\(format.decimal(seconds)) s") : nil
    }
}
