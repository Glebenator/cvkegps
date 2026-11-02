# Build the iOS app with Apple Maps, SwiftUI, and a Swift core that shares the Android rules

The owner asked for a native iOS app with as many native features and as much of the native design language as possible,
with Apple Maps in place of Google Maps and the Liquid Glass look of iOS 26. The app is in `ios/`.

We use SwiftUI, MapKit for SwiftUI, Core Location, Core Motion, ActivityKit, App Intents, and Swift Charts, with iOS 26
as the minimum version. The two apps share the measurement rules and the product behaviour, not code (AGENTS.md).
The rules are in a Swift package, `ios/CVKEGPSCore`, that uses Foundation only. Its tests repeat the Android
calculation tests, so the two apps must give the same results. It also builds and tests on Linux.

## Decisions

- **Map.** Apple Maps through MapKit for SwiftUI. It needs no API key, no Google Play services, and no billing account.
  The app keeps its own position marker, as ADR 0002 decides for Android, so an old position stays grey and location has
  one request. MapKit has no terrain map, so the map types are Standard, Muted, and Satellite.
- **Speed colours.** One polyline for each route line with a gradient. MapKit places gradient colours by distance along
  the line, so the stops use the distance of each point. The colours, scale, and smoothing follow ADR 0005.
- **Storage.** SQLite through the system library, with the tables of the Android Room database and one extra column,
  `trips.seaLevelOffsetMeters`. We did not use SwiftData: the same schema keeps the two apps comparable, the store can be
  tested on Linux, and recording writes many small rows. The database is not in iCloud or device backups.
- **Recording.** A recording trip uses a `CLBackgroundActivitySession` and background location updates. The session
  stays while the trip is paused, so the Live Activity can resume it. A paused trip has no location updates. The app
  never starts a trip without the app on screen: the Start trip intent opens the app.
- **Notification.** A Live Activity on the Lock Screen and in the Dynamic Island replaces the Android notification. It
  follows the same rules: state changes show at once, values at most every 30 seconds, and no current speed.
- **Design.** The Liquid Glass tab bar with icons only and an accessory above it, glass map controls and panels, and the
  brand gradient behind the Speed tab. Before a trip, the accessory has Start trip and the activity of the next trip, on
  both tabs. During a trip, it is the recording bar. The brand blue accent stays. iOS has no wallpaper colours, so the Colours setting is Android only.

## Platform differences

- **Satellites.** Core Location reports no satellites, signal levels, or receiver events. The satellite ring, the sky
  map, the compass alignment, and the satellite count of each fix are Android only.
- **Fix source.** Core Location does not say which receiver measured a fix. The iOS app treats a fix as a network
  position when it has no valid speed or course accuracy and its accuracy is worse than 20 m. Network positions go only
  to a recording trip after 10 seconds without a GPS fix, at most one each 5 seconds, when the user allows them.
- **High accuracy.** iOS gives about one fix each second. High accuracy asks for the navigation accuracy of Core Location.
- **Sea level.** Core Location reports the sea-level altitude and the GPS height with each fix, on all supported versions.
  A trip stores the difference near its start, so the replay can show heights above sea level.
- **Sensors.** Core Motion values are converted to the stored conventions of `SensorKind`, so the replay calculations
  do not change.
- **Map styles.** Android has the map styles Standard, Minimal, and High contrast, made with Google map style files. MapKit
  has no style files. The iOS map types are Standard, Muted (the nearest to Minimal), and Satellite. There is no High
  contrast map on iOS.
- **Speed tab values.** Android shows altitude and maximum speed, or the trip values during a trip, on cards. iOS shows
  the same values as plain text without cards, as the owner chose for iOS.
- **Start trip.** On iOS, Start trip and the activity are in the tab bar accessory, so a trip can start from the Speed
  tab and from the Trips tab. On Android, they are on the Speed tab. The start rules are the same: the recording notice,
  the access checks, and no start without the app on screen.

## Consequences

- Each Android change to a measurement rule needs the same change and test in `ios/CVKEGPSCore`.
- The fix source rule, the background recording with a locked screen, the Live Activity buttons, the sensor conversion,
  and battery use need a real iPhone before release.
- XcodeGen makes the Xcode project from `ios/project.yml`. The generated project is not in Git.
- A macOS GitHub Actions workflow builds the app and runs a simulator flow with simulated locations. Its results are
  functional evidence only.
- Before publication: replace the development bundle ID, add signing, and complete the App Store privacy details. Apple
  Maps receives the map area that the user looks at.
