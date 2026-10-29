# Replay a trip as a map player with linked charts

The owner compared three replay concepts: a map player, linked charts, and a list of moments. The owner chose a fusion of
the first two (concept D, "Player with charts"). The design notes and mockups are outside the repository, in the session
notes of 28 October 2026.

At a glance, the replay is a map player: the map with numbered moments, the values at the playhead, a speed profile that is
also the scrubber, Play and Pause, Previous and Next moment, the replay speed, and moment chips. In detail, the same screen
shows three linked charts with one cursor: speed, barometric height, and along-track acceleration.

The calculations are in plain Kotlin without Android objects (`TripReplay.kt`), so the iOS app can use the same rules and
unit tests can check them. They run once, off the main thread, when the screen opens.

## Consequences

- We build in three steps, and each step is usable: (1) the calculations and the glance view; (2) the detail view with the
  three charts; (3) the landscape and foldable pane with a chart selector, and one chart at the largest text sizes.
- Step 1 uses a fixed panel under the map. Step 2 changes it to a bottom sheet with two stops.
- The along-track acceleration and the barometric height need a test on a real phone in a car before the screen is final.
- The playhead moves at most 10 times each second, and only while the replay plays and the screen is in front.
