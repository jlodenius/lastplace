# LastPlace: reversible UI/UX beta

## 1. Purpose and agreement

This is an experimental beta, not a permanent redesign commitment. Build the changes below as **one
beta commit**, install a signed beta release, and test it in everyday use. If the result is not an
improvement, revert that commit and remove this plan with it.

This document is the implementation plan. Creating it does not implement, commit, or release the
beta.

- Source baseline at planning time: `bb2c111`.
- Working branch: `beta/ui-ux-plan`.
- Include this document, implementation, tests, and necessary release changes in the same beta
  commit.
- Preserve existing streets, custom names, cleaning rules, favourites, settings, parking, and plans.
- Do not introduce a database migration or destructive preference migration for this experiment.
- Keep the current local-first, account-free application and native Android foundation.

### What we want to learn

1. Can I understand my current parking, deadline, and reminder status at a glance?
2. Is the application calmer, clearer, and more visually coherent without becoming slower to use?
3. Can I register parking and manage a move without accidentally changing the wrong state?
4. Do the additional explanations help, or do they add unnecessary clutter and friction?

The earlier independent review and a separate parent review inspected source, not a rendered app.
Confirmed implementation gaps are recorded below. Clipping, actual contrast, TalkBack output, and
visual polish still require device verification. The visual specifications here are beta proposals,
not claims that these designs have already been tested.

## 2. Product decisions and scope boundaries

### Remove automatic parking entirely

Remove the automatic-parking feature, not just its switch:

- Remove the Settings row, coming-soon copy, setting model property, preference accessors, and
  setter.
- Remove unused automatic-parking enum cases and other feature-specific dead code, after checking
  uses.
- Update affected tests and documentation. Do not add a replacement disabled control or roadmap
  teaser.
- Keep **Park where I am**: this is a user-triggered GPS lookup, not background automatic parking.
- Keep manual selection as an equally valid way to register parking.

Existing unused preference values and historical session source strings can remain opaque stored
data. Do not rewrite them or remove database columns just to remove the feature. New code must not
depend on those values, and old data must remain readable after rollback.

### Defer mapping work

The long-term direction is a curated, pre-mapped catalogue of **all Stockholm streets**, including
sections with different restrictions. That is a separate, larger feature, not part of this beta.

Most of a street often shares one cleaning schedule, but not always. Preserve the current
workaround:

- `Ynglingagatan`: the main part of the street.
- `Ynglingagatan vändplan`: the turning area with a separate restriction.

These are independent saved entries, even if their restrictions fall on the same weekday. Do not
merge entries by name similarity, location, or shared geometry. Keep custom names editable and
visible in lists, parking status, plans, and reminders. Saving a name or schedule must not
regenerate geometry.

For this beta:

- Keep the existing map lookup, geometry capture, GPS matching, and map picker behavior.
- Do not build a street catalogue, geometry editor, coverage preview, side-of-street model, or
  importer.
- Do not implement automatic recognition of the correct named street section.
- Retain the mapping review findings below as explicitly deferred work, not forgotten requirements.
- Search progress and GPS-action error feedback are in scope; geometry and picker redesign are not.

Known limitation: GPS matching may not distinguish separately named sections with overlapping
captured geometry. Manual selection remains the dependable workaround. Do not imply that a custom
suffix alone makes GPS section-aware. The current overconfident mapping-status text is also deferred
with this work.

### Preserve the useful foundations

- Duration-first street ordering and the existing favourite tie-break behavior.
- Cleaning-now explanations and unavailable destinations being nonselectable.
- The daytime-cleaning filter as a schedule characteristic, not a claim of current availability.
- Independent deadline warnings and planned-move reminders.
- Planning never registering parking; arrival always requiring an explicit action.
- Fixed selected plans, visible missed/invalid states, and existing reminder reconciliation
  safeguards.
- Small hub-and-spoke navigation. No bottom navigation, account flow, or map-first home screen.

## 3. Review findings and beta disposition

