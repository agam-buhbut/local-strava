# Run Features Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the goal bell, notebook (logged runs), offline dark map, lock-screen run view, hold-to-stop, and an always-dark redesign to the Local Strava Android app.

**Architecture:** Pure logic (goal maths, formatting, input parsing, logged-run building) lives in framework-free Kotlin and is unit-tested. `TrackingService` stays the only writer of run state and gains the goal check plus a small `GoalBell`. Room goes from version 1 to 2 with an additive migration. The Compose screens are restyled against one dark theme.

**Tech Stack:** Kotlin 2.0.21, Jetpack Compose (BOM 2024.12.01, Material 3 1.3.1, `material-icons-core` 1.7.6 already on the classpath), Room 2.6.1, osmdroid 6.1.20, Play Services location 21.3.0, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-05-run-features-design.md`. The visual reference is `docs/superpowers/specs/2026-11-05-run-features-mockup.html`; open it in a browser.

## Global Constraints

- No new libraries or Gradle dependencies. Fonts are resource files.
- Don't touch `metrics/RunAccumulator.kt`, `metrics/Constants.kt`, `metrics/GeoMath.kt`, or the location and barometer sources. Accuracy is field-validated.
- Keep the screen-off fix from `612adcb`: the wake lock, the one-time battery-optimization ask, and the Samsung tip.
- Room: version 2 with `MIGRATION_1_2`. Never use any `fallbackToDestructiveMigration*`.
- Never edit an existing test file. New tests go in new files under `app/src/test/java/com/localstrava/<package>/`.
- Colours (exact): ink `#0B1626`, surface `#14243B`, line `#243853`, snow `#F2F5F9`, mist `#8C9DB5`, amber `#FFB000`. Text on amber is ink. Amber is the only accent.
- Type: Barlow Condensed (500/600/700) for numbers and titles, Barlow (400/500/600) for everything else. Numbers use `fontFeatureSettings = "tnum"`.
- Copy: sentence case, no all-caps labels, use the mockup's wording verbatim.
- Always dark, ignoring the system theme. No theme switch.
- minSdk 26, targetSdk 35. Match the surrounding code style (comment density, naming).
- Verify with `./gradlew assembleDebug testDebugUnitTest` from the repo root (about 6 minutes, no JAVA_HOME needed). Expect `BUILD SUCCESSFUL`.
- Commits: conventional style (`feat(ui): …`), one per task. **No `Co-Authored-By`, no Claude or AI mention, no trailers.**

## Review Focus

1. A time goal while paused or auto-paused must not fire: it compares against *moving* time. Pinned in Task 2 (`timeUsesMovingTimeNotDistance`).
2. The goal fires exactly once, including when the value is hit exactly (`>=`). Pinned in Task 2 (`*ReachedAtExactlyGoal`), and the once-only flag in Task 3, step 2.
3. Log form input with a decimal comma (`8,25`, Samsung keyboard in many locales), blanks, zero, or 60 minutes or seconds. Pinned in Task 6 (`LogRunInputTest`).
4. The lock-screen flag must clear the moment the run ends or the Record screen is left. Otherwise the app shows over the lock screen for good. Pinned in Task 4, step 3, by keying the effect on `isActive` with an `onDispose` that clears it, plus checklist item 4.
5. Offline with no cached tiles: the map must show plain dark background and the route, not a grey grid. Pinned in Task 7, step 2, plus checklist item 6.

---

### Task 1: Always-dark theme and bundled fonts

**Files:**
- Create: `app/src/main/res/font/barlow_regular.ttf`, `barlow_medium.ttf`, `barlow_semibold.ttf`, `barlow_condensed_medium.ttf`, `barlow_condensed_semibold.ttf`, `barlow_condensed_bold.ttf`
- Create: `app/src/main/java/com/localstrava/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/localstrava/MainActivity.kt`, `app/src/main/res/values/colors.xml`, `app/src/main/res/values/themes.xml`

