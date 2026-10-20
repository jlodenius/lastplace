# UI/UX beta validation

## Scope and rollback

This implements the experiment in `BETA.md` against baseline `bb2c111`. The plan, implementation,
tests, this record, and conditional beta-prerelease publishing belong to one revertible commit.
No database schema/version or destructive preference migration was introduced. Historical parking
source strings and unused preference values remain readable stored data. Existing reminder defaults,
intentionally empty selections, and disabled categories are preserved.

Mapping, geometry coverage, automatic section recognition, and a Stockholm street catalogue remain
explicitly deferred. The map picker's behavior is unchanged apart from requesting Android's required
fine/coarse permission pair. Name or schedule edits do not regenerate geometry.
GPS can still confuse independently named sections with overlapping geometry; manual registration is
the dependable workaround.

## Automated checks

The checks use JDK 17 and Android SDK 35 from the existing Nix environment, with the Nix-provided
`aapt2` override. No signing credentials were accessed or added.

| Check                       | Result                                      |
| --------------------------- | ------------------------------------------- |
| `testDebugUnitTest`         | Passed                                      |
| `testReleaseUnitTest`       | Passed                                      |
| `lintDebug`                 | Passed: 0 errors, 29 advisory warnings      |
| `assembleDebug`             | Passed                                      |
| `assembleDebugAndroidTest`  | Passed: instrumented test APK compiled      |
| `connectedDebugAndroidTest` | Not run: no connected devices               |
| `git diff --check`          | Passed                                      |

Both unit-test variants passed all 149 tests with no failures or skips. The 25 Compose tests compiled
into the instrumented APK but were not executed.

Coverage includes:

- Opaque legacy source/settings compatibility and preservation of defaults and empty selections.
- Actual reconciled pending reminders, the less-than-12-hour default warning gap, delivered/skipped/
  stale/cancelled records, updating states, at-time reminders, due/delayed warnings, and independent
  app/channel/precise-alarm blockers.
- Plan changes and cancellation preserving current parking and unrelated deadline warnings.
- Confirmation cancellation, stale session/plan/deletion guards, availability rechecks, repeated-tap
  suppression, current-street no-op registration, and deleting current versus destination streets.
- Explicit schedule input and acknowledgment, name-only edits, unchanged geometry/favourites/notes,
  overnight and 24-hour summaries, and separately named street entries.
- Loading versus empty state, search failure versus no matches, retries, stale responses,
  denied/no-fix location recovery, and urgency at two hours, one minute, and the deadline.
- Compiled Compose tests for first-use guidance, current parking and urgency, confirmation dialogs,
  distinct row actions, labeled switches, editor discard/validation/search states, and large-text
  layout scenarios. Compilation is not a substitute for running them on a device.

## Device state matrix: not yet verified

No device or emulator was connected (`adb devices` returned an empty list). Neither baseline nor
redesigned screenshots were captured. All of the following remain manual acceptance work, not passed
checks. Use the same fixture data/window size for baseline and beta captures; never clear real data
or create destructive/overdue scenarios in the daily-use installation.

| Scenario          | Required checks                                      | Status  |
| ----------------- | ---------------------------------------------------- | ------- |
| Fresh install     | First action; no launch permission dialog            | Not run |
| Not parked        | GPS action and manual alternative                    | Not run |
| Active parking    | Location, countdown, deadline, reminder status       | Not run |
| Urgency           | Normal, soon, overdue, no deadline; resume           | Not run |
| Reminder trust    | No upcoming, blocked, due/delayed, exact access      | Not run |
| Selected plan     | Valid/missed/invalid; arrival/change/cancel          | Not run |
| Street list       | Filters, empty, cleaning now, custom sections        | Not run |
| Editor            | Incomplete/overnight/24-hour hours; discard/delete   | Not run |
| Failure recovery  | Offline search/retry; denied permission; no GPS fix  | Not run |
| Settings          | Android return; section links; test notification     | Not run |
| Adaptive layout   | Light/dark, landscape, keyboard, scaling             | Not run |
| Accessibility     | TalkBack labels/state/focus; targets; contrast       | Not run |
| Background/reboot | Actual delivery and restored alarms                  | Not run |

The light/dark palette and responsive layouts are implemented but not device-validated. Scheduled
reminders are not guarantees of notification delivery. Verify 200% font size, long names such as
`Ynglingagatan vändplan`, focus after dialogs/permissions, and system navigation/keyboard insets.

## Release and personal trial: pending approval

No commit or tag has been pushed and no release has been published or installed by this work.
Publishing still requires explicit approval under `BETA.md`. The workflow marks only tags containing
`-beta.` as prereleases and includes this validation record in their release notes. Ordinary release
tags retain normal release behavior.

Before a daily-use trial:

1. Complete device acceptance and record screenshots/notification observations.
2. Choose an unused beta version and publish with the existing release key/application ID and a
   higher version code. Do not install a debug build over real release data.
3. Enable prereleases in Obtainium or manually install the signed asset without uninstalling.
4. Verify both Ynglingagatan entries, their hours and names, plus all preferences, parking,
   and plans.
5. Trial a parking/cleaning cycle and record the keep/revert decision outside `BETA.md`.

If reverting, revert the single beta commit, run checks, and publish a new signed recovery build
with a higher version code. Do not uninstall, clear storage, or rely on an old lower-version APK.
