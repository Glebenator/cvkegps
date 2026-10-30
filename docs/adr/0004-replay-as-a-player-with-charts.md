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
- Step 1 uses a fixed panel under the map. Step 2 adds the second stop as a panel with a drag handle and a Charts and Map
  button, not the Material bottom sheet component: the sheet's own vertical drag competes with the chart drags and the
  page scroll, and the panel keeps the map strip a fixed height. Back in the details returns to the glance stop.
- Step 3 keeps the moment chips in the details. One chart with a selector replaces the three charts where they would be
  too small: a phone in landscape and text at 150% or more. A wide window shows the map beside a pane of about half the
  window with the three charts. The map mutes the route after the playhead.
- The replay is a mode of the saved trip screen, not a separate screen. The map stays, so it does not load again, and only
  the pane and the top bar change. Back leaves the details, then the replay. This replaces a camera hand-over between two
  maps, which still showed a blank map while the second map loaded.
- The along-track acceleration and the barometric height need a test on a real phone in a car before the screen is final.
- The playhead moves at most 10 times each second, and only while the replay plays and the screen is in front. While it
  plays, the map alone moves the position and the camera on each display frame between two playhead steps, and the
  direction of travel turns evenly between fixes. The replay keeps one zoom level. The frame cost must be measured on a
  real phone: the emulator draws this screen at 3 to 5 frames each second with or without the frame updates.