**Interfaces:**
- Produces: `object RunColors { val Ink; val Surface; val Line; val Snow; val Mist; val Amber }` (all `Color`), `val Barlow: FontFamily`, `val BarlowCondensed: FontFamily`, `val Numbers: TextStyle` (BarlowCondensed + `"tnum"`, no size; callers `.copy(fontSize = …, fontWeight = …)`), `@Composable fun LocalStravaTheme(content: @Composable () -> Unit)`.

- [ ] **Step 1: Download the six TTFs** from `https://raw.githubusercontent.com/google/fonts/main/ofl/barlow/Barlow-{Regular,Medium,SemiBold}.ttf` and `…/ofl/barlowcondensed/BarlowCondensed-{Medium,SemiBold,Bold}.ttf` into `res/font/` under the snake_case names above. Check each with `file`: expect `TrueType Font data`.
- [ ] **Step 2: Write `Theme.kt`.** `darkColorScheme(primary = Amber, onPrimary = Ink, background = Ink, onBackground = Snow, surface = Ink, onSurface = Snow, surfaceVariant = Surface, onSurfaceVariant = Mist, surfaceContainer = Surface, outline = Line, outlineVariant = Line)`. `Typography` is Material's default with `fontFamily = Barlow` on every style, and the display and headline styles use `BarlowCondensed`.
- [ ] **Step 3: Wire it into `MainActivity`.** Call `enableEdgeToEdge(SystemBarStyle.dark(Ink.toArgb()), SystemBarStyle.dark(Ink.toArgb()))`, then `LocalStravaTheme { Surface(Modifier.fillMaxSize(), color = RunColors.Ink) { AppNav() } }`. Drop the `isSystemInDarkTheme` branch. Add `<color name="ink">#0B1626</color>` and set `android:windowBackground` to `@color/ink` in `Theme.LocalStrava`, so there's no white flash on launch.
- [ ] **Step 4: Verify.** Run `./gradlew assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`, 39 tests.
- [ ] **Step 5: Commit** `feat(ui): always-dark theme with bundled Barlow fonts`

### Task 2: Goal model and goal and number formatting

**Files:**
- Create: `app/src/main/java/com/localstrava/metrics/Goal.kt`
- Modify: `app/src/main/java/com/localstrava/format/Format.kt` (add functions only, keep existing ones unchanged)
- Test (new): `app/src/test/java/com/localstrava/metrics/GoalTest.kt`, `app/src/test/java/com/localstrava/format/GoalFormatTest.kt`

**Interfaces:**
- Produces:
  - `sealed interface Goal { data class Distance(val meters: Double) : Goal; data class Time(val millis: Long) : Goal }`
  - `fun Goal.isReached(distanceMeters: Double, movingTimeMs: Long): Boolean` (`>=`)
  - `fun Goal.progress(distanceMeters: Double, movingTimeMs: Long): Float` (0f to 1f, clamped)
  - `fun Goal.Distance.stepped(direction: Int): Goal.Distance` (±500 m, clamped 500 m to 100 000 m)
  - `fun Goal.Time.stepped(direction: Int): Goal.Time` (±5 min, clamped 5 min to 600 min)
  - `Format.km(meters: Double): String` ("0.85"), `Format.paceShort(secPerKm: Double?): String` ("5:41" or "--"), `Format.goal(goal: Goal): String`, `Format.goalStatus(goal: Goal, distanceMeters: Double, movingTimeMs: Long, reached: Boolean): String`

- [ ] **Step 1: Write the failing tests**

