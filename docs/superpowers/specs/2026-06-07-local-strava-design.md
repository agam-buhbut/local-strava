# Local Strava — Design Spec (v2)

**Date:** 2026-06-07
**Status:** Revised after multi-agent review; pending written-spec re-review.
**Revision:** v2 folds in the consolidated review
(`2026-06-07-local-strava-review-findings.md`) plus four user decisions:
auto-pause + manual pause; incremental persistence (crash-survivable); pace only
(no separate speed readout); debug-signed APK (accepted limitations in §13).

A minimal, local-only run tracker for Android, sideloaded as an APK. It records a
run via GPS, shows live stats, saves it locally as you go, and lets you review
past runs drawn on an OpenStreetMap map.

---

## 1. Goals & Non-Goals

### Goals (v1)
- Record a run: GPS track with timestamps and altitude, persisted incrementally.
- Live, in-run readouts: moving time, distance, current pace, average pace,
  elevation gain. (Pace satisfies the "speed" requirement — speed is pace
  inverted; see §1 Non-Goals and §5.)
- Keep recording with the screen off / app backgrounded (foreground service).
- Auto-pause when stationary; manual Pause/Resume too.
- Persist runs locally; survive a crash/kill losing at most a few seconds.
- List past runs (History) and review one: stats + route on an OSM map.
- Build to a sideloadable debug-signed APK on this machine.

### Non-Goals (out of scope — YAGNI)
- No accounts, cloud sync, or social feed.
- No live map during recording (live stats only; map on the summary).
- No segments/leaderboards/challenges.
- **No separate speed readout** (user decision): pace (min/km) is shown; speed
  (km/h) is its reciprocal and is not displayed. Device-reported speed is still
  used internally as a filter input (§5).
- No imperial units in v1 (metric only).
- No activity types beyond a generic "Run".
- No Google Play Services (framework `LocationManager`).
- **No release signing / authenticated updates** (user decision): debug-signed
  APK; implications accepted and documented in §13.
- No in-run *resume UI*, but a crashed run is recovered/finalized on next launch
  from its incrementally-saved points (§3, §6) — it is not lost.

---

## 2. Target Platform & Build Config

| Item | Value |
|---|---|
| Language | Kotlin **2.0.21** |
| UI | Jetpack Compose + Material 3 |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` **2.0.21** (Gradle plugin; required under Kotlin 2.0) |
| KSP | **2.0.21-1.0.28** (prefix must match Kotlin exactly) |
| AGP | **8.7.3** |
| Gradle (wrapper) | **8.10.2** — the only distribution cached on this box |
| JDK | 21 (installed) |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 26 (Android 8.0) |
| Room | **2.7.x** (built for Kotlin 2.0 / KSP2); fallback 2.6.1 with `ksp { useKsp2 = false }` if resolution fails |
| Output | `app/build/outputs/apk/debug/app-debug.apk` (debug-signed) |
| applicationId | `com.localstrava` |
| App label | "Local Strava" |

Manifest `<application>` hardening (privacy/security, §E findings):
- `android:allowBackup="false"`, `android:fullBackupContent="false"`, and a
  `android:dataExtractionRules="@xml/data_extraction_rules"` with empty
  cloud-backup/device-transfer sets (API 31+). Prevents the GPS database from
  being uploaded to the user's cloud backup.
- `android:usesCleartextTraffic="false"` — hard-fail any accidental `http://`
  tile request (tiles must be HTTPS).
- Debug build type is `debuggable` by default; accepted (§13).

Versions are pinned in a Gradle **version catalog** (`gradle/libs.versions.toml`)
and adjusted at first build only if a version fails to resolve.

**Wrapper note:** `gradle/wrapper/gradle-wrapper.properties` must point at
`gradle-8.10.2-bin` (already in `~/.gradle`), and `gradle/wrapper/gradle-wrapper.jar`
must be present (committed or copied from cache) — there is no `gradle` on PATH to
bootstrap one.

**Build risk:** the first build downloads AGP/Compose/Room/osmdroid from Google
Maven + Maven Central (the Gradle layer is already cached). Internet on this box
is verified before relying on the build.

---

## 3. Architecture

Layered, with a deliberately **framework-free metrics core** (no Android imports)
so the logic that matters is deterministic and unit-testable on the JVM.

