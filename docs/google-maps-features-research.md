# Google Maps features for the app and for a pro version

Research date: 30 October 2026. App code baseline: `d14442b`.

Status: research only. No app code changed. Each feature needs an agreed product requirement.
A pro version needs a product decision and an ADR.

## Question

Which Google Maps Platform features can CVKE GPS use, and which of them suit a paid pro version?

## Summary

- The **Maps SDK for Android** is free with no limit when the app uses no map ID. Features that stay inside this
  SDK and use only the trip's own data add no cost and send no new data to Google. They suit both the free and the
  pro version. The best candidates are an all-trips map (heat map and clusters), a planned route overlay, and
  extra map styles.
- Two **on-device SDKs** cost money for each map load: the **Maps 3D SDK** (photorealistic 3D, generally available
  since 14 October 2026) and **Street View**. They need no server. They are the best pro candidates:
  a 3D fly-over replay of a trip, and Street View at the trip moments.
- The **web service APIs** (Elevation, Roads, Geocoding) need a server that keeps the API key, and they send trip
  coordinates to Google. The terms also limit what the app can keep. Roads results can stay for 30 days only.
  Elevation values cannot be stored. Do these only after the server and privacy decisions.
- These are **not possible or not useful**: speed limits (limited access), offline maps (no offline mode and no
  download of tiles), weather and air quality on trips (short cache periods and use limits), turn-by-turn
  navigation, and an in-car (Android Automotive) version.

## What the app uses now

The trip maps use `maps-compose` 8.3.0 and `play-services-maps` 20.0.0 without a map ID
([ADR 0002](adr/0002-use-google-maps-for-trip-maps.md), [libs.versions.toml](../gradle/libs.versions.toml)).
[RouteMap.kt](../app/src/main/java/com/example/cvkegps/tracking/RouteMap.kt) uses these SDK features:
normal, satellite, hybrid, and terrain map types; traffic; 3D buildings; the map colour scheme; route lines with
`StyleSpan` gradients for speed colours; circles; markers with the toolbar that opens Google Maps.

The app does not use Street View, tile overlays, the Maps utility library, lite mode, map snapshots, or any web service API.

## Rules that apply to all ideas

### Cost

