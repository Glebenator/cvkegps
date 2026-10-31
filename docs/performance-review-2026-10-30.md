# Performance review, 30 October 2026

This review finds the slow parts of the app, measures them, and gives the causes and the proposed fixes.
It changes no app code. The owner selects the fixes.

- **App baseline:** branch `native-maps` at `27b5508` (map styles merged).
- **Main complaint of the owner:** "Clicking the Replay button is particularly slow. Clicking that button should zoom in as soon as possible."
- **Device:** Pixel 7 Pro (`cheetah`), Android 17, 120 Hz screen, with the owner's real trips.
  The owner approved each step on the phone. The owner decided not to use the emulator for this review, so all timings come from the phone.
- **Builds:**
  - *Debug*: the debuggable build that the owner uses every day on the phone.
  - *Release-like*: the `benchmarkRelease` build type. The Baseline Profile plugin makes it. It uses R8, is not debuggable, and has the debug signing key.
    Before each release-like measurement, the app was compiled with `cmd package compile -f -m speed-profile`, as after a Play install.

## Summary

The table shows the findings in order of user impact. "Measured" means that a timing or a trace shows the problem.
"Suspected" means that the cause comes from the code only.

| Rank | Finding | Evidence | Measured on the phone (release-like / debug) | Proposed fix | Risk |
|---|---|---|---|---|---|
| 1 | Replay waits for all sensor data and its analysis. The map does not zoom until the replay has a position. | Measured | 0.93 s / 2.18 s from tap to replay ready (35 min trip). No zoom at 0:00 on that trip. | F1, F2, F3 | Low to medium |
| 2 | The phone runs the debug build. All slow paths take 2 to 4 times longer. | Measured | Cold start 161 ms / 715 ms. Replay ready 0.93 s / 2.18 s. Map loaded 0.61 s / 2.05 s. | F4 | None in code |
| 3 | During the first replay play, the camera starts an 800 ms animation on each frame and stops it on the next frame. | Measured | 736 animation starts in 6 s (1 for each frame at 120 Hz). | F5 | Low |
| 4 | The first map in an app process blocks the main thread for about 150 ms. The slide-in animation stops. | Measured | No frame from 18 ms to 229 ms after the tap. | F6 | Medium |
| 5 | The saved trip read starts only after the 340 ms slide-in animation. | Measured delay, inferred cause | Read starts 370 ms after the tap, on the first and the second open. | F7 | Low |
| 6 | One long frame when the replay panel appears. | Measured | 63 ms / 280 ms. | F8 | Low |
| 7 | Speed colours: the replay changes 4 route lines on the main thread each time the playhead passes a point. | Suspected | Not measured: the phone uses one route colour. | F9 | Medium |

