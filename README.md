# CVKE GPS

A GPS speedometer and trip recorder with a native Android app and a native iOS app. This repository holds the product:
the decisions, the glossary, the shared measurement rules, the research, and the progress report. It has no app code.

| Repository | Contents |
|---|---|
| [cvkegps](https://github.com/Glebenator/cvkegps) | This repository: the product documents and the parent issues for both apps |
| [cvkegps-android](https://github.com/Glebenator/cvkegps-android) | The Android app: Kotlin, Jetpack Compose, Material 3, and Google Maps |
| [cvkegps-ios](https://github.com/Glebenator/cvkegps-ios) | The iOS app: SwiftUI, Apple Maps, Liquid Glass, and a Live Activity |

## Documents

- [PROGRESS.html](PROGRESS.html): the status of both apps, with screenshots, verification results, known limits, and
  future work. Open it in a browser.
- [GLOSSARY.md](GLOSSARY.md): the shared terms and measurement rules.
- [docs/adr/](docs/adr/): the decisions. [ADR 0007](docs/adr/0007-build-the-ios-app-with-apple-maps-and-a-shared-swift-core.md)
  lists the intended differences between the Android app and the iOS app.
- [docs/](docs/): the research of the map providers, the Google Maps features, and the location power use, and the
  first tracking plan.
- [AGENTS.md](AGENTS.md): the rules for this repository and the shared rules for both apps.

## History

Until 2 November 2026, both apps were in this repository: the Android app at the root, and the iOS app in `ios/`.
The tag `archive/before-split-2026-11-02` marks the last commit with both apps. Each app repository keeps the history
of its app. Links in the progress report and in older documents go to that commit.

The tag `archive/performance-review-71251c` keeps the performance review branch of 30 October 2026, with its progress
report entry. Its Android code is on the branch `clanker/performance-review-71251c` in cvkegps-android.