```kotlin
class GoalTest {
    @Test fun distanceReachedAtExactlyGoal() = assertTrue(Goal.Distance(5000.0).isReached(5000.0, 0))
    @Test fun distanceNotReachedJustShort() = assertFalse(Goal.Distance(5000.0).isReached(4999.9, 99_999_999))
    @Test fun timeUsesMovingTimeNotDistance() = assertFalse(Goal.Time(1_800_000).isReached(50_000.0, 1_799_999))
    @Test fun timeReachedAtExactlyGoal() = assertTrue(Goal.Time(1_800_000).isReached(0.0, 1_800_000))
    @Test fun progressHalfway() = assertEquals(0.5f, Goal.Time(600_000).progress(0.0, 300_000), 1e-6f)
    @Test fun progressClampsToOne() = assertEquals(1f, Goal.Distance(1000.0).progress(1500.0, 0), 0f)
    @Test fun distanceStepsByHalfKm() = assertEquals(Goal.Distance(5500.0), Goal.Distance(5000.0).stepped(+1))
    @Test fun distanceClampsAtMin() = assertEquals(Goal.Distance(500.0), Goal.Distance(500.0).stepped(-1))
    @Test fun distanceClampsAtMax() = assertEquals(Goal.Distance(100_000.0), Goal.Distance(100_000.0).stepped(+1))
    @Test fun timeStepsByFiveMin() = assertEquals(Goal.Time(2_100_000), Goal.Time(1_800_000).stepped(+1))
    @Test fun timeClampsAtMin() = assertEquals(Goal.Time(300_000), Goal.Time(300_000).stepped(-1))
    @Test fun timeClampsAtMax() = assertEquals(Goal.Time(36_000_000), Goal.Time(36_000_000).stepped(+1))
}

class GoalFormatTest {
    @Test fun kmAlwaysTwoDecimals() = assertEquals("0.85", Format.km(850.0))
    @Test fun paceShortHasNoUnit() = assertEquals("5:30", Format.paceShort(330.0))
    @Test fun paceShortNullDashes() = assertEquals("--", Format.paceShort(null))
    @Test fun goalWholeKm() = assertEquals("5 km", Format.goal(Goal.Distance(5000.0)))
    @Test fun goalHalfKm() = assertEquals("5.5 km", Format.goal(Goal.Distance(5500.0)))
    @Test fun goalMinutes() = assertEquals("30 min", Format.goal(Goal.Time(1_800_000)))
    @Test fun goalWholeHour() = assertEquals("1 h", Format.goal(Goal.Time(3_600_000)))
    @Test fun goalHourAndMinutes() = assertEquals("1 h 30 min", Format.goal(Goal.Time(5_400_000)))
    @Test fun statusMetresLeft() =
        assertEquals("180 m until the 5 km bell", Format.goalStatus(Goal.Distance(5000.0), 4820.0, 0, reached = false))
    @Test fun statusKmLeft() =
        assertEquals("1.20 km until the 5 km bell", Format.goalStatus(Goal.Distance(5000.0), 3800.0, 0, reached = false))
    @Test fun statusMinutesRoundUp() = // 200 s left -> 4 min
        assertEquals("4 min until the 30 min bell", Format.goalStatus(Goal.Time(1_800_000), 0.0, 1_600_000, reached = false))
    @Test fun statusUnderAMinuteInSeconds() =
        assertEquals("45 s until the 30 min bell", Format.goalStatus(Goal.Time(1_800_000), 0.0, 1_755_000, reached = false))
    @Test fun statusAfterRing() =
        assertEquals("5 km bell rang", Format.goalStatus(Goal.Distance(5000.0), 5003.0, 0, reached = true))
}
```

- [ ] **Step 2: Run them and confirm they fail.** `./gradlew testDebugUnitTest --tests '*Goal*'` should fail with unresolved references.
- [ ] **Step 3: Implement `Goal.kt` and the four `Format` functions.** Remaining distance reuses `Format.distance`. Remaining time is `ceil` minutes when 60 s or more is left, otherwise whole seconds. `paceShort` shares its maths with `pace` (have `pace` call it and append ` /km`). Use `Locale.US` like the rest of `Format`.
- [ ] **Step 4: Run the tests.** `./gradlew testDebugUnitTest` should pass: 39 existing tests plus the new ones.
- [ ] **Step 5: Commit** `feat(metrics): goal model and goal/number formatting`

### Task 3: Goal bell in the tracking service

**Files:**
- Create: `app/src/main/java/com/localstrava/tracking/GoalBell.kt`
- Modify: `app/src/main/java/com/localstrava/tracking/TrackingService.kt`, `app/src/main/java/com/localstrava/tracking/TrackingUiState.kt`, `app/src/main/AndroidManifest.xml` (add `android.permission.VIBRATE`)