```
ui/ (Compose screens + ViewModels)  ── observe ──►  TrackingStateHolder (StateFlow)
   │  send intents                                  Room Flows (History/Detail)
   ▼
tracking/ (TrackingService, LocationSource, TrackingStateHolder)
   │  Location ─► Fix (pure DTO) ─►  metrics/ (PURE Kotlin: GeoMath, RunAccumulator)
   │  incremental writes ▼
data/ (Room: Activity, TrackPoint, RunDao, RunDatabase, ActivityRepository)
```

### Components

- **`metrics/` (pure Kotlin, no Android imports)** — the unit-test target.
  - `GeoMath`: haversine (`EARTH_RADIUS_M = 6_371_000.0`).
  - `Fix`: pure input DTO — `Fix(lat, lon, altitude: Double?, timeMs, accuracy:
    Float?, speed: Float?, verticalAccuracy: Float?)`.
  - `RunAccumulator`: fed `Fix`es; maintains distance, moving time, elevation
    gain, current/avg pace, and segment boundaries. Owns all filtering (§5). No
    Android types ever enter here.

- **`tracking/`**
  - `LocationSource`: thin wrapper over `LocationManager` (GPS provider, ~1 s /
    0 m). Maps each `Location → Fix`. Registers updates on the **main Looper**
    (fine at 1 Hz); all accumulator access is confined to that thread. Catches
    `SecurityException` (permission revoked / provider disabled) → emits a
    `permissionLost` signal.
  - `TrackingService`: foreground service (type `location`). Runs a state machine
    (below), owns `LocationSource` + `RunAccumulator`, maintains the notification,
    and persists incrementally.
  - `TrackingStateHolder`: process singleton exposing `StateFlow<TrackingUiState>`.
    **The service is the only writer.** Default is `TrackingUiState.Idle`. The
    saved-run result is **retained state** (`phase = Finished(activityId)`), not a
    transient event — the UI observes it and calls `consume()` once, returning the
    holder to `Idle`. ViewModels are read-only observers that send intents; they
    never mutate the holder.

- **`data/`** — Room entities, `RunDao`, `RunDatabase`, `ActivityRepository`
  (suspend writes; `Flow` reads). `exportSchema = false` (no migrations in v1).

- **`ui/`** — `history/`, `record/`, `detail/` (each Composable + ViewModel),
  `nav/AppNav.kt`, `theme/`. osmdroid is initialized in `LocalStravaApp.onCreate`
  (see §7/§8).

### Service state machine
`Idle → Active ⇄ Paused → Finalizing → Idle`. Every intent checks current state
and is a **no-op on an illegal transition**:
- **START** (only from a visible Activity, permissions already granted): Idle→Active.
  Creates an in-progress `Activity` row (no `endedAt`), `startForeground(...)` as
  the **first** action in `onStartCommand`, registers `LocationSource`. Idempotent
  if already Active/Paused (no reset, no second listener).
- **PAUSE/RESUME**: Active⇄Paused. PAUSE stops accumulating time+distance (manual);
  RESUME marks the next accepted fix `segmentBreak = true`.
- **STOP**: →Finalizing. Unregisters `LocationSource`, flushes remaining points,
  finalizes the `Activity` (sets `endedAt`, summary), then →Idle. Safe when not
  Active; re-entrant STOP during Finalizing is ignored.
- `onStartCommand` returns `START_NOT_STICKY` and is null-intent-safe.

### Data flow (record → save → review)
1. Record screen: **Start** → ViewModel ensures `ACCESS_FINE_LOCATION` (+
   `POST_NOTIFICATIONS` on 33+) are **granted**, then `startForegroundService`.
2. Service creates the in-progress `Activity`, `startForeground`, subscribes to
   GPS. Each `Fix` → `RunAccumulator.update(fix)` → new `TrackingUiState` on the
   holder; accepted `TrackPoint`s are **buffered and flushed to Room every ~10 s
   / N points** under the in-progress `activityId`.
3. Record screen renders live stats from the `StateFlow`.
4. **Pause/Resume/Stop** → intents (also available as notification actions).
5. **Stop** → service finalizes the `Activity`; if accepted `pointCount < 2` or
   `distanceMeters < MIN_SAVE_M`, the run is **discarded** instead. Holder phase
   becomes `Finished(id)` (or `Discarded`).
