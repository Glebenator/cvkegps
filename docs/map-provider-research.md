# Map provider research: Google Maps SDK in place of OpenStreetMap tiles

Research date: 26 October 2026. App code baseline: `be7e5ce`.

Status: research only. No app code changed. A decision needs an ADR.

## Problem

The trip maps use MapLibre with raster tiles from `tile.openstreetmap.org`
([RouteMap.kt:100-121](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt)).
The OpenStreetMap Foundation (OSMF) tile policy gives no service level. It can
block an app without notice, and it warns that commercial services can lose
access at any time.
[OSMF tile usage policy](https://operations.osmfoundation.org/policies/tiles/)
The current User-Agent also contains the development application ID
`com.example.cvkegps`.

## Recommendation

Use the Google Maps SDK for Android through `maps-compose`, if the product
accepts these two conditions:

1. The map needs Google Play services. On a device without Play services, the
   map is not available. Recording, the speedometer, and saved trip values
   continue to work.
2. The Play Data safety form declares the data that the Maps SDK collects (see
   "Privacy and Play").

If the product does not accept these conditions, keep MapLibre and change only
the tile source to a provider whose terms permit app use. That change is
smaller. It does not change the map engine, the tests, or the Play services
requirement.

Do a short spike before the full change. Measure the battery and GPU use of
the live map with the same test as review item B3.

## Comparison

| Topic | Current: MapLibre + OSM raster | Google Maps SDK + maps-compose |
| --- | --- | --- |
| Tile service | Best effort. Can be blocked without notice. | Commercial service with terms and a quota. |
| Cost | No charge, but no right of use at scale. | Mobile map loads without a map ID use the "Maps SDK" SKU. It bills one load per `MapView` creation; pan and zoom do not add loads. Google's price list shows this SKU with an unlimited free cap. Confirm the price in the Cloud console before release. A map ID (cloud styling) changes the SKU to the paid "Dynamic Maps" SKU, so do not use a map ID. |
| Account | None. | Google Cloud project with billing turned on, and an API key. |
| Device need | None. | Google Play services. `minSdk` 23 or higher (this app: 29). |
| Dark theme | The app dims and desaturates raster tiles ([RouteMap.kt:117-121](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt)). | `mapColorScheme` follows the system theme by default (maps-compose 8.6.0 and later). |
| Rendering | Raster tiles, TextureView mode (`textureMode(true)`). | Vector map, SurfaceView by default. |
| Lifecycle code | Manual `MapView` lifecycle and listener code ([RouteMap.kt:168-255](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt)). | `GoogleMap` composable owns the lifecycle. |
| Attribution | The app shows its own OSM caption and link. | The SDK shows the Google logo and legal text. Do not cover them. Use the map `contentPadding` for the system bars and the app's buttons. |
| APK size | MapLibre native libraries in the APK, plus OkHttp. | The map code is in Play services, so the APK becomes smaller. OkHttp is used only by `RouteMap.kt`, so it can go too. |
| iOS | MapLibre has an iOS SDK. | iOS uses a separate native map (MapKit or Google Maps SDK for iOS). AGENTS.md permits native UI on each platform; share only the map behaviour. |

Sources:
[Maps SDK for Android usage and billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing),
[SKU details](https://developers.google.com/maps/billing-and-pricing/sku-details),
[Pricing](https://developers.google.com/maps/billing-and-pricing/pricing),
[Project configuration](https://developers.google.com/maps/documentation/android-sdk/config),
[maps-compose releases](https://github.com/googlemaps/android-maps-compose/releases).

## Feature fit

All current map features have a direct equivalent:

| Current feature (RouteMap.kt) | Google Maps equivalent |
| --- | --- |
| Route line with gaps between segments (`:270-285`) | One `Polyline` for each recorded piece |
| Start and end markers (`:235-238`, `:289-292`) | `Circle` or `Marker` with an app icon |
| Accuracy circle polygon (`circle()`, `:410-420`) | `Circle(center, radius in metres)`. The custom polygon code goes away. |
| Direction arrow (`headingArrow()`, `:423-437`) | `Marker` with `flat = true` and `rotation` |
| Follow with north up or course up (`:294-306`) | `CameraPositionState.animate` or `move`. Use `cameraMoveStartedReason == GESTURE` to stop following. |
| Fit route (`:309-322`) | `CameraUpdateFactory.newLatLngBounds` |
| 48 dp follow, fit, and zoom buttons (`:342-373`) | Keep the app buttons. Turn off the SDK zoom controls. |
| Saved camera after rotation (`:164-167`) | `rememberCameraPositionState` is saveable. |
| "Map unavailable" and Retry (`:374-389`) | Keep. Also show it when `GoogleApiAvailability` reports that Play services are missing. |

The B2 review item (the full route is rebuilt for each fix) applies to any map
engine. Fix it separately: send only new points, or update the route line at a
lower rate.

## Privacy and Play

The Maps SDK collects these items by default: request metadata (device model,
OS, SDK version, an internal usage identifier), stack traces and crash metrics,
the IP address, and a pseudonymous Maps SDK identifier. Map interaction events
(pan and zoom) are also collected when the app uses the camera APIs. The app
developer must declare this in the Play Data safety section.
[Play data disclosure for the Maps SDK](https://developers.google.com/maps/documentation/android-sdk/play-data-disclosure)

Required changes:

- Update the Data safety form for the SDK data types above. Review item S2
  (map area sent to the tile service) stays open, with Google as the receiver.
- Change `map_privacy` and `trip_privacy` in
  [tracking_strings.xml](../app/src/main/res/values/tracking_strings.xml).
  They now name OpenStreetMap.
- Update the privacy policy.

## API key handling

- Put the key in the manifest meta-data `com.google.android.geo.API_KEY`.
- Keep the key out of Git. Use the Secrets Gradle Plugin with a
  `secrets.properties` file that Git ignores and a `local.defaults.properties`
  fallback file. AGENTS.md already keeps local credentials out of Git.
- In the Cloud console, restrict the key to the Maps SDK for Android and to the
  release package name and signing certificate SHA-1. Include the Play App
  Signing certificate. Do this when the development application ID changes.

## Work to change

| Item | Change |
| --- | --- |
| [RouteMap.kt](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt) | Rewrite `MapCanvas` with `GoogleMap`. `MapSurface` keeps its parameters, so `LiveTripScreen`, `SavedTripScreen`, and `TripComponents` do not change. |
| [libs.versions.toml](../gradle/libs.versions.toml), [app/build.gradle.kts](../app/build.gradle.kts) | Add `maps-compose` and `play-services-maps` and the Secrets plugin. Remove `maplibre` and `okhttp`. |
| [AndroidManifest.xml](../app/src/main/AndroidManifest.xml) | Add the API key meta-data. Check the merged permissions again (review item S5). |
| [TrackingDeviceTest.kt](../app/src/androidTest/java/com/example/cvkegps/TrackingDeviceTest.kt) | The helpers `cameraAtLatest` (`:275-294`) and `mapCamera` (`:808-821`) look for a MapLibre `MapView`. Change them to read the camera through a test tag or the Google `MapView`. The emulator images must include Google APIs (the current `sdk_gphone64` images do). |
| Strings, README, PROGRESS.html | Remove OpenStreetMap text and the attribution caption. Record the change. |
| `docs/adr/` | Add an ADR for the provider choice and the Play services requirement. |

## Open questions for the product owner

1. Is a map that needs Google Play services acceptable for the release markets?
2. Who owns the Google Cloud billing account and the API key?
3. Is the extra Data safety declaration for the Maps SDK acceptable?
4. Which native map will the iOS app use?

## To measure on a real device

- Battery and GPU use while the live map follows for 10 minutes: MapLibre
  raster against Google vector. Use the same camera behaviour for both.
- Cold start time of the first map screen.
- Data use for one hour of live map following.
