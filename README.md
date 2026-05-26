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

## Built with

Kotlin · Jetpack Compose · Room · OpenStreetMap (Photon search, Overpass geometry,
osmdroid maps) · AlarmManager notifications.
