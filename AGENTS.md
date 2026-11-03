# Project rules: CVKE GPS product

CVKE GPS is a GPS speedometer and trip recorder with a native Android app and a native iOS app. It has three
repositories:

- [cvkegps](https://github.com/Glebenator/cvkegps): this repository, the product. Decisions (ADRs), the glossary, the
  shared measurement rules, the research, and the progress report. It has no app code.
- [cvkegps-android](https://github.com/Glebenator/cvkegps-android): the Android app. Its `AGENTS.md` has the Android rules.
- [cvkegps-ios](https://github.com/Glebenator/cvkegps-ios): the iOS app. Its `AGENTS.md` has the iOS rules.

Until 2 November 2026, both apps were in this repository. The tag `archive/before-split-2026-11-02` marks the last
commit with both apps. Links in older documents go to that commit.

## Product

- Build native apps for publication on Android and iOS.
- Use ASD-STE100 Simplified Technical English for instructions and documentation.
- Implement only the agreed feature scope. Do not add inactive controls or simulated production data.
- Commit and push only when the user asks.

## Shared rules for both apps

- Both apps have the same measurement rules and product behaviour. Keep platform behaviour native, and do not force
  shared UI code.
- Keep measurements in SI units. Convert them only for display.
- Record each decision that affects both apps, and each intended difference between the apps, in an ADR in `docs/adr/`.
- Define each shared term in `GLOSSARY.md`.
- Show missing or old measurements as unavailable. Never show an old speed as a current speed.
- Explain location use before the system asks. Provide a recovery action when access or location services are off.
- Support light and dark appearance, large text, screen readers, and rotation.
- Run location only while a feature needs it. Do not start or restart a trip silently in the background. A recording
  trip has a visible notification on Android and a Live Activity on iOS.
- Do not log coordinates or include them in crash reports.
- Do not add analytics, network transmission, or cloud backup of location data without an agreed product requirement.
- Treat emulator and simulator results as functional evidence only. Measure battery use and GPS accuracy on real devices.

## Progress report

- Keep `PROGRESS.html` current after each completed feature, fix, milestone, or change to verification results in
  either app.
- Update it in a separate change after the app change. Name the app repository and its commit as the baseline.
- Do not mark planned or untested work as complete.
- Record the update date, app code baseline, completed scope, test evidence, known limits, and required and optional
  future work.
- Add a dated change-history entry for meaningful progress. Preserve earlier milestone entries.
- Use real app screenshots for UI changes. Store selected captures in `docs/progress/images/` with dated names.
- Save report screenshots as WebP (lossy, quality about 85, at the capture size). They average about a tenth of a PNG,
  and every image stays in the Git history. Use PNG only where exact pixels matter, such as an app icon.
- Track these selected report images in Git. Keep raw captures and other generated output in the ignored `artifacts/`
  folder.
- Caption each image with its date, app baseline, and relevant state. Identify simulated location data.
- Keep code excerpts short and accurate. Link each excerpt to its source in the app repository, at the commit of the
  excerpt.
- Keep the report readable without a build step. Use relative links to local assets such as images.
- Check the report layout and links after report changes. Check narrow screens after layout changes.

## Agent skills

### Issue tracker

Issues are in GitHub Issues. Use the `gh` CLI. App issues are in `Glebenator/cvkegps-android` and
`Glebenator/cvkegps-ios`. A feature or a decision that affects both apps has a parent issue in `Glebenator/cvkegps`,
with one sub-issue in each app repository. See `docs/agents/issue-tracker.md`.

### Triage labels

Use the five default triage labels: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`.
See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: one `GLOSSARY.md` and one `docs/adr/` at the root of this repository. See `docs/agents/domain.md`.