**Interfaces:**
- Consumes: `Goal`, `Goal.isReached` (Task 2).
- Produces: `TrackingUiState.goal: Goal? = null`, `TrackingUiState.goalReached: Boolean = false`, `TrackingService.start(context: Context, goal: Goal?)` (replaces `start(context)`), `class GoalBell(context: Context) { fun ring(); fun release() }`.

- [ ] **Step 1: Write `GoalBell`.** It plays `RingtoneManager.getDefaultUri(TYPE_ALARM)`, falling back to `TYPE_NOTIFICATION` when null. Use `AudioAttributes` `USAGE_ALARM` / `CONTENT_TYPE_SONIFICATION`, so it's heard on silent. Stop it after 4 000 ms through a main-Looper `Handler`. Vibrate the waveform `[0, 600, 300, 600, 300, 600]` (no repeat), using `VibratorManager` on API 31+ and `Vibrator` below. `release()` stops any playing tone and removes pending callbacks.
- [ ] **Step 2: Wire the goal into the service.** Send it as START extras: `EXTRA_GOAL_DISTANCE_M` (Double) or `EXTRA_GOAL_TIME_MS` (Long), with neither meaning no goal. `handleStart` reads them into `TrackingUiState(phase = Active, goal = …)`. In `publishStats`, after the state update: if `goal != null && !goalReached && goal.isReached(s.distanceMeters, s.movingTimeMs)`, set `goalReached = true` in state and call `bell.ring()`. Call `bell.release()` in `onDestroy`. Leave everything else in the service as is.
- [ ] **Step 3: Update the one caller.** In `RecordScreen.kt`, each `TrackingService.start(context)` call site temporarily becomes `start(context, null)`. Task 4 passes the real goal.
- [ ] **Step 4: Verify.** Run `./gradlew assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `feat(tracking): ring and vibrate once when the run goal is reached`

### Task 4: Record screen redesign, hold-to-stop, lock-screen view

**Files:**
- Modify: `app/src/main/java/com/localstrava/ui/record/RecordScreen.kt` (keeps permission, battery and back flows; picks Ready or Running content; lock-screen effect)
- Create: `app/src/main/java/com/localstrava/ui/record/ReadyContent.kt` (mockup screen 1)
- Create: `app/src/main/java/com/localstrava/ui/record/RunningContent.kt` (mockup screen 2, including `HoldToStopButton`)

**Interfaces:**
- Consumes: `RunColors`, `Numbers` (Task 1); `Goal`, `stepped`, `progress`, `Format.km`, `Format.paceShort`, `Format.goal`, `Format.goalStatus` (Task 2); `TrackingService.start(context, goal)`, `TrackingUiState.goal` and `goalReached` (Task 3).
- Produces: none used later.

- [ ] **Step 1: Ready content** (shown when not `isActive`). There's no top app bar. Title "Ready to run". Then the label "Ring me at" over a three-way Off / Distance / Time toggle and a −/+ stepper around the big value (`Format.goal`, with the unit in mist). Under it: "Rings and vibrates once, even in your pocket." It is hidden when Off. At the bottom: the existing Samsung tip (same condition as now), then a full-width amber "Start run" button. The location-permission hint keeps its current text and Open settings button, restyled on the surface colour. Remember the choice in the existing `"record"` SharedPreferences: `goal_kind` (`off` / `distance` / `time`), `goal_distance_m` (default 5000.0), and `goal_time_ms` (default 1 800 000). Each kind keeps its own value. Start passes `Goal?` through the existing `startRun()` into `TrackingService.start`.
- [ ] **Step 2: Running content** (shown when `isActive`). Top row: an amber dot with "Recording" ("Paused" when paused) on the left. On the right, "GPS on" in mist, or "GPS lost" in amber when `locationLost`. Then the label "Distance" over `Format.km` at about 128 sp, Numbers 700, with "km" in mist. If there's a goal: a 6 dp amber bar (`progress`) and `Format.goalStatus` under it. Then "Time" over `Format.duration` at about 76 sp. Then three columns "Pace now" / "Average" / "Climb" (`paceShort` ×2, `Format.elevation`). At the bottom, side by side: Pause as an outlined button ("Resume" in amber fill when paused), and `HoldToStopButton`. Leave the dot's pulse out unless it's trivial.
- [ ] **Step 3: Lock-screen view.** In `RecordScreen`, use `DisposableEffect(state.isActive) { activity.setShowOverLock(state.isActive); onDispose { activity.setShowOverLock(false) } }`. Write a private `Activity.setShowOverLock(show: Boolean)`: `setShowWhenLocked(show)` on API 27+, otherwise add or clear `WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED` (deprecated, needed only on API 26: `@Suppress` with a one-line why). Get the activity by unwrapping `LocalContext.current` through `ContextWrapper`. Never call `setTurnScreenOn` or `requestDismissKeyguard`.
- [ ] **Step 4: `HoldToStopButton(onStop: () -> Unit)`.** A surface-coloured 60 dp button labelled "Hold to stop". While pressed, an amber fill animates left to right over 1 200 ms (`Animatable`, linear), and the label switches to ink. Releasing early snaps the fill back with no stop. Reaching full calls `onStop` once. Use `pointerInput` + `detectTapGestures(onPress = { … tryAwaitRelease() })`. For accessibility, add `semantics { onClick(label = "Stop run") { onStop(); true } }`, so TalkBack users can stop with a double tap.
- [ ] **Step 5: Verify.** Run `./gradlew assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit** `feat(ui): dark record screen with goal picker, hold-to-stop and lock-screen view`