| #   | Finding                                   | Beta decision                              |
| --- | ----------------------------------------- | ------------------------------------------ |
| 1   | Enabled warnings may produce no reminder  | Show actual upcoming warnings and blockers |
| 2   | Default cleaning hours look authoritative | Require deliberate schedule confirmation   |
| 3   | Automatic parking is enabled but absent   | Remove the entire unused feature           |
| 4   | State-changing actions lack recovery      | Add targeted confirmations                 |
| 5   | Partial mapping claims full coverage      | Defer with mapping and coverage previews   |
| 6   | Search/location failures appear silent    | Fix non-map progress, errors, and retry    |
| 7   | Setup prerequisites are hard to discover  | Add contextual first-use guidance          |
| 8   | Deadline urgency lacks visual priority    | Redesign home hierarchy and urgency states |
| 9   | Labels and narrow layouts need attention  | Fix semantics and verify adaptive layouts  |

Finding 1 includes a concrete default-settings gap: deadline offsets are 24 and 12 hours.
Registering parking less than 12 hours before cleaning skips both offsets, while home currently only
checks whether the category is enabled. Android notification and precise-alarm restrictions are only
shown in Settings.

Finding 2 concerns new rules silently starting at Tuesday 08:00–14:00. A mapped street is real map
data; its cleaning restriction is user-entered information. The UI must not blur that distinction.

Finding 5 also covers technical point/segment counts, lack of a coverage preview, and the map
picker's unexplained fallback location. These remain known limitations, not beta acceptance
blockers.

The review suggested undo for ending parking. This beta deliberately uses a confirmation instead: it
provides protection without introducing session restoration and reminder replay behavior. Whether
that extra tap feels worthwhile is one of the trial questions.

## 4. General visual design direction

### Character

Aim for a calm, compact, legible Android utility: neutral surfaces, one blue accent, strong time
hierarchy, and restrained use of cards. It should feel designed rather than like a sequence of
default components, but not decorative. No gradients, oversized illustrations, glass effects, or
animated maps.

Retain Material 3 components and the system font. Define shared theme tokens rather than scattering
individual colors, type sizes, and corner radii through screens. Support system light and dark
modes.

### Color and surfaces

Use the following starting palette. Verify contrast on the actual rendered components before
shipping; adjust paired foreground/background tokens together when necessary. Do not mix the blue
primary with unrelated default purple containers.

| Role                 | Light     | Dark      |
| -------------------- | --------- | --------- |
| Background           | `#F7F9FC` | `#10141B` |
| Surface              | `#FFFFFF` | `#181F28` |
| Primary accent       | `#1565C0` | `#9AC3FF` |
| On primary           | `#FFFFFF` | `#072A52` |
| Primary container    | `#E4EEFF` | `#203854` |
| On primary container | `#102F55` | `#DBE9FF` |
| Main text            | `#17212F` | `#EEF3FA` |
| Secondary text       | `#526174` | `#B4C0CF` |
| Decorative divider   | `#D8E1EC` | `#455263` |
| Warning container    | `#FFF3CF` | `#3E3118` |
| On warning container | `#634400` | `#FFE09A` |

- Define the remaining Material roles coherently, including interactive outlines and error colors.
- Decorative divider colors are not necessarily suitable for essential control boundaries.
- Use amber for imminent deadlines and Material error roles for overdue/invalid states.
- Pair status color with an icon and explicit text. Never communicate urgency only through color.
- Do not use a green "safe to park" state: saved cleaning hours cannot establish legal parking or
  space.
- Keep normal street rows on a neutral surface. Reserve emphasis for current parking and selection.
- Target at least 4.5:1 contrast for normal text and 3:1 for large text and essential control
  graphics.

### Typography and spacing

- Main countdown: approximately 32sp/40sp, semibold; allow wrapping and font scaling.
- Move-by time: approximately 22sp/28sp, medium.
- Street names and section headings: approximately 16sp/24sp, medium.
- Body text, important status, and actions: at least 14sp/20sp.
- Secondary metadata can use 12sp/16sp, but never the deadline, delivery blocker, or essential
  warning.