6. Record screen observes `Finished(id)` → navigates to Detail, then `consume()`.
   (Durable state, so the navigation can't be missed if the screen was recreated.)
7. Detail loads `Activity` + points from Room → osmdroid polyline (split on
   `segmentBreak`) + markers + stats.
8. History observes `Flow<List<Activity>>` (completed runs only), newest-first.
9. **Crash recovery:** on app launch, any `Activity` with `endedAt == null`
   (orphaned in-progress run) is finalized from its stored points (summary
   computed, `endedAt` = last point time), or discarded if below the min-run
   threshold. The run is not lost.

---

## 4. Data Model (Room)

```kotlin
@Entity
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,        // null = in-progress (crash-recoverable)
    val movingTimeMs: Long,           // excludes auto-paused AND manually-paused time
    val distanceMeters: Double,
    val elevationGainMeters: Double,
)
// avg pace is DERIVED at read time: movingTimeMs / (distanceMeters/1000), in sec/km,
// or null when distanceMeters < MIN_PACE_DISTANCE_M (shown as "--"). Not stored.
// elapsed time is DERIVED: endedAtEpochMs - startedAtEpochMs (completed runs).

@Entity(
    foreignKeys = [ForeignKey(entity = Activity::class, parentColumns = ["id"],
        childColumns = ["activityId"], onDelete = CASCADE)],
    indices = [Index("activityId")]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampEpochMs: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,             // raw device speed; used by filters (§5) when present
    val segmentBreak: Boolean,        // first point after a manual resume or a GPS gap
)
```

- Only **accepted** fixes are stored; rejected ones are dropped (§5).
- `segmentBreak` splits the detail polyline so no straight line is drawn across a
  pause or a signal-loss gap.
- Deleting an `Activity` cascades its points.

---

## 5. Metrics & Filtering (the pure core)

Raw `LocationManager` GPS is noisy; the accumulator gates, segments, and smooths.
All constants are named and centralized.

| Constant | Value | Purpose |
|---|---|---|
| `MAX_ACCURACY_M` | 35.0 | Reject fixes worse than this (with short hysteresis to ~40 m for a few fixes before declaring a gap). |
| `STATIONARY_SPEED_MPS` | 0.5 | Below this smoothed speed → stationary → auto-pause (no time, no distance). |
| `MIN_DUP_M` | 1.0 | Drop literal duplicate/sub-noise displacements. |
| `MAX_PLAUSIBLE_SPEED_MPS` | 12.0 | Reject teleports at normal `dt` (above any sustained running pace). |
| `MAX_JUMP_M` | 200.0 | Absolute single-fix jump ceiling (catches post-gap spikes regardless of `dt`). |
| `MAX_FIX_GAP_MS` | 5_000 | Gap longer than this → segment break, no bridged distance. |
| `CURRENT_PACE_WINDOW_MS` | 20_000 | Trailing window for current pace. |
| `ELEV_VACC_MAX_M` | 6.0 | Drop altitude with null or worse-than-this vertical accuracy before smoothing. |
| `ELEV_THRESHOLD_M` | 1.5 | Hysteresis on the *smoothed* altitude before committing gain. |
| `EARTH_RADIUS_M` | 6_371_000.0 | Haversine radius. |
| `MIN_SAVE_M` | 10.0 | Runs shorter than this (or < 2 points) are discarded on Stop. |
| `MIN_PACE_DISTANCE_M` | 10.0 | Below this, pace is undefined (`--`). |

### Acceptance pipeline (per incoming `Fix`)
1. **Reject** if `accuracy == null` or `accuracy > MAX_ACCURACY_M` (with the short
   hysteresis above); reject if `dt <= 0` (duplicate/backwards timestamp).
2. **First fix:** passes accuracy only → set as anchor, add no distance.
3. **Gap/teleport:** if `dt > MAX_FIX_GAP_MS`, or the implied `dist > MAX_JUMP_M`,
   or (`dist/dt > MAX_PLAUSIBLE_SPEED_MPS` at normal `dt`) → **segment break**:
   set `segmentBreak = true`, reset the anchor to this fix, add **no** bridged
   distance. A rejected teleport never becomes the anchor.
4. **Stationary (auto-pause):** smoothed speed (prefer device `speed` when present,
   else windowed `dist/dt`) `< STATIONARY_SPEED_MPS` → accumulate **no** moving
   time and **no** distance for this interval (position held).
5. **Moving:** if `dist >= MIN_DUP_M`, add haversine distance and the interval to
   moving time; advance the anchor.

### Metrics
- **Distance** = Σ haversine over accepted, non-bridged segments.
- **Moving time** = Σ intervals where the runner is moving and not manually paused.
  The Record screen's single timer shows **moving time** (it visibly freezes on
  auto-pause and manual pause, staying consistent with average pace). Detail also
  shows **elapsed** = `endedAt − startedAt`.