Start time, the trip list, and disk access on the main thread are good. See [What is good](#what-is-good).

## How the measurements were made

- **Timing marks (temporary).** A local patch added log lines and trace sections at these points: the tap, the start and the end of
  each database read, the end of the analysis, the first replay position, the first replay camera move, "map loaded", and the route build.
  The marks log durations and counts only, never coordinates. The patch is not committed. It is in `artifacts/perf/instrumentation.patch` (ignored by Git).
- **Start time.** `adb shell am start -W -S` for cold starts, and `am start -W` after Home for warm starts.
- **Frames.** `adb shell dumpsys gfxinfo com.example.cvkegps framestats`, reset before each flow. At 120 Hz, a frame has 8.3 ms.
- **System traces.** `adb shell perfetto` with `sched`, `gfx`, `view`, `am`, `wm`, `input`, `dalvik`, `res`, `binder_driver`, `database`, and app
  atrace for the package. The config is `artifacts/perf/trace-config.pbtx`. Perfetto `trace_processor` v59.1 (SHA-256 checked) read the traces with
  `artifacts/perf/replay.sql` and `artifacts/perf/open.sql`. Open the `.perfetto-trace` files in `artifacts/perf/` with ui.perfetto.dev to see them.
- **StrictMode (temporary).** The debug build of the patch set a thread policy that logs disk reads, disk writes, network access,
  slow calls, and unbuffered I/O on the main thread.
- **Phone trip.** The owner's trip of 29 October, 8:31 a.m.: 35 min 50 s, 23.5 km, 2,139 fixes, 3,225 sensor chunks (13.7 MB).
  The phone uses the single route colour. The review did not change any app setting on the phone.
- **Phone procedure.** No trip was recording. A copy of the installed APK was saved. The timing debug build and then the timing release-like build
  were installed as updates (same signing key), so the saved trips stayed. `adb input` made the taps. After the measurements, the saved APK was installed
  again, and its SHA-256 on the phone matched the saved copy.

## Findings

### 1. Replay waits for all sensor data and its analysis

**Measured (phone, 35 min trip):**

| Step after the Replay tap | Release-like | Debug |
|---|---|---|
| Read 2,139 saved points | 49 ms | 57 ms |
| Read 3,225 sensor chunks (13.7 MB) | 231 ms | 262 ms |
| Analysis (`ReplayAnalysis.analyze`) | 635 ms | 1,790 ms |
| Replay ready | **933 ms** | **2,183 ms** |
| First camera move | Only after Play | Only after Play |

The release-like trace shows 3 garbage collections in this time: 405 ms in total, the longest 199 ms.
One background thread did the analysis (632 ms CPU). The disk thread ran for 230 ms.

**Code:**
[`TrackingViewModel.replay()`](../app/src/main/java/com/example/cvkegps/tracking/TrackingViewModel.kt#L153),
[`TripDao.sensorChunks()`](../app/src/main/java/com/example/cvkegps/tracking/TripDatabase.kt#L95),
[`ReplayAnalysis.analyze()`](../app/src/main/java/com/example/cvkegps/tracking/TripReplay.kt#L183),
[replay camera effect](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L292).

**Causes:**

1. The replay shows nothing new until the full read and the full analysis are complete. The GPS part of the replay does not need the sensor data.
2. The camera moves only to a playhead position. A position needs a fix within 10 s of the playhead.
   On the owner's trip, the first fix is more than 10 s after the start. The replay then shows "No GPS signal" at 0:00, and the camera does not move until Play.
   The saved route and its first fix are already in memory, but the replay does not use them.
3. The sensor analysis makes many small objects for each reading:
   - [`readings()`](../app/src/main/java/com/example/cvkegps/tracking/TripReplay.kt#L350) makes a `Pair`, a `FloatArray` copy, and a boxed list
     (`take(3)`) for each of about 214,000 motion readings, and sorts them. The database already returns them in time order.
   - [`bin()`](../app/src/main/java/com/example/cvkegps/tracking/TripReplay.kt#L435) puts each reading in a `TreeMap` with boxed keys and a new `Pair`.
   - [`alignmentAngles()`](../app/src/main/java/com/example/cvkegps/tracking/TripReplay.kt#L394) reads all GPS bins again for each 30 s window.
     Its time increases with the square of the trip duration.
4. The read gets 13.7 MB of sensor data for 36 minutes. A 3 h trip has about 70 MB.

**Proposed fixes:**

- **F1. Zoom at once.** When the user taps Replay, move the camera in one short animation to the first usable fix of the saved route,
  at the replay zoom. Do not wait for the analysis. When the playhead has no fix, keep the camera at the nearest fix.
  - Risk: low. The camera continues to follow the playhead after the analysis. A device test must check the camera after the tap.
- **F2. Read less before the first replay frame.** Read the sensor chunks at the same time as the points, not after them.
  Then, as an option, show the GPS part of the replay first and add the sensor charts when they are ready.
  - Risk: low for the parallel read (saves about 50 ms). Medium for the two-step replay: the acceleration moments and the height source can change when
    the sensor data arrives.
- **F3. Make the analysis cheaper.** Keep the same calculations, but use primitive arrays (`LongArray`, `FloatArray`), dense 1 s bins
  indexed by bin number, no sort when the readings are already in order, and one pass over the GPS bins for all windows.
  - Risk: medium. The results must stay the same. `TripReplayTest` must pass, and a test must compare the old and the new result on a long simulated trip.
  - Expected effect: much less garbage collection, and a time that increases linearly with the trip duration. Measure it before and after.

### 2. The phone runs the debug build

**Measured (phone):**

| Flow | Release-like | Debug |
|---|---|---|
| Cold start (`am start -W -S`) | 150 to 182 ms, median 161 ms (5 runs) | 710 and 720 ms (the owner's own build, 2 runs) |
| Warm start | 39 to 55 ms (5 runs) | not measured |
| Trip open: tap to "map loaded", first map in the process | 606 to 616 ms | 2,048 ms |
| Replay: tap to replay ready | 933 ms | 2,183 ms |
| Frame when the replay panel appears | 63 ms | 280 ms |

**Cause:** A debuggable app runs with reduced compiler optimisations, without R8, and without the Baseline Profile.
Measurements in a debug build are much worse than a release install.

**Proposed fix:**

- **F4. Use the release-like build for daily use.** Install it with `./gradlew :app:installBenchmarkRelease`.
  It has the same signing key as the debug build, so an update install keeps the saved trips.
  - Risk: none in code. The installed app is not debuggable, so `run-as` and the debugger do not work on it.
    Use the debug build for development and device tests, as `AGENTS.md` requires.

### 3. The replay camera starts an animation on each frame

**Measured (phone):** The "first replay camera move" mark is logged once for each frame during the first play:
736 times in 6 s (release-like) and 5,705 times in about 25 s (debug). The frames stay in time in the release-like build
(0.13% late frames at 1×), but the camera does not move as the code intends.

**Code:** [`RouteMap.kt` lines 292 to 311](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L292).

**Cause:** `replayStart` selects one 800 ms animation for the first move. The effect sets `replayStart = false` only after `camera.animate()` ends.
During play, the playhead position changes on each frame, so the effect restarts before the animation ends.
`replayStart` stays true, and each frame starts a new 800 ms animation and stops the previous one. The camera moves only a small part of each animation,
so it stays behind the position marker.

**Proposed fix:**

- **F5.** Set `replayStart = false` before the first animation starts, or run the first move in its own effect that the playhead does not restart.
  Later frames then use `camera.move()`, as the comment says.
  - Risk: low. Do F5 together with F1, because both change the same effect.

### 4. The first map in an app process blocks the main thread

**Measured (phone, release-like trace):** After the first trip tap in a new process, the main thread runs Google Maps SDK setup from 76 ms to 229 ms:
`NativeHelper.loadLibrary()` (6 ms), lock contention with a Maps thread (9 ms), `ClientParametersBlockingReference.blockedOnMainThread` (18 ms),
and `PhoenixGoogleMapActivityEnvironment.getMap` (33 ms). No frame is drawn from 18 ms to 229 ms, so the slide-in animation stops.
On the second open in the same process, map creation takes 6 ms, and "map loaded" comes 275 ms after the tap instead of 616 ms.

**Code:** [`GoogleMap(...)` in `MapCanvas`](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L313).

**Cause:** The Maps SDK loads its library and its settings from Google Play services when the first map is made. This work is on the main thread.

**Proposed fix:**

- **F6.** Call `MapsInitializer.initialize(context, MapsInitializer.Renderer.LATEST) {}` once, when the main thread is idle after the first frame of the app.
  Then measure the first trip open again. Adopt the change only if the frozen time becomes shorter.
  - Risk: medium. It moves Maps work to the time after the app start, also for users who do not open a map. It uses some memory.
    Part of the cost (`getMap`) is for each map and does not move.

### 5. The saved trip read waits for the slide-in animation

**Measured (phone, release-like):** The trip read starts 372 ms after the tap on the first open and 371 ms after the tap on the second open.
The read and the summary then take about 60 ms. The route values show about 440 ms after the tap.

**Code:** [`SavedTripScreen.kt` lines 93 and 94](../app/src/main/java/com/example/cvkegps/tracking/SavedTripScreen.kt#L93),
[`AppShell.kt` line 302](../app/src/main/java/com/example/cvkegps/ui/AppShell.kt#L302),
[`Motion.ENTER_MILLIS`](../app/src/main/java/com/example/cvkegps/ui/theme/Tokens.kt#L134).

**Cause (inferred):** The trip list selects the trip before the navigation, "so the route loads while the trip screen opens".
But the screen collects `detail` with `minActiveState = Lifecycle.State.RESUMED`. A Navigation 3 entry is resumed only after its transition.
The transition is 340 ms. The delay is the same with and without the first-map cost, so it does not come from the map.

**Proposed fix:**

- **F7.** Collect `detail` (and the list record) in `SavedTripScreen` with the default `Lifecycle.State.STARTED`.
  - Risk: low. The collection still stops when the app is not visible. Measure the read start again after the change.

### 6. One long frame when the replay panel appears

**Measured (phone):** One frame of 63 ms (release-like) or 280 ms (debug). In the release-like trace, 51 ms of the frame is recomposition:
17 ms recompose, 18 ms apply changes, and map work inside the apply.

**Cause (suspected):** The replay panel, the speed profile, the moment chips, and the map changes compose in one frame.
The Baseline Profile covers only the start and the tab change
([`AppJourney.kt`](../baselineprofile/src/main/java/com/example/cvkegps/baselineprofile/AppJourney.kt#L15)), so the trip and replay code runs without
ahead-of-time compilation the first time. The trace shows 76 ms of JIT compiler CPU time during the replay start.

**Proposed fix:**

- **F8.** Add "open a saved trip" and "open the replay" to the Baseline Profile journey. The generator needs a saved trip on the device.
  - Risk: low for the app (a profile change only). Medium effort: the journey must make or find a trip.

### 7. Speed colours during replay

**Not measured.** The phone uses the single route colour, and the emulator was not used. This finding comes from the code only.

**Code:** [`RouteOverlay`, `doneSpans` and the 4 `Polyline` calls](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L437),
[`speedSpans()`](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L566),
[`played` in `TripMap`](../app/src/main/java/com/example/cvkegps/tracking/TripReplayScreen.kt#L262).

**Suspected cause:** During play, `TripMap` calculates the played points of each line on each frame. When the playhead passes a fix, the count changes.
With speed colours, each line then gets new spans from `speedSpans()` on the main thread, and 4 polylines (2 outlines, 2 coloured parts) get new
point lists. The Maps SDK receives each full list again. At 1× and 1 fix each second, this occurs once a second. At 64×, it occurs about 64 times
a second, which is about every second frame at 120 Hz. The work increases with the number of fixes: about 2,100 for the 36 min trip, about 10,800 for a 3 h trip.
The single route colour has the same 2 point list changes, but no span calculation and no outlines.

**How to measure it:** On the phone, select Speed in the map type menu of a saved trip, play the 36 min trip at 64×, and compare
`dumpsys gfxinfo` with the single colour. The temporary patch also logs each span calculation of 2 ms or more.
For comparison, the single colour at 64× in the debug build had 11% late frames (median frame 16 ms).

**Proposed fix:**

- **F9.** Do not change the speed-coloured route on each played fix. For example: draw the full route once and show the progress with the position
  marker only, or change the played part at most a few times a second, or calculate the spans off the main thread.
  - Risk: medium. The muted part after the playhead changes how the replay looks, so the owner must choose. Measure F9 before and after.

## What is good

- **Start time.** Cold start on the phone (release-like): 161 ms median. Warm start: 45 ms median. No change is necessary.
- **Main-thread disk access.** The StrictMode policy in the timing debug build logged no violation on the phone during the app start,
  the Trips tab, the trip open, and the Replay tap. The log of the play part was cleared before it was read.
  The Speed tab with a GPS fix, Settings, and a recording trip were not in the run.
- **Frames during replay play (release-like).** At 1×, 0.13% of the frames were late, also with the camera problem of finding 3.
- **Trip list.** The list uses stable keys and a content type. The distance comes from the trip record, so the list does not calculate routes.
- **Database and settings.** Room suspend calls run on Room's executors. The settings read and write on an IO dispatcher.
  Route calculations run on `Dispatchers.Default`.
- **Map styles.** The JSON style file is read off the main thread
  ([`rememberMapStyle`](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt#L582)).

## What still needs the real phone

- Speed colours during replay (finding 7). Select Speed for one measurement, then select the single colour again.
- A trip of more than 1 hour. The longest trip on the phone is 36 minutes. The analysis time of finding 1 can increase faster than the trip duration.
- Play at 64× in the release-like build. Only the debug build was measured at 64×.
- Each approved fix: measure the Replay tap and the trip open again on the trip of 29 October, 8:31 a.m., with the release-like build.
- Battery use and heat during a long replay. This review did not measure them.

## Recommended order

1. **F1 and F5 together.** They make the Replay tap zoom at once and make the replay camera follow correctly. Low risk, small change in one effect.
2. **F4.** Use the release-like build on the phone. No code change. All other timings improve by 2 to 4 times.
3. **F3.** Make the analysis cheaper. Medium risk, but `TripReplayTest` covers the results.
4. **F7.** Start the trip read during the slide-in. Low risk, one line.
5. **F2 (parallel read), F8, F6, F9.** Measure each one before and after. Keep only the changes that give a measured gain.

For each approved fix: measure before and after, run the debug build, lint, and the relevant device tests on the emulator, and update `PROGRESS.html`.

## Files of this review

All raw data is in the ignored `artifacts/perf/` folder of the review worktree
(`.claude/worktrees/performance-review-71251c/artifacts/perf/`):

- the traces `phone-rel-replay.perfetto-trace` and `phone-rel-open.perfetto-trace`;
- the trace config `trace-config.pbtx` and the queries `replay.sql`, `open.sql`, and `frame.sql`;
- the timing patch `instrumentation.patch` (apply it with `git apply` to repeat the measurements; never commit it);
- `seed_trips.py`, a script that adds simulated long trips to an emulator database (not used in this review);
- `tools/bin/trace_processor_shell.exe`, Perfetto v59.1;
- `phone-backup/phone-installed-2026-10-30.apk`, the APK that was on the phone before the review.