- Use sentence case. Prefer weight and spacing over many different font sizes.
- Use an 8dp spacing rhythm, 16dp screen gutters, 16dp card padding, and 24dp between major
  sections.
- Use about 12dp card corners and little or no elevation. Avoid enclosing every element in a card.
- Standard icons are 24dp. Every interactive icon has at least a 48dp touch target.
- Keep labels visible rather than relying on unexplained icons. Favourite state must also be
  announced.
- On wide layouts, constrain the content to a readable centered column instead of stretching
  controls.

### Controls and motion

Use filled buttons for the primary action in a section, outlined/tonal buttons for secondary
actions, and text buttons for tertiary actions. Do not give every available action equal visual
weight.

Keep **End parking** visible next to the current street where space permits; stack it on narrow
screens. Do not hide it in an overflow menu. It remains secondary to the deadline, not a competing
large button.

Avoid fixed-height text containers, clipped street names, and ellipsized deadlines. Long names such
as `Ynglingagatan vändplan` must remain distinguishable. Let buttons stack or wrap rather than
shrink text. Use subtle standard state transitions only; no pulsing urgent cards or moving countdown
layouts.

## 5. Home screen specification

Rename the screen heading to **Parking**; use **Saved streets** as the list section heading. Keep
Settings in the top app bar and the add-street action available. Avoid showing redundant add actions
in the empty state. Keep list bottom padding clear of the floating action button and system
navigation.

### No saved streets

Replace the nonfunctional first-use GPS emphasis with a compact introduction:

> Save the streets you use and enter their cleaning hours. Register parking to track when to move.

Primary action: **Add your first street**. Explain that hours come from street signs, not the map.
Do not request notification or location permission merely because the app opened.

### Saved streets, not parked

Show **Not currently parked**, followed by the primary **Park where I am** action and a brief manual
alternative: "Or choose a saved street below." Do not imply that background detection will start
later.

### Active parking

The first screenful should prioritize current location, time remaining, and reminder status. This is
a content-order sketch, not a fixed-height layout:

```text
Parking                                                   Settings

Parked on Ynglingagatan                                End parking
2 hr 15 min remaining
Move before Tue 08:00
Deadline warning scheduled: Tue 07:00               Reminder settings

Planned move / suggested move
Destination and time
Relevant action / View options

Saved streets
All streets   Daytime cleaning
Street name                                              Favourite
Can stay 3 days 5 hr                                  I've parked here
Next cleaning: Fri 08:00

                                                    [+ Add street]
```

- Prefer "Can stay …" to the ambiguous "Free for …", with "Based on saved cleaning hours" nearby.
- Use weekday and time, plus date when needed to remove ambiguity. Retain 24-hour time for this
  beta. Display times in the same local time zone used by the scheduler.
- Show both the relative countdown and exact deadline; neither replaces the other.
- Before a deadline less than a minute away, say "Less than a minute remaining", not "0m".
- If there is no cleaning deadline, say "No cleaning deadline in saved hours", not "Park anytime".
- A compact selected-plan section follows current status. Its details must not dominate the
  countdown.
- Keep the current street distinguishable in the list and replace its registration action with
  **Parked here**. Tapping it must not restart the session.
- Make row interaction discoverable: an edit affordance with a labeled action opens the existing
  editor. Keep favourite and parking actions separate; do not make tapping ordinary row text
  register parking.

### Urgency states

For this beta, "soon" means two hours or less before the deadline. Keep the threshold in one place.

| State                 | Presentation                                                |
| --------------------- | ----------------------------------------------------------- |
| More than two hours   | Neutral card, blue accent, ordinary countdown               |
| Within two hours      | Amber status: "Move soon", prominent countdown and deadline |
| Deadline reached/past | Error status: "Move now — cleaning deadline passed"         |
| No deadline           | Neutral explanation; no fabricated countdown                |
| Loading               | Stable loading state, not "No streets yet" or "Not parked"  |

A missing or invalid plan must never obscure the current parking deadline. Urgency updates without
navigation or manual refresh, including while returning to the app from the background.

### Street list