- **Current pace** (sec/km): deque of accepted fixes; `windowDistance = Σ` over
  fixes with `ts ≥ now − CURRENT_PACE_WINDOW_MS`; `windowTime = newest − oldest`;
  pace = `windowTime_ms / windowDistance_m`. Show `--` when `windowDistance < 3 m`,
  fewer than 2 fixes in window, or `windowTime ≤ 0`. Partial window in the first
  < 20 s. Never spans a `segmentBreak`.
- **Average pace** = `movingTimeMs / (distanceMeters/1000)`; `--` when
  `distanceMeters < MIN_PACE_DISTANCE_M`.
- **Elevation gain** = positive deltas of a **smoothed** altitude. Pipeline:
  drop fixes with null/`> ELEV_VACC_MAX_M` vertical accuracy → median-of-5
  smoother (median of the last ≤5 gated altitudes) → commit gain when smoothed
  altitude rises `≥
  ELEV_THRESHOLD_M` above the last committed level. Documented **approximate**
  (GPS altitude; barometer is a future upgrade).
- **Speed:** not displayed (§1). Device `speed` is used internally (steps 3–4).

### Edge cases (all covered + unit-tested, §10)
First fix; `dt ≤ 0`/duplicate/backwards timestamp; null accuracy; null altitude
(skipped in elevation, never treated as 0); provider disabled mid-run
(`permissionLost` → auto-pause, §6); very long pause (gap → segment break);
window with a single point (pace `--`); zero-distance run (pace `--`, discarded on
Stop).

---

## 6. Tracking Service & Lifecycle

- Foreground service, `android:foregroundServiceType="location"`,
  `android:exported="false"`, **no** `intent-filter`; invoked only via explicit
  `Intent(ctx, TrackingService::class.java)`.
- **Start ordering (hard contract):** permissions granted → `startForegroundService`
  → `startForeground(...)` as the first action in `onStartCommand` (before building
  the location source/accumulator). Prevents `ForegroundServiceDidNotStartInTime` /
  `SecurityException` on Android 14+.
- **Notification:** low-importance channel created in `LocalStravaApp.onCreate`
  (mandatory on minSdk 26). Content reflects state: "Recording run" + live
  distance/moving-time; "Run paused" + frozen stats; "Location off — paused" when
  `permissionLost`. Includes **Stop** and **Pause/Resume** actions. All
  `PendingIntent`s use `FLAG_IMMUTABLE`. Updates throttled (~every 3–5 s / on
  whole-unit change), not every fix. `onStartCommand` validates the action against
  a fixed allow-list and ignores unknown/null.
- **Permission loss mid-run:** `LocationSource` catches `SecurityException` →
  service auto-pauses (distance/time preserved), notification + an in-app banner
  show the paused-by-location-off state; recording resumes when location is
  restored.
- **Incremental persistence:** accepted `TrackPoint`s are flushed to Room every
  ~10 s / N points under the in-progress `Activity`. STOP only finalizes the
  summary + flushes the tail (sub-100 ms), which also shrinks the STOP race window.
- **Process death:** `START_NOT_STICKY`; a killed run ends. Its saved points are
  finalized/discarded on next launch (§3.9).

---

## 7. Screens & Navigation

Three Navigation-Compose destinations.

1. **History** (`history`) — saved (completed) runs, **newest first** by
   `startedAtEpochMs`; each row: localized date + start time, distance, moving
   time, avg pace (`--` if none). Empty state: "No runs yet — tap Start a run."
   Prominent **Start a run** → `record`. If a run is currently active, show a live
   "Recording…" indicator with tap-to-return to `record`. Tap a run → `detail/{id}`.

