# Use the Google Maps SDK for the trip maps

The trip maps used MapLibre with raster tiles from `tile.openstreetmap.org`. The OpenStreetMap Foundation tile policy gives no
service level and can block an app without notice, so it is not a base for a published app.
We now use the native Android map: the Google Maps SDK through `maps-compose`. It gives a vector map that follows the app's
light or dark theme, satellite and terrain map types, live traffic, 3D buildings, the native compass, rotate and tilt
gestures, markers with info windows, and the marker toolbar that opens a place in Google Maps.
The iOS app will use its own native map. The two apps share the map behaviour, not the map code.

We kept the app's own position marker. The SDK's My Location layer runs its own location requests and cannot show an old
position as old. The app draws its own dot, accuracy circle, and direction arrow from the fixes that it already receives,
so a stale position stays grey and GPS has one request.

## Consequences

- The map needs Google Play services. Without them, the map area shows a message and the Play services dialog that installs,
  updates, or turns them on. Recording, speed, and saved trip values work without a map.
- The Maps SDK sends the map area, the IP address, and technical data to Google. The Play Data safety form and the privacy
  policy must declare this before release. The app shows the same statement before the first trip and on each saved trip.
- A Google Cloud project with billing and an API key is necessary. The key stays out of Git (`MAPS_API_KEY` in
  `local.properties`). Restrict it to the Maps SDK for Android, the release application ID, and the signing certificates.
  Do not use a map ID: it changes the mobile map loads to a paid SKU.
- `maps-compose` 8.4 and later need Kotlin 2.4. The app stays on 8.3.0 until the Kotlin update.
- The SDK draws a route line across the date line the short way, so the app no longer splits lines there.