- Preserve current ordering. Add a small "Longest stay first" explanation so it is not mysterious.
- Use a clear name line, duration/status line, and quieter next-cleaning line.
- Keep favourites as a small trailing action, not the dominant symbol in the row.
- Keep daytime-cleaning metadata compact and secondary, with its actual weekday/time information.
- Use wrapping filter chips. The explanation must state that other cleaning windows still apply.
- Cleaning-now rows show when cleaning ends and why registration is unavailable.
- A filtered-empty state offers **Show all streets** rather than looking like all data disappeared.

## 6. Reminder trust and Settings

### Show operational state, not only preferences

Home must distinguish enabled settings from an actual upcoming deadline warning. Use the same
scheduling rules and reconciled reminder state as the coordinator, not a second approximation based
on offsets. Exclude delivered, cancelled, stale, and skipped reminders. Do not create additional
alarms to support this display.

Show the next scheduled deadline warning, or an honest explanation:

- **Deadline warnings off** → open the relevant settings section.
- **No warning times selected** → choose warning times.
- **No upcoming deadline warnings** → all configured times have passed; offer settings to add one.
- **Notifications blocked** → open the relevant Android notification settings.
- **Precise reminders unavailable** → explain possible delay and offer the Android permission
  action.
- **Updating reminder status…** → do not prematurely claim a warning is scheduled.

Display delivery blockers alongside scheduling state when both apply. Check app-level and relevant
channel-level blocking separately, so a blocked move channel does not falsely describe deadline
alerts. Refresh system capability information after returning from Android Settings.

Use "scheduled" rather than "guaranteed". An allowed notification permission is not proof of
delivery. If a pending warning's scheduled time has passed, show it as due/delayed, not as a future
warning. Apply the same honest status pattern to selected-plan reminders, including at-time
reminders, but keep that information in the plan section rather than merging it with
deadline-warning status.

### Defaults and permissions

- Preserve existing reminder selections, including intentionally empty selections and disabled
  groups.
- Do not silently change existing or implicit default offsets in this beta.
- Add one-hour and ten-minute deadline presets alongside the existing choices.
- When all selected offsets have passed, explain that adding an already-past offset will not help.
- Ask for notification permission in context after parking registration or explicitly enabling
  warnings. Explain the reason first; denial must not prevent parking registration or cause repeated
  prompts.
- Request precise-alarm access through an explicit repair action, not automatically on every launch.
- Keep location permission tied to a GPS action; manual registration requires no location
  permission.

### Settings layout

Order the screen around the core promise:

1. **Reminder delivery**: Android blockers and repair actions; test-reminder action here.
2. **Parking deadline warnings**: labeled switch, concise explanation, presets, custom offsets.
3. **Planned move reminders**: the same control pattern, with the distinct purpose explained.
4. **Preferred re-parking times**: wrapping choices and the effect of selecting none.

Use consistent section spacing rather than a wall of dividers and explanatory paragraphs. Keep
advanced explanation secondary but accessible. The whole labeled switch row should be a coherent
toggle target. A test reminder can say it was scheduled/requested, not that it was delivered without
evidence.

## 7. Parking, planning, and destructive actions

### Targeted confirmations

- First registration with no active parking remains one tap.
- Registering a different street from the saved-street list while parked asks: "Change parked
  location from X to Y?" Explain that the current session ends, its plan clears, and warnings are
  recalculated. Confirm with an explicit **I've parked on Y** action; cancelling changes nothing.
- Ending parking asks: "End parking on X?" Explain that this clears the plan and both reminder
  groups for this session. Confirm with **End parking**; cancelling changes nothing.
- Deleting a street names the entry and explains the loss of its saved hours. If it is the current
  parked street or selected destination, explain that consequence before confirmation.
- Recheck the current session, target, and availability when confirming. A stale dialog must not
  replace a newer session or register an unavailable street.
- Guard against repeated taps while mutations are running. Feedback must describe the completed
  result.

Do not add confirmations to favourite toggles or initial parking. Keep existing notification actions
and their stale-session safeguards; the new confirmation policy above applies to in-app actions.