Since March 2025, each SKU has its own free monthly cap. Essentials SKUs have 10,000 free events, Pro SKUs have
5,000, and Enterprise SKUs have 1,000. The cap is for each billing account, not for each user.
[Pricing](https://developers.google.com/maps/billing-and-pricing/pricing)

| SKU | Free each month | Price after the cap, per 1,000 | What triggers it |
| --- | --- | --- | --- |
| Maps SDK | No limit | None | A map created without a map ID |
| Dynamic Maps | 10,000 | $7.00 | On mobile, a map created **with** a map ID |
| Immersive Maps | 5,000 | $7.00 | A map load in the Maps 3D SDK for Android |
| Dynamic Street View | 5,000 | $14.00 | Each Street View panorama object |
| Street View Metadata | No limit | None | A check for Street View coverage at a place |
| Elevation | 5,000 | $5.00 | One Elevation API request |
| Roads: Route Traveled | 5,000 | $10.00 | One Snap to Roads request (up to 100 points) |
| Geocoding | 10,000 | $5.00 | One Geocoding API request |
| Weather Usage | 10,000 | $0.15 | One Weather API request |
| Navigation SDK: Navigation Request | 1,000 | $25.00 | One navigation destination |

Prices are the global list prices on the research date. Confirm them in the Cloud console before release.
[SKU details](https://developers.google.com/maps/billing-and-pricing/sku-details)

A map ID moves **every** map load of that map to the paid Dynamic Maps SKU. Use a map ID only on a pro screen that
needs it, not on the trip maps.

### Terms

These rules come from the Google Maps Platform Terms of Service and the Service Specific Terms:

- **No caching.** The app must not store Google Maps content, except where the Service Specific Terms permit it.
  [Terms 3.2.3(b)](https://cloud.google.com/maps-platform/terms)
  - Place IDs can stay without a time limit.
    [Service Specific Terms A.3](https://cloud.google.com/maps-platform/terms/maps-service-terms)
  - Geocoding addresses can stay without a time limit, only for the user who asked for them.
    [Service Specific Terms 6.3.2](https://cloud.google.com/maps-platform/terms/maps-service-terms)
  - Roads API coordinates can stay for 30 days only.
    [Service Specific Terms 17.3](https://cloud.google.com/maps-platform/terms/maps-service-terms)
  - The Elevation API and the Time Zone API have no caching permission. The app cannot store their values.
  - A map image saved to disk (for example, a trip list thumbnail) is stored map content. Do not do it.
- **No prefetch or bulk download.** The app must not prefetch, store, or bulk download map tiles, roads data, or
  elevation values. An offline map mode with Google content is therefore not possible.
  [Terms 3.2.3(a)](https://cloud.google.com/maps-platform/terms)
- **No use with a non-Google map.** Google content (for example, Places or Roads results) must not show on another
  map. App data, such as the recorded route, can show on the Google map.
- **No navigation copy.** The app must not use the Maps SDK and the Directions or Roads APIs to make real-time
  navigation similar to Google Maps. [Terms 3.2.3(d)](https://cloud.google.com/maps-platform/terms)
- **No embedded vehicle systems.** The app must not use the services in an application built into a vehicle,
  such as an in-dashboard system. [Terms 3.2.3(f)](https://cloud.google.com/maps-platform/terms)
- **Attribution.** Do not hide or change the Google Maps logo or the data provider text. An image that the app shares
  must keep them visible. [Maps SDK policies](https://developers.google.com/maps/documentation/android-sdk/policies)
- An account with a billing address in the European Economic Area uses separate terms. Check the billing address.

### Privacy

AGENTS.md does not permit network transmission of location data without an agreed product requirement. Today Google
receives only the map area that the user looks at. A web service feature sends trip coordinates. Each such feature
needs an agreed requirement, a clear opt-in before the first request, a Data safety update, and a privacy policy update.

### API key and server

The Maps SDK and Street View use the current key, restricted to the Android app. Web service APIs are different.
Google recommends a secure proxy server for mobile use of Geocoding and Time Zone. For direct calls, Google gives only a
fallback: a separate key with Android app headers, kept in a secure keystore.
[API security best practices](https://developers.google.com/maps/api-security-best-practices)

A pro version with web service features therefore needs a server. The server keeps the key, checks the Play
subscription, limits the requests of each user, and does not log coordinates. The app has no server today.

## Feature candidates

### Group A: free, in the Maps SDK, no new data to Google

These use only the Maps SDK, `maps-compose`, and the open-source Maps utility library. They cost nothing for each use.

| Feature | Google feature | Notes |
| --- | --- | --- |
| **All-trips map**: one map with every saved trip as a heat map, and the trip starts as clusters | `HeatmapTileProvider` (Maps utility library), clustering in `maps-compose-utils`, `TileOverlay` | Uses only local trips. Good pro feature with no running cost. Large trip counts need calculation off the main thread. |
| **Planned route overlay**: show an imported route on the live and saved trip maps | KML and GeoJSON layers (Maps utility library) | The library reads KML and GeoJSON, not GPX. The app already writes GPX ([Gpx.kt](../app/src/main/java/com/example/cvkegps/tracking/Gpx.kt)), so a GPX reader in app code is also possible. |
| **More map styles**: for example a minimal style for driving or a high-contrast style | JSON styling (`MapStyleOptions`), no map ID | Free. The style must then handle dark mode itself. The release notes show no deprecation of JSON styling up to 20.1.0. |
| **Transit layer** in the map type menu | `setTransitEnabled()`, Maps SDK 20.0.0 | The app already has 20.0.0. `maps-compose` 8.3.0 needs `MapEffect` for it. Small; suits the free version. **Not usable yet** (see below). |
| **Trip list thumbnails** | Lite mode | Lite mode has no dark mode, no gestures, and no tile overlays. Do not save the image to disk (no caching). Measure the list performance first. |
| **Share card**: an image of the route with the trip values | None: the app draws the route from its own points | Free and with no terms risk. A map snapshot in the image is stored and shared map content. Check the terms before that option. |

**Transit layer check, 30 October 2026.** The `setTransitEnabled()` and `isTransitEnabled()` methods are in the
20.0.0 library. On a Pixel 10 Pro API 37 emulator with Google Play services 26.41.31, the map logs
"setTransitEnabled is not yet supported", and `isTransitEnabled()` stays false after the call. The app therefore has no
transit control, because a control without an effect is not permitted. Check again with later Play services versions
and on a real phone.

[Lite mode](https://developers.google.com/maps/documentation/android-sdk/lite),
[maps-compose](https://github.com/googlemaps/android-maps-compose),
[Maps SDK release notes](https://developers.google.com/maps/documentation/android-sdk/release-notes)

### Group B: paid for each load, on the device, no server

| Feature | Google feature | Cost | Notes |
| --- | --- | --- | --- |
| **3D fly-over replay**: the trip route on a photorealistic 3D map, with a camera that flies along it | Maps 3D SDK for Android: polylines, markers, `flyCameraTo` and `flyCameraAround` camera paths | Immersive Maps: 5,000 loads free, then $7 per 1,000 | Generally available since 16.0.1 (14 October 2026). A camera that follows the route needs app code that moves the camera in steps; test that it is smooth. Coverage of photorealistic 3D is limited; check it. The `ROADMAP` mode (0.2.2) can show other areas. Check if it needs a map ID. |
| **Street View at the moments**: the Street View image at the fastest point or the highest point of a trip | Street View panorama (`StreetView` composable) | Dynamic Street View: 5,000 free, then $14 per 1,000 | Check coverage first with the free Street View Metadata request. Open a panorama only when the user selects it. |

[Maps 3D SDK release notes](https://developers.google.com/maps/documentation/maps-3d/android-sdk/release-notes),
[Camera path animations](https://developers.google.com/maps/documentation/maps-3d/android-sdk/custom-camera-paths),
[Maps 3D SDK billing](https://developers.google.com/maps/documentation/maps-3d/android-sdk/usage-and-billing)

### Group C: web services, need a server, send trip coordinates

| Feature | Google feature | Cost | Terms limit |
| --- | --- | --- | --- |
| **Ground height profile and totals**: height of the ground along the route, and total climb | Elevation API path request, up to 512 samples | 5,000 free, then $5 per 1,000. One request for each trip view. | No caching: request the values again each time. The barometric height already gives height changes on the device. Use the ground height to set the level, or to check the totals. |
| **Snapped route** for car trips: the route on the road lines | Roads API Snap to Roads, up to 100 points, points 300 m apart or less | 5,000 free, then $10 per 1,000. About 4 to 5 requests for a 1 hour drive at one point each 10 s. | Snapped coordinates can stay for 30 days only. Show them as a display layer. Keep the recorded route and distance as the record. |
| **Place names**: "Home to Office" as a trip title | Geocoding API (reverse geocoding) | 10,000 free, then $5 per 1,000. Two requests for each trip. | Addresses can stay without a time limit, for that user only. |
| **Regions visited**: countries and regions of all trips, coloured on a map | Data-driven styling for boundaries, with place IDs from reverse geocoding | The map needs a map ID: Dynamic Maps, 10,000 free, then $7 per 1,000. Plus the geocoding requests. | Place IDs can stay without a time limit. Use the map ID on this one screen only. |

Android's own `android.location.Geocoder` can also give place names. It needs no API key and no server, and it is not a
Maps Platform service, so the Maps Platform cost and caching rules do not apply. Results can be missing. It is a good
first choice for place names in the free version.

[Elevation requests](https://developers.google.com/maps/documentation/elevation/requests-elevation),
[Snap to Roads](https://developers.google.com/maps/documentation/roads/snap),
[Data-driven styling for boundaries](https://developers.google.com/maps/documentation/android-sdk/dds-boundaries/overview)

### Not possible or not recommended

| Feature | Reason |
| --- | --- |
| Speed limits on the speedometer | The Roads API speed limit service is for customers with an Asset Tracking license only. Google also states that the data can be inaccurate or old. [Speed limits](https://developers.google.com/maps/documentation/roads/speed-limits) |
| Offline maps | The Maps SDK has no offline mode. The terms do not permit prefetch or bulk download of tiles. |
| Weather on each trip | Hourly history goes back 24 hours only. The app can keep it for 240 hours only. The terms do not permit a weather product. [Hourly history](https://developers.google.com/maps/documentation/weather/hourly-history), [Service Specific Terms 21](https://cloud.google.com/maps-platform/terms/maps-service-terms) |
| Air quality on trips | The terms permit Air Quality data only for routing and for environmental risk indices or research. [Service Specific Terms 2.1](https://cloud.google.com/maps-platform/terms/maps-service-terms) |
| Time zone of a trip from the Time Zone API | No caching permission. Store the device time zone when the trip records instead. This needs no network. |
| Turn-by-turn navigation | The Navigation SDK is an Enterprise SKU ($25 per 1,000 after 1,000 free). It is outside the product scope. "Open in Google Maps" through the marker toolbar already gives directions. |
| Android Automotive version | The terms do not permit use in a system built into a vehicle. |
| Map ID on all trip maps (cloud styling, Advanced Markers) | Each map load becomes paid, also for free users. |

## Pro version fit

| Feature | Version | Reason |
| --- | --- | --- |
| Transit layer | Free | Small change, no cost. Not supported by the Play services map yet. |
| Place names with `android.location.Geocoder` | Free | No cost, no server. |
| Share card from the app's own drawing | Free or pro | No cost. |
| All-trips map (heat map and clusters) | Pro | Clear user value, no running cost, no new data to Google. |
| Planned route overlay | Pro | Useful for cyclists and drivers, no running cost. |
| More map styles | Pro | No running cost. Small value alone. |
| 3D fly-over replay | Pro | Strong visual feature. Cost for each load. No server. |
| Street View at the moments | Pro | Cost for each panorama. No server. |
| Ground height profile, snapped route, regions visited | Later pro | They need a server, an opt-in for trip upload, and work around the caching limits. |

### Cost example for one active pro user

Assumptions for one month: 10 3D replays, 10 Street View panoramas, 30 ground height profiles, 10 snapped one-hour car
trips (50 requests), and 60 Geocoding API requests. Prices are after the free caps.

| Item | Cost |
| --- | --- |
| 3D replay: 10 × $0.007 | $0.07 |
| Street View: 10 × $0.014 | $0.14 |
| Ground height: 30 × $0.005 | $0.15 |
| Snapped route: 50 × $0.010 | $0.50 |
| Geocoding: 60 × $0.005 | $0.30 |
| **Total** | **about $1.16** |

The free caps are shared by all users. For example, 5,000 free Immersive Maps loads cover 500 users at 10 loads each
month. Group B alone costs about $0.21 for each active pro user after the cap. Set quotas in the Cloud console so that
a fault or misuse cannot make an unlimited bill.

## Recommended next steps

1. Decide the pro version scope and the payment model (Play Billing subscription or one-time purchase). Record it in an ADR.
2. Start with Group A and Group B. They need no server and no new data transmission.
3. Do a spike for the 3D fly-over replay: coverage, smoothness of a camera that follows the route, map ID need,
   battery and GPU use on a real device.
4. Decide on a server before any Group C feature. Also decide the opt-in text and the Data safety changes.

## Open questions for the product owner

1. Is a pro version with a running cost for each user acceptable, or must pro features have no running cost?
2. Is a server for the app acceptable? It would receive trip coordinates.
3. Is an opt-in upload of trip coordinates to Google acceptable for pro features?
4. Is the billing account address in the European Economic Area?
5. Which pro features must the iOS app also have? The Maps 3D SDK also has an iOS version.

## Sources

- [Google Maps Platform pricing](https://developers.google.com/maps/billing-and-pricing/pricing)
- [SKU details](https://developers.google.com/maps/billing-and-pricing/sku-details)
- [Maps SDK for Android usage and billing](https://developers.google.com/maps/documentation/android-sdk/usage-and-billing)
- [Maps SDK for Android release notes](https://developers.google.com/maps/documentation/android-sdk/release-notes)
- [Maps SDK for Android policies](https://developers.google.com/maps/documentation/android-sdk/policies)
- [Lite mode](https://developers.google.com/maps/documentation/android-sdk/lite)
- [Data-driven styling for boundaries](https://developers.google.com/maps/documentation/android-sdk/dds-boundaries/overview)
- [Maps 3D SDK for Android](https://developers.google.com/maps/documentation/maps-3d/android-sdk/overview),
  [release notes](https://developers.google.com/maps/documentation/maps-3d/android-sdk/release-notes),
  [camera paths](https://developers.google.com/maps/documentation/maps-3d/android-sdk/custom-camera-paths),
  [usage and billing](https://developers.google.com/maps/documentation/maps-3d/android-sdk/usage-and-billing)
- [Roads API: Snap to Roads](https://developers.google.com/maps/documentation/roads/snap),
  [Speed limits](https://developers.google.com/maps/documentation/roads/speed-limits)
- [Elevation API requests](https://developers.google.com/maps/documentation/elevation/requests-elevation)
- [Weather API hourly history](https://developers.google.com/maps/documentation/weather/hourly-history)
- [API security best practices](https://developers.google.com/maps/api-security-best-practices)
- [Google Maps Platform Terms of Service](https://cloud.google.com/maps-platform/terms)
- [Google Maps Platform Service Specific Terms](https://cloud.google.com/maps-platform/terms/maps-service-terms)
- [android-maps-compose](https://github.com/googlemaps/android-maps-compose)
