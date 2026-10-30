# Map styles as JSON styles without a map ID

Settings > Maps > Map style selects **Standard**, **Minimal**, or **High contrast**. Standard is the Google map with the
dark map of the Maps SDK, as before. Minimal shows roads, names, water, and parks in the neutral colours of the design
tokens, without places of business and transit. High contrast has strong road edges and black or white text, for
sunlight and low vision. Standard is the default.

We use JSON styles (`MapStyleOptions`) from files in `res/raw`, not cloud-based map styling. Cloud styling needs a map ID,
and a map ID changes each mobile map load to the paid Dynamic Maps SKU. A JSON style costs nothing and sends no new data
to Google.

A JSON style replaces the automatic dark map of the SDK. Each style therefore has a light and a dark file, and the map
uses the file that agrees with the app theme. The light files are light and the dark files are dark, so the outline rule
of [ADR 0005](0005-colour-saved-routes-by-trip-speed-range.md) still applies: a dark outline on a light map, and a white
outline on a dark map.

The Maps SDK applies styles to the normal map type only. The style therefore applies to the Default map type. Satellite,
satellite labels, and terrain keep the Google look. The style choice stays when the user selects another map type and
applies again on Default.

## Consequences

- The Minimal files take the land, road, border, and text colours from the `bg`, `surface`, `border`, `border-control`,
  `ink`, and `ink-muted` tokens. A change to these tokens needs a change to the files.
- Minimal hides transit lines and stations. Check the transit layer with Minimal when it is added.
- The map reads the style file off the main thread. A new map can show the Standard map for a moment before the style
  arrives. This was not measured.
- At street zoom the Maps SDK draws no road edges. High contrast therefore uses built-up areas with a 2:1 contrast to the
  roads. Map text has a contrast of 4.5:1 or more to its halo and to the water in all four files.
- The device test checks that the land or built-up area colour of each style and theme is the most common map colour.
  It needs map tiles from the network.
- Google can change or end JSON styling. The release notes show no deprecation up to Maps SDK 20.1.0. Without JSON
  styling, the styles would need cloud styling and a map ID, which has a cost for each map load.
- The iOS app must offer the same three choices. The iOS look can differ if its native map has fewer style controls.
