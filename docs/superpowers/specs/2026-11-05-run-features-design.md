# Pocket-proof runs, goal bell, notebook, offline map, lock-screen view, dark redesign

**Date:** 2026-11-05
**Status:** Implemented 2026-11-06 on `feat/run-features` (plan `../plans/2026-11-06-run-features.md`). §6 phone checklist pending on the S24.
**Builds on:** `2026-06-07-local-strava-design.md` and the (uncommitted) accuracy work in
`../plans/2026-06-15-accuracy-improvements.md`.
**Approved look:** `2026-11-05-run-features-mockup.html` (open in a browser; it is the visual
reference for every screen below).

## 1. Why

The owner runs with the phone in a pocket on a Samsung Galaxy S24. Today:

- With the screen off, the phone locked, and the run "a couple of minutes behind" on unlock:
  nothing was recorded while locked.
- There is no way to be told "you've hit 5 km" or "30 minutes" without looking.
- Runs timed another way (for example a stopwatch at the track) can't be kept anywhere.
- Checking stats mid-run means unlocking the phone.
- The UI is stock Material with default colours. It works but is hard to read at a glance.

Success: put the phone in a pocket, run, and get rung at the goal. Press the power button and
see the run without unlocking. Afterwards the whole run is there. Typed-in runs live in the same
history.

## 2. Goals, non-goals, anti-goals

**Goals**
1. Recording keeps going with the screen off and the phone locked, on Samsung.
2. Optional goal bell: one distance or time goal per run, rings and vibrates once when reached.
3. Notebook: type in a run (when, distance, time, note) and see it in history.
4. Offline: everything works with no internet. Map pieces already seen stay available.
5. Lock-screen view: during a run, turning the screen on shows the run screen without unlocking.
   Anything else still needs an unlock.
6. Redesign per the mockup, always dark.