### Task 5: Logged runs in the database

**Files:**
- Modify: `app/src/main/java/com/localstrava/data/Activity.kt`, `app/src/main/java/com/localstrava/data/RunDatabase.kt`, `app/src/main/java/com/localstrava/data/ActivityRepository.kt`
- Test (new): `app/src/test/java/com/localstrava/data/LoggedActivityTest.kt`

**Interfaces:**
- Produces: `Activity.logged: Boolean` (`@ColumnInfo(defaultValue = "0")`, default `false`), `Activity.note: String?` (default `null`), `fun loggedActivity(startedAtEpochMs: Long, distanceMeters: Double, durationMs: Long, note: String?): Activity` (top level in `Activity.kt`), `suspend fun ActivityRepository.logRun(startedAtEpochMs: Long, distanceMeters: Double, durationMs: Long, note: String?): Long`, `RunDatabase.MIGRATION_1_2`.

- [ ] **Step 1: Write the failing test**

```kotlin
class LoggedActivityTest {
    private val a = loggedActivity(1_000L, 8000.0, 2_650_000L, "Track, 8 × 400 m")
    @Test fun endsAtStartPlusDuration() = assertEquals(2_651_000L, a.endedAtEpochMs)
    @Test fun movingTimeIsDuration() = assertEquals(2_650_000L, a.movingTimeMs)
    @Test fun markedLoggedWithNoClimb() { assertTrue(a.logged); assertEquals(0.0, a.elevationGainMeters, 0.0) }
    @Test fun newRowHasNoId() = assertEquals(0L, a.id)
    @Test fun noteIsTrimmed() = assertEquals("Track", loggedActivity(0, 1000.0, 1000, "  Track ").note)
    @Test fun blankNoteBecomesNull() = assertNull(loggedActivity(0, 1000.0, 1000, "   ").note)
}
```