Deleting the current parked street must explicitly end that parking session and clear its
plan/alarms, not leave an "Unknown street" session. Deleting only a planned destination keeps
current parking and its deadline warnings, and leaves the plan visibly needing attention as it does
today.

### Planned moves and recommendations

- Preserve the distinction between **Plan this move** and **I've parked here** everywhere.
- Keep the selected destination/time visible on home, with secondary change/cancel actions.
- Reminder details can expand; the selected destination, time, and any problem must not be hidden.
- A missed plan says its time passed. An invalid plan explains the problem and cancelled move
  reminders.
- Arrival is enabled only when the destination is currently available. Explain a disabled arrival
  action. The selected plan's **I've parked here** action is already explicit; do not add a second
  confirmation, but retain session/plan identity and availability checks at execution time.
- Cancelling a plan remains direct and gives clear feedback; it never ends parking or disables
  deadline warnings. Do not introduce extra confirmations for every planning interaction.
- Keep up to three destination cards on the options screen, with one clear selected treatment and
  consistent placement of destination, move time, expected stay, and action.
- Show the saved-schedule/not-live-spaces caveat near the choices, not only at the bottom of a long
  list.

When preferred times are empty, say **No preferred re-parking times selected** and offer **Choose
times** linked to that Settings section. When configured times have no suitable opportunity left,
say that instead. Do not claim an opportunity "remains" or was missed when preferences were never
configured.

## 8. Add/edit street and failure recovery

### Form hierarchy and schedule accuracy

Use distinct sections: **Street**, **Cleaning hours**, then the save action. Keep existing map
controls functional without redesigning their behavior. Preserve the editable custom name used for
street parts.

- New rules must not silently acquire Tuesday 08:00–14:00 as accepted data.
- Start new rules with clearly unselected day/time fields. Picker seed values are not saved input
  until the user chooses them. Existing saved rules load normally.
- Add concise guidance: "Enter the weekly cleaning restriction from the street sign."
- Show all seven weekdays using wrapping or a suitable adaptive layout, not a hidden horizontal
  tail.
- Show a plain-language summary of the entered window before saving.
- Overnight restrictions explicitly end the next day. Equal start/end times mean a 24-hour
  restriction under the existing engine; say so clearly rather than leaving the meaning implicit.
- Require an **I've checked these cleaning hours** acknowledgment for a new street or changed
  schedule. Reset it if the schedule changes again. Do not require it for a name-only edit of an
  existing street.
- Keep acknowledgment as form state; do not add a persisted verification schema or relabel old data.
- Explain incomplete fields inline. Do not leave a disabled Save button as the only clue.
- Back navigation with unsaved edits asks whether to discard them. Preserve form state during
  requests.

### Feedback and recovery

- Search distinguishes idle/short query, searching, results, no matches, and network/service
  failure.
- Do not convert network failure into "No streets found". Offer retry without losing the entered
  form.
- Ignore stale search responses when the query changes. A failed request must not disable later
  searches.
- **Use current location** shows progress, handles permission denial and no fix, and offers manual
  search.
- **Park where I am** retains progress and a clear manual alternative when there is no reliable
  match.
- Use inline persistent errors for blocked tasks. Use snackbars for transient completed-action
  feedback, rather than relying on disappearing toasts for important problems.
- Separate initial loading from empty results in home and settings-dependent views.
- Keep the existing map picker and geometry-service behavior out of this work package.

## 9. Accessibility and responsive behavior

- Give switches an associated label and state in semantics; make each labeled row one toggle target.
- Ensure nested edit, favourite, and registration actions have distinct focus targets and
  descriptions.
- Use meaningful reading order: current street, countdown, deadline, reminder state, plan, saved
  streets.
- Give section headings appropriate semantics and provide readable spoken versions of abbreviated
  times.
- Do not announce every countdown tick as an interrupting accessibility alert.
- Make important state changes discoverable without relying on color, sound, or short-lived
  messages.
- Test narrow phones, long names, landscape, keyboard visibility, display scaling, and 200% font
  size.
- Stack time controls, location buttons, and change/cancel buttons when they do not fit comfortably.
- Verify focus behavior after dialogs, permission changes, failed requests, and returning from
  Settings.

