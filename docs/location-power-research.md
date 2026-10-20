# Location power and performance research

Research date: 20 October 2026. App code baseline: `c109f66`.

Status: research complete. Proposed recording design. No app code changed.
No physical-device power or location measurements were made for this research.

## Recommendation

Keep the current GPS source as the baseline. Before background route recording,
add a recording session with its own lifecycle and reliable local storage.
Then compare the current source with Google's fused location provider.

Use one location request for the recorder and live display. Stop that request
when no feature needs it. Test batched delivery when the display is not visible.
Do not claim a battery saving from a provider change alone.
Android identifies accuracy, update frequency, delivery delay, and request
lifetime as controls for location power use.
[Android battery guidance](https://developer.android.com/develop/sensors-and-location/location/battery)

The user can call this feature background tracking. For a trip started in the
visible app, the proposed Android implementation is a **location foreground
service**. Android permits foreground location access through that service
after Home or screen lock. This flow does not need
`ACCESS_BACKGROUND_LOCATION` only because the screen is off.
[Location access definitions](https://developer.android.com/develop/sensors-and-location/location/permissions)

## Provider choice

| Source | Benefit | Limit | Proposed use |
| --- | --- | --- | --- |
| Current `LocationManager.GPS_PROVIDER` | No Google Play services dependency. Explicit GNSS location source. | Acquisition and quality depend on reception. It does not select a lower-power source for the app. | Keep as the baseline and possible fallback. |
| Google `FusedLocationProviderClient` | Combines location inputs and provides accuracy, cache, interval, and batching controls. | Requires a client library and Google Play services. High accuracy can still consume substantial power. | First alternative to measure if the release targets devices with Google Play services. |
| Platform `LocationManager.FUSED_PROVIDER` | Uses a platform provider without the Google client library. | The public constant starts at API 31. Availability and implementation can differ. This app supports API 29. | Consider if support without Google Play services is required. |

Google FLP and platform fused location are separate API choices. Do not assume
that their implementations or power use are identical. Check provider or service
availability before use. Sources:
[Android providers](https://developer.android.com/reference/android/location/LocationManager#FUSED_PROVIDER),
[Google FLP](https://developers.google.com/location-context/fused-location-provider),
[Google Play services requirements](https://developers.google.com/android/guides/setup).

## Controls and their limits

- **Request lifetime:** Remove updates when the feature stops. Avoid repeated
  requests for the same data. The current screen cleanup is suitable for live
  speed. A recorder must remain active independently of screen visibility.
  [Request cleanup](https://developer.android.com/develop/sensors-and-location/location/battery/optimize)
- **Accuracy:** Keep high accuracy for the first route tests. Balanced and low
  power modes trade accuracy for power. Passive requests depend on other clients
  and cannot be the only source for a complete route.
  [Priority definitions](https://developers.google.com/android/reference/com/google/android/gms/location/Priority)
- **Fix interval and delivery delay:** These are separate controls. The desired
  interval is not a deadline. Batching can reduce application wake-ups while
  preserving intermediate fixes. It does not establish that GNSS switches off
  between deliveries.
  [Batching guidance](https://developer.android.com/develop/sensors-and-location/location/battery)
- **Batch support:** FLP permits batching when maximum delay is at least twice
  the interval. Hardware can ignore this option. Different batches can arrive
  out of order. Inspect measurement timestamps.
- **Minimum distance:** This filters delivery. It does not guarantee lower
  sensor power. Start with 0 m so tests retain stationary measurements and
  isolate the effect of time and batching.
  [FLP request contract](https://developers.google.com/android/reference/com/google/android/gms/location/LocationRequest.Builder)
- **Short GPS cycles:** Do not implement a timer that repeatedly enables and
  disables GPS. The inspected AOSP code can keep GNSS active at short intervals
  when hardware scheduling is unavailable. This is implementation evidence,
  not a guarantee for every device.
  [AOSP GNSS implementation](https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/location/gnss/GnssLocationProvider.java)

## Initial policies to measure

These values are proposed experiments. They are not measured optimum settings.
Start with one recording profile. Add activity profiles only if results show a
useful benefit.

| State | Requested fix interval | Maximum delivery delay | Initial policy |
| --- | --- | --- | --- |
| No active feature, or location paused | No request | None | Remove updates. |
| Future map preview only | One bounded request | One result | Try balanced accuracy and a defined result-age limit. |
| Visible speedometer | 1 s | No batching | High accuracy, minimum interval 1 s, distance filter 0 m. |
| Screen-off recording baseline | 1 s | 10 s | High accuracy. Preserve all delivered points. Also test immediate delivery. |
| Walking experiment | 3 s | 15 s | Compare corners, stops, gaps, and power with the baseline. |
| Cycling experiment | 2 s | 10 s | Use the same comparison. |
| Driving experiment | 1 s | 5 s | Retain closer point spacing at higher speed. |

Use a minimum interval equal to the requested interval for these comparisons.
Use 0 m minimum distance for all active profiles. Restore immediate delivery
when the live speedometer becomes visible. Update the recording notification
at a modest rate. Record actual delivery delay; the requested delay is not
proof of continuity under all system conditions.

Sampling, storage, and rendering need separate rates. Removing points from a
saved route does not itself reduce sensor power. This policy table is a
project proposal based on the request controls, not a platform requirement.

## Recording architecture

The present app has three recording limits:

1. [AndroidGpsSource.kt](../app/src/main/java/com/example/cvkegps/location/AndroidGpsSource.kt)
   uses `conflate()`. A slow consumer can lose intermediate readings.
2. [SpeedometerViewModel.kt](../app/src/main/java/com/example/cvkegps/ui/SpeedometerViewModel.kt)
   rejects samples at least ten seconds old. A valid batched route point can be
   older than this when delivered.
3. [GpsSource.kt](../app/src/main/java/com/example/cvkegps/location/GpsSource.kt)
   has no coordinates or route identity.

Proposed data flow:

```text
One location request
    -> recording session -> local route storage
    -> latest display state -> ViewModel -> screen
```

The recording session must own the request while a trip is active. The screen
observes that session. When no trip is active, screen visibility can still
control the request.

Use a separate route-point model. Store coordinates, measurement time, elapsed
time, speed, available uncertainty values, and session and segment identities.
Process every point in each delivered batch. Use a bounded queue with an
explicit overflow response. Never silently discard route points.

Write small transactions off the main thread. Save session state before the
first point. On an orderly stop, use a bounded, best-effort flush where the
provider supports it. Commit received points and release updates. A flush
cannot guarantee recovery after process termination.

Keep display freshness separate from route validity. Order and deduplicate
route points by measurement time. Handle late batches without discarding valid
history. Represent signal loss and interruptions as gaps. Do not present a
line across a gap as a measured route.

Elapsed realtime is monotonic within one boot. Do not compare it across a
reboot without a new time boundary. Reported speed can be more accurate than
speed derived from position differences. Android uncertainty fields describe
estimates, not hard error limits.
[Android Location contract](https://developer.android.com/reference/android/location/Location)

The current limits of 50 m horizontal error and 3 m/s speed uncertainty need
field validation. The latter equals 10.8 km/h, so it is not a useful universal
walking-quality threshold. Retain quality information for later decisions.
Never convert missing or poor readings to zero speed.

Defer automatic pause. Slow motion, signal loss, and position noise can cause
false pauses or missed starts. Test manual pause and resume first.

## Service and permission design

For target SDK 37, declare `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_LOCATION`, and service type `location`.
Check the location grant and system location state before starting.
Start recording from a user action while the activity is visible.
[Location service requirements](https://developer.android.com/develop/background-work/services/fgs/service-types#location)

Service-start restrictions and while-in-use permission rules are separate.
Adding background location permission does not remove all restrictions.
Do not plan silent restarts from arbitrary background work.
[Foreground service start restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)

Ordinary background location access can be limited to a few deliveries per
hour. Increasing the requested frequency does not remove this limit. It is not
a suitable basis for the proposed live route recorder.
[Background location limits](https://developer.android.com/about/versions/oreo/background-location-limits)

Provide Pause and Stop notification actions. Request notification permission
when recording needs it. Android can permit a foreground service after
notification denial, while showing its notice only in Task Manager. Define
and test the resulting user flow.
[Notification permission](https://developer.android.com/develop/ui/compose/notifications/notification-permission)

A foreground service can still be terminated. Task Manager Stop can end the
app without a callback. Save progress during the trip. On reopening, show the
last saved state and an interrupted segment. Do not rely on `onDestroy()` to
save the route. Respect force-stop and user termination.
[User stopping behavior](https://developer.android.com/develop/background-work/services/fgs/handle-user-stopping)

Test Doze, Battery Saver, and manufacturer restrictions separately. Doze
restricts CPU, jobs, alarms, and network access. WorkManager is not a continuous
GPS sampling loop. Do not add a general wake lock or request a battery
optimization exemption by default.
[Doze guidance](https://developer.android.com/training/monitoring-device-state/doze-standby)

Battery Saver can alter location delivery with the screen off. Inspect
`getLocationPowerSaveMode()` and show interruption information when needed.
[Location power-save modes](https://developer.android.com/reference/android/os/PowerManager#getLocationPowerSaveMode())

### API 37 release decision

The location-button guide uses broad wording about session access. The linked
Google Play policy preview takes effect on **27 January 2027**. Its text
requires the button for one-time precise-location actions and allows broader
access where limited access cannot support the declared feature.

Confirm the policy for continuous speed display and trip recording before
publication. Do not treat the preview as a blanket current requirement to
replace this app's permission flow.
[Location-button guide](https://developer.android.com/guide/topics/permissions/private-alternatives/location-button),
[Policy preview and effective date](https://support.google.com/googleplay/android-developer/answer/16909972).

## Measurement and verification plan

1. **Permission recovery completed on 20 October 2026.** The P2 failure after
   two denials is fixed. Seven GPS and permission tests passed. A separate direct
   check passed through the Settings permission controls.
   See the [verification record](../PROGRESS.html#verification).
2. **Establish the baseline.** Use a release build on a Pixel and at least one
   other manufacturer's phone. Include API 29 and API 37 in functional coverage.
3. **Control the comparison.** Use the same route, mounting position, screen
   state, and radio settings. Repeat each case at least three times. Record
   device, OS, provider version, temperature, duration, and battery state.
   Avoid other location apps that can alter shared demand.
4. **Change one factor at a time.** Compare idle/no request, current 1 Hz GPS,
   fused location, batching, then longer intervals. Include open sky, buildings,
   trees, stops, weak signal, and a long stationary run with the screen off.
5. **Measure power and quality together.** Record energy or charge per hour,
   CPU wake-ups, GNSS activity, first-fix time, delivery delay, missing time,
   route and distance error, stop drift, and speed error. Use a suitable
   reference. A smooth map line does not prove accuracy.
6. **Test full user flows.** Cover Home, lock, rotation, notification controls,
   approximate and removed permission, GPS off/on, Doze, Battery Saver, process
   loss, Task Manager Stop, reboot, and reopening a partial route. Confirm that
   Pause and Stop remove requests and that gaps remain visible.
7. **Set acceptance limits.** Agree the allowed route error, update delay,
   battery use, and maximum unsaved time before selecting a production profile.

Use Android Studio Power Profiler and system traces first. Supported Pixel
devices expose ODPM power measurements. These represent device power, so
baseline comparisons remain necessary. Where suitable counters are available,
measure charge change over a long run. Battery percentage is too coarse for
short comparisons.
[Power Profiler](https://developer.android.com/studio/profile/power-profiler)

Use `dumpsys batterystats` as supporting evidence. Battery Historian is no
longer actively maintained; it should not be the main measurement tool.
[Current power profiling guidance](https://developer.android.com/topic/performance/power/setup-battery-historian)

## Open product decisions

- Must the app work without Google Play services?
- What route error and speed uncertainty are acceptable for the first activity?
- What battery use and maximum unsaved time are acceptable?
- What should the user see after an interrupted recording?
- Which release permission path applies to the final feature set?

Keep coordinates local and out of diagnostic logs. Use explicit user action
for export. Share measurement units, quality rules, and privacy rules with the
iOS app. Retain native platform APIs and lifecycle handling.