- [ ] **Step 2: Run it and confirm it fails.** `./gradlew testDebugUnitTest --tests '*LoggedActivityTest'` should fail with unresolved reference.
- [ ] **Step 3: Implement.** Add the two fields to `Activity`. Write `loggedActivity`. Add `logRun` as `dao.insertActivity(loggedActivity(…))`. In `RunDatabase`, set `version = 2` and `.addMigrations(MIGRATION_1_2)`, with exactly:

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE activities ADD COLUMN logged INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE activities ADD COLUMN note TEXT")
    }
}
```

`finalizeActivity` and `recoverInProgress` need no change: the defaults and `copy` keep both fields right.
- [ ] **Step 4: Verify.** Run `./gradlew assembleDebug testDebugUnitTest`. Expect `BUILD SUCCESSFUL`, and expect Room's KSP step to accept the schema.
- [ ] **Step 5: Commit** `feat(data): logged runs (db v2 with additive migration)`

### Task 6: Log a run screen and History redesign

**Files:**
- Create: `app/src/main/java/com/localstrava/ui/log/LogRunInput.kt`, `app/src/main/java/com/localstrava/ui/log/LogRunScreen.kt`, `app/src/main/java/com/localstrava/ui/log/LogRunViewModel.kt`
- Modify: `app/src/main/java/com/localstrava/ui/history/HistoryScreen.kt`, `app/src/main/java/com/localstrava/ui/nav/AppNav.kt` (add `Routes.LOG = "log"`)
- Test (new): `app/src/test/java/com/localstrava/ui/log/LogRunInputTest.kt`

**Interfaces:**
- Consumes: `logRun` (Task 5), `Format.km` (Task 2), `Format.avgPace`, `Format.duration`, theme (Task 1).
- Produces: `fun parseDistanceMeters(text: String): Double?` (km in, metres out, accepts `.` or `,`, trims, null if blank, garbage, ≤ 0 or > 1000 km), `fun parseDurationMs(hours: String, minutes: String, seconds: String): Long?` (blank counts as 0, hours 0–99, minutes and seconds 0–59, null if any is out of range or the total is 0).

- [ ] **Step 1: Write the failing test**

```kotlin
class LogRunInputTest {
    @Test fun distanceDot() = assertEquals(8250.0, parseDistanceMeters("8.25")!!, 1e-9)
    @Test fun distanceComma() = assertEquals(8250.0, parseDistanceMeters("8,25")!!, 1e-9)
    @Test fun distanceTrims() = assertEquals(5000.0, parseDistanceMeters(" 5 ")!!, 1e-9)
    @Test fun distanceBlank() = assertNull(parseDistanceMeters(""))
    @Test fun distanceZero() = assertNull(parseDistanceMeters("0"))
    @Test fun distanceNegative() = assertNull(parseDistanceMeters("-3"))
    @Test fun distanceGarbage() = assertNull(parseDistanceMeters("abc"))
    @Test fun distanceOver1000Km() = assertNull(parseDistanceMeters("1000.01"))
    @Test fun durationBlankHoursIsZero() = assertEquals(2_650_000L, parseDurationMs("", "44", "10"))
    @Test fun durationWithHours() = assertEquals(4_390_000L, parseDurationMs("1", "13", "10"))
    @Test fun durationAllZero() = assertNull(parseDurationMs("0", "0", "0"))
    @Test fun durationMinutes60() = assertNull(parseDurationMs("0", "60", "0"))
    @Test fun durationSeconds60() = assertNull(parseDurationMs("0", "1", "60"))
    @Test fun durationNegative() = assertNull(parseDurationMs("-1", "0", "0"))
}
```

- [ ] **Step 2: Run it and confirm it fails.** `./gradlew testDebugUnitTest --tests '*LogRunInputTest'` should fail with unresolved reference.
- [ ] **Step 3: Implement `LogRunInput.kt`**, then run step 2's command again and expect it to pass.
- [ ] **Step 4: Build the Log a run screen** (mockup screen 5). The top bar has a Close icon (`Icons.Filled.Close`, pops back) and the title "Log a run". Fields:
  - "When": tapping opens a Material 3 `DatePickerDialog`, then a `TimePicker` in an `AlertDialog`. Defaults to now. Shown as `EEE d MMM, HH:mm`.
  - "Distance": decimal keyboard, with "km" suffix.
  - "Time": three number fields with h / m / s suffixes.
  - Under the time fields: "Pace **x:xx /km**" (`Format.avgPace`). Hidden until both are valid.
  - "Note (optional)", multi-line.
  
  An amber "Save run" button is disabled until distance and time are valid. On tap it calls `LogRunViewModel.save(…)` (IO dispatcher, `repo.logRun`), then navigates back to History. `LogRunViewModel` follows the `AndroidViewModel` + `ActivityRepository(RunDatabase.get(app).runDao())` pattern of `HistoryViewModel`.
- [ ] **Step 5: Redesign History** (mockup screen 3). Replace the FAB and top app bar with the title "Runs".
  - **Rows:** each row has its date (`EEE d MMM, HH:mm`, mist) on top. For logged runs a small snow-on-ink pill reading "Logged" sits beside the date. Below: `Format.km` at about 36 sp with "km" in mist on the left, and `Format.duration` over `Format.avgPace` right-aligned. Then the note, if any. Rows are separated by 1 dp `Line` dividers, not cards.
  - **Bottom:** "Log a run" (outlined) and "Start run" (amber), side by side.
  - **Active run:** the "● Recording… tap to return" banner stays, restyled on the surface colour.
  - **Empty state:** "No runs yet. Start one, or log a run you timed another way."
  - **Nav:** wire `Routes.LOG`.
- [ ] **Step 6: Verify.** Run `./gradlew assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`.
- [ ] **Step 7: Commit** `feat(ui): notebook to log runs, dark history list`

### Task 7: Run details redesign and offline dark map

**Files:**
- Modify: `app/src/main/java/com/localstrava/ui/detail/DetailScreen.kt`, `app/src/main/java/com/localstrava/LocalStravaApp.kt`

**Interfaces:**
- Consumes: `Activity.logged` and `note` (Task 5), `RunColors`, `Numbers` (Task 1), `Format.km` (Task 2).

- [ ] **Step 1: Keep map tiles.** In `LocalStravaApp.configureOsmdroid`, the base dir becomes `File(filesDir, "osmdroid")` instead of `cacheDir`. Leave the size cap and user agent alone.
- [ ] **Step 2: Dark map.** On the `MapView`:
  - `overlayManager.tilesOverlay.setColorFilter(TilesOverlay.INVERTED_COLORS)`.
  - `tilesOverlay.loadingBackgroundColor` and `loadingLineColor` set to ink, so missing tiles are plain dark.
  - **Route:** each segment is drawn twice, an ink casing polyline about 9 dp wide, then an amber one about 4.5 dp wide, with round caps and joins. Keep the existing segment-break splitting.
  - **Start and end markers:** replace the default pins. The start is a small snow dot with an ink ring (`GradientDrawable` oval, anchored at the centre). The end marker is your choice, but keep it minimal.
- [ ] **Step 3: Layout** (mockup screen 4).
  - **Top bar:** a back icon (`Icons.AutoMirrored.Filled.ArrowBack`), the run's date `EEE d MMM, HH:mm` as the title, and a delete icon (`Icons.Filled.Delete`). The existing delete-confirm dialog stays.
  - **Map:** full width, about 270 dp tall.
  - **Grid below it:** "Distance" in full width at about 64 sp. Then a 3-column grid: "Moving time", "Pace" (`avgPace`), "Total time" (`endedAt − startedAt` via `Format.duration`), "Climb", "Highest" (the existing max-altitude calculation).
  - **Logged run:** no map, and no Climb, Highest or Total time. Show Distance, "Time", "Pace", then the note in body text.
- [ ] **Step 4: Verify.** Run `./gradlew assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`.
- [ ] **Step 5: Commit** `feat(ui): dark run details with inverted offline map`

### Task 8: Final check and hand-off

- [ ] **Step 1: Build and test once more.** Run `./gradlew clean assembleDebug testDebugUnitTest` and expect `BUILD SUCCESSFUL`, with all tests passing (39 + new).
- [ ] **Step 2:** Give the owner the APK path (`app/build/outputs/apk/debug/app-debug.apk`) and the spec's §6 checklist (7 items) to run on the S24. Remind them to install it over the existing app (never uninstall first: that wipes the runs).
- [ ] **Step 3:** Mark the spec's status line implemented, with the date. Commit `docs: mark run features implemented`.