## 10. Implementation order and code touchpoints

These are work packages within one final beta commit, not separate releases. Keep changes limited to
this experiment and avoid unrelated architecture cleanup. Paths below are relative to
`app/src/main/java/dev/lastplace/app/`.

1. **Baseline and theme**
   - Capture the current home, editor, options, and settings screens before changing them.
   - Implement shared visual tokens in `ui/theme/Theme.kt` and reusable state presentation as
     needed.
2. **Remove automatic parking**
   - Update `data/settings/SettingsRepository.kt`, `ui/settings/SettingsViewModel.kt`, and
     `ui/settings/SettingsScreen.kt`; check `data/Repositories.kt` and parking-source call sites.
3. **Reminder readiness**
   - Connect `reminders/ReminderCoordinator.kt` and scheduler capability checks to home/settings
     state.
   - Update `ui/streets/StreetListViewModel.kt`, reminder preference/delivery components, and
     `MainActivity.kt` permission timing. Avoid duplicate scheduling logic.
4. **Home and planning presentation**
   - Update `ui/streets/StreetListScreen.kt`, `StreetRows.kt`, `PlannedMoveDetails.kt`, and
     `ReparkingOptionsScreen.kt`. Preserve the planner's ordering and selected-plan semantics.
5. **Safe mutations and explicit schedule input**
   - Add targeted dialogs, stale-state checks, and current-street deletion handling through the
     existing parking/reminder services. Update add-street screen/view-model form behavior and
     validation.
6. **Contextual setup and recovery**
   - Add settings-section destinations through `ui/nav/ParkingNavHost.kt` as needed.
   - Expose search failures from `osm/OsmService.kt` without changing geometry capture; update
     GPS-action states through existing location providers rather than replacing location
     infrastructure.
7. **Validation and release preparation**
   - Add/update tests, capture the redesigned state matrix, and update README claims to match
     behavior.
   - Mark beta tags as prereleases in `.github/workflows/release.yml`; leave normal releases
     unchanged.

## 11. Acceptance checks

### Automated coverage

Extend the existing unit tests for settings, street rows/view-models, parking, and reminder
coordination. Add Compose UI test dependencies and focused instrumented tests where needed; do not
build a new visual regression platform just for this beta.

- [ ] Old settings and session records load without automatic-parking feature code or data rewrites.
- [ ] Registering less than 12 hours before cleaning with current defaults shows no upcoming
      warning.
- [ ] Future, delivered, skipped, cancelled, and stale warnings produce the correct readiness state.
- [ ] Disabled categories, empty offsets, blocked channels, and precise-alarm limits remain
      distinct.
- [ ] Changing a plan cannot change current parking or cancel unrelated deadline warnings.
- [ ] Cancelling each confirmation leaves state unchanged; confirming applies only to the intended
      state.
- [ ] Repeated taps and stale dialogs cannot replace a newer session or create duplicate reminders.
- [ ] Deleting the parked street clears its session/alarms; deleting a destination preserves the
      deadline.
- [ ] New/changed schedules require deliberate input and acknowledgment; name-only edits preserve
      rules.
- [ ] Overnight and equal-time summaries agree with the existing schedule engine.
- [ ] `Ynglingagatan` and `Ynglingagatan vändplan` remain independent, with names and schedules
      intact.
- [ ] Empty/loading/search failure/no-results states differ; search works again after a failed
      request.
- [ ] Countdown presentation changes correctly at two hours, one minute, and the deadline.
- [ ] First-use actions, labeled switches, confirmation dialogs, and key home states have UI
      coverage.