**Non-goals** (don't build)
- Performance work. Nothing measured is slow. Measure first if it ever comes up.
- Downloading map areas ahead of time.
- Light theme or a theme switch. The owner chose always dark.
- Editing a logged run (delete and re-log instead).
- Repeating bells ("every 1 km"), several goals per run, spoken pace announcements.
- Imperial units.
- Recording on a plane. The 43 km/h plausibility gate rejects it on purpose.

**Anti-goals** (failure even if it "works")
- Losing any existing run when the database is upgraded.
- A pocket touch ending a run.
- Any change to distance or elevation accuracy. Do **not** touch the accuracy gates, filters or
  `RunAccumulator` maths. The fused-location fix was field-validated to about 2% and is the
  reason distances are right.
- The app showing over the lock screen when no run is active, or letting someone reach other
  apps or settings without unlocking.
- Anything needing the internet other than fetching map pieces not seen before.

## 3. Constraints

- Kotlin, Jetpack Compose, Material 3, Room, osmdroid, Play Services location (already present).
  minSdk 26, targetSdk 35. Personal sideloaded debug APK, so Play Store policy is not a concern.
- **No new libraries.** Fonts are bundled as resource files, not a library (see §4.6).
- The working tree has a lot of uncommitted, intentional accuracy work. Keep it. Don't reset,
  stash-and-drop, or rewrite it.
- Tests: pure logic gets JUnit tests next to existing ones under `app/src/test/`. Never edit an
  existing test to make it pass. Verify with `./gradlew assembleDebug testDebugUnitTest`.

## 4. What gets built

### 4.1 Recording with the screen off (done, 2026-11-05)

Already in the working tree, building, all 39 unit tests passing. Not committed yet. It consists of a `PARTIAL_WAKE_LOCK` (12 h safety timeout) held from Start to Stop in
`TrackingService`. Also a one-time system request to exempt the app from battery optimization
when Start is tapped, and a one-line Samsung tip ("add this app to Settings › Battery ›
Background usage limits › Never sleeping apps") shown only while the app isn't exempt and no run
is going. The redesign must keep that tip on the Ready screen (mockup screen 1) in its quiet
style.

### 4.2 Goal bell

- **Ready screen** (mockup 1): "Ring me at" with Off / Distance / Time, plus a −/+ stepper.
  Distance goes in 0.5 km steps (0.5 to 100 km). Time goes in 5-minute steps (5 min to 10 h).
  The last choice is remembered between runs. *(My call.)*
- The goal travels to `TrackingService` with the START intent. The service checks it each time
  it publishes stats. **Distance** is compared to recorded distance. **Time** is compared to
  moving time, the same number shown on screen, so it doesn't advance while paused or
  auto-paused. It fires once per run, and reaching the goal also **ends the run**: it is saved
  like a normal Stop, and the service stays up until the bell finishes (owner request,
  2026-11-07).
- **The ring:** the phone's default alarm tone (`RingtoneManager.TYPE_ALARM`) with alarm audio
  attributes, so it is heard even when the phone is on silent. It plays for about 4 seconds,
  together with a vibration pattern. No sound files are bundled. *(My call. The point is hearing
  it from a pocket.)* It takes permanent audio focus, so music from other apps stops and does
  not resume (owner request, 2026-11-07).
- **GPS label** (top right of mockup 2): reuse the existing `locationLost` state. It shows
  "GPS on" normally and "GPS lost" in amber when lost. No new signal-strength value from the
  service. *(My call. Keeps the tracking path untouched.)*
- **Running screen** (mockup 2): an amber bar fills toward the goal, with a line under it
  ("180 m until the 5 km bell" or "4 min until the 30 min bell"). After it rings, the line reads
  "5 km bell rang" and the bar stays full. With no goal set, there is no bar and no line.

### 4.3 Notebook (logged runs)

- **Entry:** a "Log a run" button next to "Start run" on History (mockup 3), opening the form
  (mockup 5).
- **Fields:** *When* (date and time pickers; defaults to "Now", which means the run just ended,
  so it is saved as starting *time* before the moment of saving. A picked date and time is the
  start). *Distance* in km (more than 0,
  accept `.` or `,` as the decimal point). *Time* in h/m/s (total more than 0). *Note*
  (optional). Pace is shown live once distance and time are valid. "Save run" is disabled until
  they are.
- **Storage:** Room database version 1 → 2 with a real `Migration` that adds `note TEXT`
  (nullable) and `logged INTEGER NOT NULL DEFAULT 0` to `activities`. Never use destructive
  fallback. A logged run is an `Activity` row: `startedAt` = When, `endedAt` = When + time,
  `movingTimeMs` = time, `distanceMeters` = distance, `elevationGainMeters` = 0, `logged` = 1,
  and no track points. It has `endedAt` set, so crash recovery (`endedAt IS NULL`) never
  touches it.
- **Entity must match the migration:** declare `@ColumnInfo(defaultValue = "0") val logged:
  Boolean = false` and `val note: String? = null`. Without the `defaultValue`, Room's
  post-upgrade schema check fails and the app crashes on every launch.
- **History:** logged runs sit in date order with the recorded ones. They carry a "Logged" tag
  and show their note under the stats.
- **Run details for a logged run:** no map. Shows distance, time, pace and the note. Climb,
  highest and total time are hidden. Delete works the same as for recorded runs.

### 4.4 Offline

- Recording already needs no internet. Nothing to change there.
- Move the osmdroid tile cache from `cacheDir` to `filesDir`, so Android doesn't wipe the map
  pieces already seen. Tiles in the old cache are simply re-fetched. Keep osmdroid's default
  size cap.
- Missing tiles (offline, never seen) show as plain dark background, not osmdroid's grey
  loading grid. The route line and markers always draw.

### 4.5 Lock-screen view

- While a run is Active or Paused **and the Record screen is showing**, the activity shows over
  the lock screen (`setShowWhenLocked(true)` on API 27+, `FLAG_SHOW_WHEN_LOCKED` on 26). It is
  switched off as soon as the run ends or the user leaves the Record screen. *(My call: limited
  to the Record screen so run history isn't readable over the lock screen.)*
- Do not dismiss the lock screen and do not turn the screen on. Leaving the app (home, recents,
  or a notification for another app) hits the normal lock screen.
- Known limit, accepted: this only works if the app was on screen when the phone locked.
  Otherwise the normal lock screen shows, with the run notification's Pause/Stop as today.
- **Hold to stop:** the in-app Stop button needs a 1.2-second press, filling amber while held,
  and letting go early cancels (mockup 2 has a working demo). Pause stays a normal tap: an
  accidental pause is harmless and easy to see, and Samsung's accidental-touch protection
  covers most pocket presses. *(My call.)* The notification's Stop action is unchanged.

### 4.6 Look: always dark

- One dark colour scheme for the whole app, ignoring the phone's theme setting. System bars
  are dark too.
- **Tokens** (from the mockup): background `#0B1626`, surface `#14243B`, lines `#243853`,
  text `#F2F5F9`, secondary text `#8C9DB5`, and one accent, amber `#FFB000`. Text on amber is
  `#0B1626`.
- **Type:** Barlow Condensed (500/600/700) for numbers and titles, Barlow (400/500/600) for
  everything else. Bundle the TTFs from Google Fonts (SIL Open Font License, which allows
  bundling) in `res/font/`. Numbers use tabular figures (`fontFeatureSettings = "tnum"`) so
  they don't wobble.
- **Screens:** follow the mockup. Ready, Running, History, Run details and Log a run. Labels in
  sentence case, no all-caps. Distance is the hero number on Running and History.
- **Map** (Run details): darken osmdroid tiles with its inverted-colours filter. The route is
  amber, about 4–5 dp wide with a dark casing. The start is a light dot. The end marker is the
  builder's choice, but keep it minimal (no default teardrop pins).
- Back and Delete become icons. Use Material icons if `material-icons-core` is already pulled
  in by Material 3. Otherwise use two small vector drawables. Don't add a library either way.
- The launcher icon and the notification's look are unchanged.

## 5. Decided by the owner

Samsung S24 is the target phone. One goal bell per run, ringing once. The notebook fields are
when / distance / time / note, with pace worked out. Offline means "works with no signal, map
pieces kept". The lock-screen view works like Waze. Always dark with no switch, and the
mockup's look is approved. No performance work.

Added 2026-11-06:
- **Git:** the earlier uncommitted work is committed as a baseline (`b24edec` accuracy work,
  `612adcb` screen-off fix). After that there is one commit per finished feature.
  Commit messages never mention AI: no `Co-Authored-By` or similar trailers.
- **Database upgrade safety:** careful additive migration only, with no test library and no
  emulator. If the upgrade fails, Room rolls it back and the runs stay at version 1, so
  reinstalling the previous APK works. Once the upgrade has succeeded, the previous APK can't
  open the version-2 database. The runs are still in the file, and the fix goes forward, never
  back. Never add any `fallbackToDestructiveMigration*`.

## 6. Checking it on the phone

The S24 is not connected to the build machine, and no emulator is installed. So the plan ends
with a checklist the owner runs on the S24 with the new APK. Failures come back as new work.

1. Old runs are all still in History after installing over the current build.
2. Start a run, lock the phone, walk 5+ minutes, unlock: time and distance kept up.
3. With the phone on silent and music playing, set a short goal (0.5 km or 5 min). It rings
   and vibrates, the music stops, and the run ends and opens in its details.
4. Mid-run, lock the phone and press power: the run screen shows without unlocking. Swiping
   home asks for the unlock.
5. Tapping Stop does nothing. Holding it for about a second stops the run.
6. In airplane mode, open an old run: the route line draws, and seen map areas show.
7. Log a run. It shows in History with its tag and note, and its details have no map.

## 7. Left to the builder

Composable structure and file layout (match the existing `ui/<screen>/` pattern). How the goal
reaches the service (intent extras are fine). The vibration pattern. The end-marker style.
Exact spacing beyond the mockup. Whether "Log a run" is a new route or a dialog (the mockup
shows a full screen).
