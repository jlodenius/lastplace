# LastPlace

A personal Android app that helps you avoid street-cleaning parking fines. Save the
streets near you and when they're cleaned, register where you park, and get reminded to
move your car before the next cleaning window.

## What it does

- **Add streets from real map data** — search a street by name or tap it on a map
  (OpenStreetMap). The app stores the street's full shape, so it recognizes you anywhere
  along it, not just one spot.
- **Set cleaning hours** — enter the weekdays and time windows from street signs, not the map.
  New or changed schedules require explicit day/time choices and a cleaning-hours acknowledgment.
  Overnight and 24-hour restrictions are explained before saving; name-only edits keep existing hours.
- **See where you can park longest** — available streets are ranked by time until their next
  cleaning, each showing **Can stay …**, based on saved cleaning hours. Streets being cleaned
  show when parking becomes available and cannot be selected until cleaning ends. The list
  updates automatically as cleaning starts and ends, including overnight windows.
- **Find daytime cleaning** — a positive sun badge highlights cleaning windows entirely
  within **08:00–18:00** (such as 09:00–14:00), with their weekdays and hours. Tap
  **Daytime cleaning** to show only streets with at least one such window. The badge describes
  the schedule, not current parking availability; other cleaning windows still apply.
- **Favourite nearby streets** — tap the small star beside a street. Favourites break exact ties
  in both the main list and re-parking suggestions; better parking opportunities still rank first.
- **Choose preferred re-parking times** — Settings shows the distinct cleaning end times across
  your saved streets. Tap to select any number of times. Choices update when streets change;
  new times start unselected, and removed times lose their selection.
- **Plan an easy re-park** — while parked, your parking card recommends the latest preferred
  cleaning-end opportunity before your current deadline. Only genuinely just-cleaned streets
  qualify. Equal-time opportunities rank by longest stay, then favourites. If none remain, the
  fallback is the longest-stay street available now. **View re-parking options** shows up to three
  distinct destinations without changing the main list. Recommendations refresh automatically;
  future plans are not current availability, and saved schedules cannot guarantee an empty space.
- **Select a planned move** — tap **Plan this move** on a suggestion or one of the top three
  options. The destination and time stay fixed until you change or cancel the plan, even if
  preferences or favourites change. Planning does not register parking: tap **I've parked here**
  only after moving. Schedule changes or a removed destination mark the plan as needing attention
  and cancel its reminders. Missed plans remain visible; they never silently move your car.
  The options view integrates the selected plan into its street's card, alongside up to two
  alternatives. A changed opportunity on the same street stays in that card without replacing
  your chosen time until you explicitly select it.
- **Register parking explicitly** — **Park where I am** uses a one-time location lookup, or choose
  **I've parked here** on a saved street without location permission. Initial registration is one tap;
  changing the parked street and ending parking ask for confirmation. The current street cannot restart
  its own session. Separately named sections with overlapping map geometry may require manual selection.
- **Choose independent reminders** — enable either or both **Planned move reminders** and
  **Parking deadline warnings**, or turn both off. Each supports multiple presets and custom
  offsets in minutes, hours, or days (up to 365 days); zero means at the time. For example, remind
  yourself 1 hour and 10 minutes before your chosen move, independently of 2-day and 1-day cleaning
  warnings. Move reminders default to at the planned time; existing deadline settings are retained.
  Past offsets are skipped when selecting a plan or adding reminders. Selecting an available-now
  fallback can send its at-time reminder immediately, without inventing a future availability.
- **Keep reminders in sync** — changes to plans, schedules and settings update alarms immediately.
  Changing or cancelling a plan leaves deadline warnings alone. Ending parking clears that session's
  plan and alarms. Pending reminders are restored after reboot or an app update; stale and duplicate
  alarms are checked before delivery. Undelivered move reminders more than 15 minutes past the
  planned time are suppressed. Home shows actual pending warnings separately from enabled preferences,
  including no upcoming warnings and due/delayed alarms. App/channel notification blocking and precise
  alarm limits have repair actions; system restrictions can delay or block delivery. Permission is
  explained after parking or explicitly enabling reminders, never merely on launch.
- **See when to move** — the Parking screen prioritizes the current street, countdown, exact local
  deadline, and reminder status. Deadlines within two hours say **Move soon**; passed deadlines say
  **Move now**. Plans remain secondary and never register arrival automatically.
- **Recover from mistakes and failures** — search distinguishes no matches from service failure and
  offers retry. Location errors keep a manual alternative visible. Unsaved editor changes ask before
  discarding; deleting the parked street ends its session and clears its plan and reminders.

Everything is stored locally on your device — no account, no servers.

## Build & run

The repo includes a Nix dev shell with Android Studio:

```bash
nix develop
android-studio
```

Open the project, let Gradle sync, then Run ▶ on your phone (with USB debugging on) or an
emulator. Run the local checks with:

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The second command requires a connected device/emulator.

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
- Tags containing `-beta.` (for example, `v0.4.0-beta.1`) publish as prereleases; ordinary tags
  remain normal releases. Publishing a beta requires explicit approval. Enable prereleases in
  Obtainium or manually install the signed APK, without uninstalling or clearing application data.
  A local debug-signed build cannot update an existing release-signed installation.
- To roll back a change, revert its commit and publish a newly versioned signed recovery release
  with a higher CI version code. Do not downgrade by uninstalling or clearing application data.

## Built with

Kotlin · Jetpack Compose · Room · OpenStreetMap (Photon search, Overpass geometry,
osmdroid maps) · AlarmManager notifications.