Run the local build checks in the project's Android development environment:

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug
./gradlew connectedDebugAndroidTest
```

The instrumented command requires a device/emulator. Record an unavailable check as not run, never
as passed. The signed release build uses the existing CI signing setup; do not commit credentials.

### Device and visual acceptance

Capture before/after screenshots with the same representative data and window size. Use test
fixtures or a test device for destructive, overdue, and artificial-time scenarios rather than
risking real data.

- [ ] Fresh install: obvious first step; no immediate notification permission popup.
- [ ] Saved streets without parking: GPS and manual registration are both discoverable.
- [ ] Active parking: location, deadline, and warning state are obvious in a quick glance.
- [ ] Normal, imminent, overdue, and no-deadline presentations remain visually distinct and
      readable.
- [ ] No upcoming warning and blocked delivery remain visible without opening Settings.
- [ ] A selected, missed, or invalid plan cannot displace or hide the deadline.
- [ ] All-streets, daytime-only, filtered-empty, cleaning-now, and long-name rows remain readable.
- [ ] New street, editing, incomplete hours, overnight hours, and unsaved-exit flows work clearly.
- [ ] Offline search, denied location, and no GPS fix have understandable recovery paths.
- [ ] Settings return refreshes permission status; test reminders are checked on an actual device.
- [ ] Light/dark, narrow portrait, landscape, large display size, and 200% text have no lost
      controls.
- [ ] TalkBack announces labeled controls and state accurately without noisy countdown
      announcements.
- [ ] Tap targets, contrast, keyboard insets, and system navigation insets meet the visual
      requirements.

Device testing must include notification behavior while backgrounded and after reboot, using the
existing restoration safeguards. Do not infer delivery from a scheduled-state label or a passing
unit test.

## 12. Single-commit release, trial, and rollback

### Build and release

1. Keep this plan uncommitted separately; include it with all beta changes in one coherent commit.
2. Review the complete diff against the baseline. Confirm there are no migrations, unrelated
   changes, mapping features, automatic-parking remnants, or accidental edits to existing user
   defaults.
3. Run the automated and device checks above. Document any outstanding limitations in the release
   notes.
4. Make the single beta commit, for example `Try the parking UI and reminder-trust beta`.
5. After explicit approval to publish, push that commit and a new unused beta tag such as
   `v<NEXT_VERSION>-beta.1`, replacing the placeholder with the chosen release version.
6. The current workflow matches all `v*` tags but does not yet mark prereleases. Add conditional
   prerelease publishing for `-beta.` tags as part of the beta commit; verify the release is marked
   beta.
7. Build with the existing release signing key and application ID. The CI version code must be
   higher than the installed version. A debug-signed APK cannot update an existing release-signed
   installation.
8. Verify Obtainium's prerelease setting, or manually install the signed beta asset without
   uninstalling.
9. Confirm the two Ynglingagatan entries, all preferences, current parking, and any plan survive
   updating.

A prerelease flag is not access control: the GitHub release remains public in a public repository.
This is a personal trial, not a declaration that the design or parking information is
production-validated.

### Personal trial

Use the beta through at least one real parking/cleaning cycle if practical. Keep feedback outside
this plan or in release notes so deleting the plan does not lose the decision rationale.
Specifically assess:

- Can I answer "where, until when, and will I be warned?" within a few seconds?
- Is the new appearance actually nicer on my phone, including outdoors and in dark mode?
- Is the reminder explanation useful, or too verbose on every visit?
- Are confirmations worth their friction? Is the visible End parking action still convenient?
- Is planning clearly secondary without becoming difficult to discover or change?
- Does the custom-name workaround remain practical while mapping is deferred?

If accepted, retain the implementation and retire this temporary plan after recording the outcome.
Any follow-up design or Stockholm catalogue work gets its own scope, not an expanding beta
checklist.

### Revert if the trial is not worthwhile

1. Revert the single beta commit with `git revert <beta-commit>`; do not reset shared history.
2. Because `BETA.md` was introduced in that commit, the revert removes the plan as requested.
3. Rebuild the reverted code and run its applicable tests. Verify existing beta-used data still
   loads.
4. Publish a newly versioned, signed recovery release through the reverted release workflow. Its
   Android version code must exceed the beta's version code, even though its application behavior is
   older.
5. Install that update over the beta and verify streets, preferences, parking, plans, and reminders.

Do not rely on installing an old lower-version APK, uninstalling the app, or clearing storage to
roll back. The absence of schema and destructive preference changes is what makes this experiment
reversible.