2. **Record** (`record`) — large readouts: **moving time, distance, current pace,
   avg pace, elevation gain** (no speed). Controls: **Start** → **Pause/Resume** +
   **Stop**. On mount, if a run is active, **rehydrate** into it (don't offer a
   fresh Start). Requests `ACCESS_FINE_LOCATION` (+ `POST_NOTIFICATIONS` on 33+)
   before starting; explains + routes to settings if permanently denied; if
   notifications are denied, recording still proceeds with an in-app banner.
   **System-back during an active run** is intercepted (`BackHandler`): the service
   keeps running and the screen pops to History (with the live indicator) — back
   never orphans the service. **Stop** has no modal (fast) and navigates to the
   saved run; a discarded too-short run toasts "Run too short to save" and returns
   to History.

3. **Detail** (`detail/{id}`) — osmdroid `MapView` with the route as a `Polyline`
   (split on `segmentBreak`), start/end markers, auto-zoomed to the track bounds
   via `zoomToBoundingBox(..., animated = false, paddingPx)` called from the first
   layout pass (`doOnLayout`). `< 2` points → center on the single point at a fixed
   zoom (no polyline) or "No route recorded". Shows distance, moving time, elapsed,
   avg pace, elevation gain. Overflow **Delete run** → confirmation dialog. The
   `MapView` lifecycle (`onResume`/`onPause`/`onDetach`) is wired via the Compose
   `AndroidView` + `DisposableEffect`.

### Formatting
distance `%.2f km` (`%d m` below 1 km); pace `m:ss` zero-padded → `--` when
undefined; moving time / elapsed `H:MM:SS` collapsing to `M:SS` under an hour;
elevation `%d m` (rounded); sentinel `--`. Decimal separator: device locale via
`NumberFormat` (explicit, to avoid `String.format` locale bugs).

### Launcher icon
Adaptive icon (solid background + simple glyph via Image Asset Studio); label
"Local Strava".

---

## 8. Permissions

| Permission | When | Why |
|---|---|---|
| `ACCESS_FINE_LOCATION` | runtime, before first record | GPS fixes. |
| `POST_NOTIFICATIONS` | runtime, **only `SDK_INT >= 33`** | FGS notification. Declared unconditionally in the manifest (harmless < 33). Denial is **non-fatal** — recording continues. |
| `FOREGROUND_SERVICE` | manifest | Run a foreground service. |
| `FOREGROUND_SERVICE_LOCATION` | manifest (API 34+) | Typed FGS for location. |
| `INTERNET` | manifest | osmdroid tile fetch (detail map only). |
| `ACCESS_NETWORK_STATE` | manifest | osmdroid connectivity check. |

**Deliberately NOT requested:** `ACCESS_BACKGROUND_LOCATION` (the
foreground-started `location` FGS grants screen-off location without it — verified
against Android 13/14/15 docs). `WRITE_EXTERNAL_STORAGE` / `ACCESS_WIFI_STATE`
(legacy osmdroid cruft — not needed; cache is app-private).

**osmdroid configuration** (in `LocalStravaApp.onCreate`, before any `MapView`):
`Configuration.getInstance().load(ctx, prefs)`; `userAgentValue =
BuildConfig.APPLICATION_ID` (default UA `"osmdroid"` is banned by OSM servers);
`osmdroidBasePath = File(cacheDir, "osmdroid")` and `osmdroidTileCache =
File(cacheDir, "osmdroid/tiles")` — pinned to **app-private** storage. HTTPS tile
source only (enforced by `usesCleartextTraffic="false"`).

---

## 9. Dependencies (no Google Play Services)

| Dependency | Purpose |
|---|---|
| Jetpack Compose BOM + Material 3 | UI |
| `org.jetbrains.kotlin.plugin.compose` (Gradle plugin) | Compose compiler (Kotlin 2.0) |
| AndroidX Activity-Compose, Lifecycle/ViewModel, Navigation-Compose | App scaffolding |
| Room (runtime, ktx) + KSP compiler | Local storage |
| osmdroid-android | OSM map + route overlay |
| JUnit, kotlinx-coroutines-test | Unit tests for the metrics core |

Conservative pinned versions (§2). No `play-services-*`.

