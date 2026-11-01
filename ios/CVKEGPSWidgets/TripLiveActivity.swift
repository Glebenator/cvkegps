import ActivityKit
import AppIntents
import SwiftUI
import WidgetKit

@main
struct CVKEWidgets: WidgetBundle {
    var body: some Widget {
        TripLiveActivity()
        StartTripControl()
    }
}

/// The recording trip on the Lock Screen, in the Dynamic Island, and in StandBy. It shows the state, the recording time,
/// the distance, and the average speed, with Pause or Resume and Stop. It never shows the current speed.
struct TripLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: TripActivityAttributes.self) { context in
            LockScreenTrip(state: context.state)
                .activityBackgroundTint(nil)
                .activitySystemActionForegroundColor(Color.accentColor)
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    StatusLabel(state: context.state).padding(.leading, 4)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    TripTime(state: context.state).font(.title3.weight(.semibold)).monospacedDigit()
                }
                DynamicIslandExpandedRegion(.bottom) {
                    HStack {
                        Values(state: context.state)
                        Spacer()
                        Controls(state: context.state)
                    }
                }
            } compactLeading: {
                Image(systemName: context.state.recording ? "record.circle.fill" : "pause.circle.fill")
                    .foregroundStyle(context.state.recording ? .red : .secondary)
            } compactTrailing: {
                TripTime(state: context.state).monospacedDigit().frame(maxWidth: 56)
            } minimal: {
                Image(systemName: context.state.noSignal ? "location.slash.fill" : (context.state.recording ? "record.circle.fill" : "pause.circle.fill"))
                    .foregroundStyle(context.state.recording ? .red : .secondary)
            }
            .keylineTint(.red)
        }
    }
}

private struct LockScreenTrip: View {
    var state: TripActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                StatusLabel(state: state)
                Spacer()
                TripTime(state: state).font(.title2.weight(.semibold)).monospacedDigit()
            }
            HStack(alignment: .bottom) {
                Values(state: state)
                Spacer()
                Controls(state: state)
            }
        }
        .padding()
    }
}

private struct StatusLabel: View {
    var state: TripActivityAttributes.ContentState

    var body: some View {
        Label {
            Text(state.recording ? (state.noSignal ? "No GPS signal" : "Recording trip") : "Trip paused")
                .font(.headline)
        } icon: {
            Image(systemName: state.recording ? (state.noSignal ? "location.slash.fill" : "record.circle.fill") : "pause.circle.fill")
                .foregroundStyle(state.recording ? .red : .secondary)
        }
    }
}

/// While recording, the system counts the time without updates.
private struct TripTime: View {
    var state: TripActivityAttributes.ContentState

    var body: some View {
        if state.recording, let start = state.timerStart {
            Text(timerInterval: start...Date.distantFuture, countsDown: false)
        } else {
            Text(state.durationText)
        }
    }
}

private struct Values: View {
    var state: TripActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 16) {
            VStack(alignment: .leading, spacing: 0) {
                Text("Distance").font(.caption2).foregroundStyle(.secondary)
                Text(state.distanceText ?? "—").font(.subheadline.weight(.medium))
            }
            VStack(alignment: .leading, spacing: 0) {
                Text("Average").font(.caption2).foregroundStyle(.secondary)
                Text(state.averageText ?? "—").font(.subheadline.weight(.medium))
            }
        }
        .monospacedDigit()
    }
}

private struct Controls: View {
    var state: TripActivityAttributes.ContentState

    var body: some View {
        HStack(spacing: 8) {
            if state.recording {
                Button(intent: PauseTripIntent()) { Image(systemName: "pause.fill") }
                    .accessibilityLabel(Text("Pause trip"))
            } else {
                Button(intent: ResumeTripIntent()) { Image(systemName: "play.fill") }
                    .accessibilityLabel(Text("Resume trip"))
            }
            Button(intent: StopTripIntent()) { Image(systemName: "stop.fill") }
                .tint(.red)
                .accessibilityLabel(Text("Stop and save"))
        }
        .buttonStyle(.borderedProminent)
        .buttonBorderShape(.circle)
    }
}

/// A Control Center and Lock Screen control that opens the app and starts a trip.
struct StartTripControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "com.example.cvkegps.start-trip") {
            ControlWidgetButton(action: StartTripIntent()) {
                Label("Start trip", systemImage: "record.circle")
            }
        }
        .displayName("Start trip")
        .description("Opens CVKE GPS and starts recording a trip.")
    }
}
