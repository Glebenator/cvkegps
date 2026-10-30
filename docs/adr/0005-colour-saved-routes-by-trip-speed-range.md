# Colour saved routes by the speed range of the trip

A saved trip and its replay can show the route in speed colours. The colours go from dark (slow) to yellow (fast) on the
viridis scale, and the scale uses the speed range of that one trip: the 5th and the 95th percentile of the moving speeds.
A legend on the map shows the speeds at the two ends. Speed colours are the default for saved trips. The current trip map
keeps one colour.

We chose the range of the trip, not a fixed range for each activity or one range for all trips. A bike ride and a motorway
drive then both use all colours, and the result does not depend on the activity estimate, which can be unknown or "car or
transit". The cost is that a colour means a different speed on each trip, so the legend is necessary.

We chose viridis over a red-to-green traffic scale and over one blue hue. Red to green fails for red-green colour blindness
and uses the status colours: red is danger and the end marker. One blue hue loses one end on a light or a dark map and looks
like the normal route. Viridis rises in lightness in order, so the order also shows without colour vision. An outline under
the line keeps both ends visible: dark on a light map, white on a dark or satellite map.

## Consequences

- The design language had no data colours. `SpeedRamp` in `ui/theme/Tokens.kt` adds them. They stay the same in both themes
  and with wallpaper colours.
- Speeds are averaged over 5 seconds, so one noisy fix does not change the colour. The scale has a range of at least 2 m/s,
  centred on the speeds, so a trip at an even speed shows the middle colours and does not show noise as large changes.
- Network positions have no speed. Their lines stay dashed in the warning colour.
- The route line uses map style spans: one span for each run of segments with the same rounded colour (32 steps). A long trip
  at 10 fixes each second can still need many spans. Measure the drawing time on a real phone.
- The iOS app must use the same scale, percentiles, smoothing, and colours.