---

## 10. Testing Strategy

JVM unit tests in `app/src/test/` (deterministic; no device/network/sleep). The
pure `metrics/` core is the primary target — happy path + edge/failure per rule:

- `GeoMath`: known distances (1° lat ≈ 111.2 km), zero distance, antimeridian.
- `RunAccumulator`: distance accumulation; **gap → segment break, no bridged
  distance** (the corner-cutting fix); accuracy-gate rejection; teleport rejection
  **without adopting the rejected fix as anchor**; `MAX_JUMP_M` ceiling; stationary
  **auto-pause excludes both time and distance**; manual pause excludes time;
  elevation gain vs. noise (smoother + vertical-accuracy gate + hysteresis);
  current-pace window (full, partial < 20 s, single-point `--`, never spans a
  break); avg-pace `--` guard; first fix; `dt ≤ 0`; backwards/duplicate timestamp;
  null accuracy; null altitude; `segmentBreak` set exactly once per resume.

Service/UI glue is not unit-tested in v1 (no Robolectric); risk is mitigated by
pushing all real logic into the pure core (`Fix` DTO keeps Android types out).

---

## 11. Build & Sideload Procedure

1. Generate `local.properties` with `sdk.dir=/home/earl/Android/Sdk` (gitignored).
2. Confirm `gradle/wrapper/gradle-wrapper.properties` → `gradle-8.10.2-bin` and the
   wrapper jar is present; confirm `sdkmanager --licenses` accepted.
3. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
4. Sideload: `~/Android/Sdk/platform-tools/adb install -r app-debug.apk`, or copy
   to the phone and tap (enable "Install unknown apps").
5. First launch: grant location + notifications when prompted.

---

## 12. Project Structure

```
local-strava/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.{properties,jar}   (→ gradle-8.10.2-bin)
  gradlew, gradlew.bat
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/
      xml/data_extraction_rules.xml                (empty backup sets)
      mipmap-*/ (adaptive launcher icon), values/ (theme, strings)
    src/main/java/com/localstrava/
      MainActivity.kt
      LocalStravaApp.kt                 (Application: notif channel + osmdroid init)
      metrics/   GeoMath.kt, Fix.kt, RunAccumulator.kt, Constants.kt   ← pure, tested
      data/      Activity.kt, TrackPoint.kt, RunDao.kt, RunDatabase.kt, ActivityRepository.kt
      tracking/  LocationSource.kt, TrackingService.kt, TrackingStateHolder.kt, TrackingUiState.kt
      ui/        nav/AppNav.kt
                 history/HistoryScreen.kt, HistoryViewModel.kt
                 record/RecordScreen.kt, RecordViewModel.kt
                 detail/DetailScreen.kt, DetailViewModel.kt
                 theme/...
    src/test/java/com/localstrava/metrics/  GeoMathTest.kt, RunAccumulatorTest.kt
  docs/superpowers/specs/
    2026-06-07-local-strava-design.md            (this file)
    2026-06-07-local-strava-review-findings.md
```

---

## 13. Known Limitations / Accepted Risks

- **Elevation accuracy:** GPS-altitude based (now smoothed + vertical-accuracy
  gated); still approximate. Barometer is a future upgrade.
- **Track quality:** raw `LocationManager` is noisier than fused; mitigated by the
  filtering core (segment breaks, stationary detection, jump ceilings).
- **Debug-signed APK (user decision):** signed with the shared, well-known Android
  debug key and is `debuggable=true`. Implications, accepted for personal use:
  updates are not authenticated (anyone can produce an APK that updates
  `com.localstrava`), and the run database is readable via ADB `run-as` on a
  device with USB debugging. Switching to a local release keystore later is a small
  additive change.
- **Tile privacy:** viewing a run's map reveals that run's location to the OSM tile
  server and any network observer (HTTPS in transit). It is the app's only network
  egress. Offline/pre-seeded tiles are the future mitigation.
- **No in-run resume UI:** a crashed/killed run is finalized from its
  incrementally-saved points on next launch (loses at most the last unsaved
  interval), but recording does not auto-resume.
- **Speed not shown (user decision):** pace (min/km) is displayed; speed is its
  reciprocal.
- **First build needs network** to fetch the toolchain + dependencies.
