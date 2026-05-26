# LastPlace

A personal Android app that helps you avoid street-cleaning parking fines. Save the
streets near you and when they're cleaned, register where you park, and get reminded to
move your car before the next cleaning window.

## What it does

- **Add streets from real map data** — search a street by name or tap it on a map
  (OpenStreetMap). The app stores the street's full shape, so it recognizes you anywhere
  along it, not just one spot.
- **Set cleaning hours** — for each street, add the weekdays and time windows when
  parking isn't allowed.
- **See where you can park longest** — your streets are ranked by time until their next
  cleaning, each showing how long it's free (e.g. **Free for 3d 5h**).
- **Park in one tap** — "Park where I am" uses your location to detect which saved street
  you're on, or pick one manually.
- **Get reminded in time** — a notification fires a configurable number of hours before
  cleaning starts, telling you which street you're on and how long you have. Tap
  "I moved it" to clear it.

Everything is stored locally on your device — no account, no servers.

## Build & run

The repo includes a Nix dev shell with Android Studio:

```bash
nix develop
android-studio
```

Open the project, let Gradle sync, then Run ▶ on your phone (with USB debugging on) or an
emulator. Run the tests with `./gradlew test`.

## Releasing an update

Updates are delivered over the air via [Obtainium](https://github.com/ImranR98/Obtainium):
push a version tag and GitHub Actions builds, signs, and publishes a new APK.

After making your changes:

```bash
git add -A
git commit -m "describe your changes"
git push

# tag the new version (bump the number each time) and push the tag
git tag v0.1.2
git push origin v0.1.2
```

Pushing the tag triggers the release workflow. Watch it finish with:

```bash
gh run watch
```

Once it's green, open Obtainium on your phone (pull down to refresh) — it shows
**Update available** for LastPlace; one tap installs it.

Notes:
- **Always bump the tag** (`v0.1.2` → `v0.1.3` → …). The version number must increase or
  Android won't install the update over the existing app. The internal version code is
  set automatically from the CI run number.
- Signing is handled in CI using the release keystore stored in the repo's GitHub
  Secrets — every release is signed with the same key, which is what lets updates install
  cleanly. Keep that keystore (and its passwords) backed up.

## Built with

Kotlin · Jetpack Compose · Room · OpenStreetMap (Photon search, Overpass geometry,
osmdroid maps) · AlarmManager notifications.
