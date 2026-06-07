# Local Strava — Design Spec

**Date:** 2026-06-07
**Status:** Approved-in-substance, pending written-spec review

A minimal, local-only run tracker for Android, sideloaded as an APK. It records
a run via GPS, shows live stats, saves it locally, and lets you review past runs
drawn on an OpenStreetMap map.

---

## 1. Goals & Non-Goals

### Goals (v1)
- Record a run: GPS track with timestamps and altitude.
- Live, in-run readouts: elapsed time, distance, current pace, average pace,
  elevation gain.
- Keep recording with the screen off / app backgrounded (real runs).
- Persist runs locally and list them (History).
- Review a saved run: stats + route drawn on an OSM map ("where it was").
- Build to a sideloadable debug-signed APK on this machine.

### Non-Goals (explicitly out of scope — YAGNI)
- No accounts, login, cloud sync, or social feed.
- No live map during recording (decided: live stats only; map on the summary).
- No segments/leaderboards/challenges.
- No imperial units in v1 (metric only; imperial is a trivial later toggle).
- No activity types beyond a generic "Run" (walk/bike are future, easy).
- No Google Play Services (decided: framework `LocationManager`).
- No release-keystore signing ceremony in v1 (debug APK installs fine).

---

## 2. Target Platform & Build Config

| Item | Value |
|---|---|
| Language | Kotlin 2.0.x |
| UI | Jetpack Compose + Material 3 |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 26 (Android 8.0) — runs comfortably on the target Android 13+ phone |
| AGP | ~8.7.x (compatible with `compileSdk 35` + JDK 21) |
| Gradle | ~8.9 (wrapper) |
| JDK | 21 (installed) |
| Build tools | 35.0.0 (installed) |
| Output | `app/build/outputs/apk/debug/app-debug.apk` |
| applicationId | `com.localstrava` |
| App label | "Local Strava" (trivially renamable) |

Exact dependency versions are pinned in a Gradle **version catalog**
(`gradle/libs.versions.toml`) and adjusted at first build if a version fails to
resolve. `local.properties` (with `sdk.dir`) is generated locally and gitignored.

**Build risk:** the first build downloads AGP/Gradle/Compose/Room/osmdroid from
Google Maven + Maven Central. Internet on this box is verified before relying on
the build succeeding.

---

## 3. Architecture

Layered, with a deliberately **framework-free metrics core** so the logic that
matters is deterministic and unit-testable without a device or Robolectric.

```
ui/ (Compose screens + ViewModels)
      │  observes StateFlow / Room Flows
      ▼
tracking/ (TrackingService, TrackingStateHolder, LocationSource)
      │  feeds raw fixes into ───────────────► metrics/ (PURE Kotlin)
      │  on stop, writes via                    GeoMath, RunAccumulator, filters
      ▼
data/ (Room: entities, DAO, database, ActivityRepository)
```

### Components & responsibilities

- **`metrics/` (pure Kotlin, no Android imports)**
  - `GeoMath`: haversine distance between lat/lon.
  - `RunAccumulator`: stateful but pure; fed accepted fixes, maintains distance,
    moving time, elevation gain, current/avg pace. Holds the filtering rules.
  - Filtering: accuracy gate, GPS-jump rejection, elevation hysteresis,
    stationary-noise gate (see §5).
  - **This package is the unit-test target.**

- **`tracking/`**
  - `LocationSource`: thin wrapper over Android `LocationManager` (GPS provider).
    Requests updates (~1 s / 0 m), exposes a callback of raw `Location`s. Keeps
    the only Android-location dependency in one small, swappable place.
  - `TrackingService`: foreground service (type `location`). Owns `LocationSource`
    + a `RunAccumulator`. Handles `START/PAUSE/RESUME/STOP` intents, maintains the
    persistent notification, and on `STOP` persists the run via the repository.
  - `TrackingStateHolder`: process singleton exposing
    `StateFlow<TrackingUiState>` (live stats) and a `SharedFlow<Long>`
    (saved-activity-id one-shot for post-stop navigation). The service writes;
    the Record screen reads. Avoids bound-service boilerplate.

- **`data/`**
  - Room entities `Activity` and `TrackPoint`, a DAO, the database, and
    `ActivityRepository` (suspend writes; `Flow` reads for History/Detail).

- **`ui/`**
  - `history/`, `record/`, `detail/` — each a Composable screen + ViewModel.
  - `theme/` — Material 3 theme.
  - Navigation via Navigation-Compose (3 destinations).

### Data flow (record → save → review)
1. Record screen: user taps **Start** → ViewModel ensures permissions → sends
   `ACTION_START` to `TrackingService` → `startForeground(...)`.
2. Each GPS fix → `RunAccumulator.update(fix)` → new `TrackingUiState` emitted on
   `TrackingStateHolder`.
3. Record screen renders live stats from the `StateFlow`.
4. **Pause/Resume/Stop** → intents to the service.
5. **Stop** → service finalizes the `Activity`, writes it + all `TrackPoint`s to
   Room (background coroutine), stops foreground, emits the new `activityId`.
6. Record screen collects the id → navigates to **Run detail**.
7. Run detail loads `Activity` + points from Room → draws osmdroid polyline +
   start/end markers + stats.
8. History observes `Flow<List<Activity>>` from Room → list.

---

## 4. Data Model (Room)

```kotlin
@Entity
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val movingTimeMs: Long,          // excludes paused time
    val distanceMeters: Double,
    val elevationGainMeters: Double,
    val avgPaceSecPerKm: Double,     // zero/NaN-guarded; stored as 0 if no distance
)

@Entity(
    foreignKeys = [ForeignKey(
        entity = Activity::class, parentColumns = ["id"],
        childColumns = ["activityId"], onDelete = CASCADE)],
    indices = [Index("activityId")]
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,     // null if device gave none
    val timestampEpochMs: Long,
    val speedMps: Float?,            // raw, if provided
    val accuracyMeters: Float?,
    val segmentBreak: Boolean,       // true on first point after a pause
)
```

- Full point track is stored so routes redraw and metrics can be recomputed.
- `segmentBreak` lets the detail map avoid drawing a straight line across a pause
  gap (it splits the polyline there).
- Deleting an `Activity` cascades its points.

---

## 5. Metrics & Filtering (the pure core)

Raw `LocationManager` GPS is noisy, so the accumulator gates and smooths. All
constants are named and centralized.

| Constant | Value | Purpose |
|---|---|---|
| `MAX_ACCURACY_M` | 25.0 | Drop fixes with horizontal accuracy worse than this. |
| `MAX_PLAUSIBLE_SPEED_MPS` | 12.0 (~43 km/h) | Reject GPS "teleport" jumps; well above running speed. |
| `MIN_SEGMENT_M` | 1.5 | Ignore sub-fix segments (stationary noise) to limit drift. |
| `ELEV_THRESHOLD_M` | 3.0 | Hysteresis: only commit elevation gain past this delta. |
| `CURRENT_PACE_WINDOW_MS` | 20_000 | Rolling window for "current pace". |

**Distance** = Σ haversine between consecutive *accepted* fixes. A fix is accepted
when: it has accuracy and `accuracy ≤ MAX_ACCURACY_M`; the implied speed to the
previous accepted fix is `≤ MAX_PLAUSIBLE_SPEED_MPS`; and the segment is
`≥ MIN_SEGMENT_M`. Rejected fixes are discarded entirely in v1 (not stored), which
keeps the saved track clean.

**Average pace** = `movingTimeMs / distanceKm`, in sec/km. Moving time excludes
paused intervals. Guarded against zero distance (shown as `--`).

**Current pace** = over the trailing `CURRENT_PACE_WINDOW_MS` of accepted fixes:
`windowTime / windowDistance`. If window distance ≈ 0 (stopped), shown as `--`.

**Speed (km/h)** available from current pace for display.

**Elevation gain** = cumulative positive change of a smoothed altitude with
`ELEV_THRESHOLD_M` hysteresis against the last committed altitude. **Documented as
approximate** — GPS altitude is inherently noisy; barometer is a future upgrade.

**Pause/Resume:** while paused, fixes are ignored (no distance, no time). The first
accepted fix after resume sets `segmentBreak = true`.

---

## 6. Tracking Service & Lifecycle

- Foreground service, `android:foregroundServiceType="location"`.
- Started via `ContextCompat.startForegroundService` while the app is in the
  foreground, then immediately `startForeground(notificationId, notification)`.
- Persistent notification: "Recording run" + live distance/time; tapping reopens
  the app. (Optional Stop action button on the notification — included, cheap.)
- States exposed via `TrackingUiState`: `isActive`, `isPaused`, `elapsedMs`,
  `distanceMeters`, `currentPaceSecPerKm`, `avgPaceSecPerKm`, `elevationGainMeters`,
  `pointCount`.
- On process death mid-run, v1 simply ends recording (no crash-recovery of an
  in-progress run — explicitly out of scope; noted as a known limitation).

---

## 7. Screens & Navigation

Three Navigation-Compose destinations:

1. **History** (`history`)
   - List of saved runs: date/time, distance, duration, avg pace.
   - Prominent **Start a run** button → `record`.
   - Empty state when no runs yet.
   - Tap a run → `detail/{id}`.

2. **Record** (`record`)
   - Large live readouts: time, distance, current pace, avg pace, elevation gain.
   - Controls: **Start** → **Pause/Resume** + **Stop**.
   - Requests `ACCESS_FINE_LOCATION` (and `POST_NOTIFICATIONS` on 13+) before
     starting; explains and routes to settings if permanently denied.
   - On **Stop**, navigates to `detail/{newId}`.

3. **Run detail** (`detail/{id}`)
   - osmdroid `MapView` with the route as a `Polyline` (split on `segmentBreak`),
     start/end markers, auto-zoomed to the track bounds.
   - Summary stats. Overflow action: **Delete run**.

---

## 8. Permissions

| Permission | When | Why |
|---|---|---|
| `ACCESS_FINE_LOCATION` | runtime, before first record | GPS fixes. |
| `POST_NOTIFICATIONS` | runtime (API 33+) | Foreground-service notification. |
| `FOREGROUND_SERVICE` | manifest | Run a foreground service. |
| `FOREGROUND_SERVICE_LOCATION` | manifest (API 34+) | Typed FGS for location. |
| `INTERNET` | manifest | osmdroid tile fetch on the detail map. |
| `ACCESS_NETWORK_STATE` | manifest | osmdroid connectivity check. |

**Deliberately NOT requested:** `ACCESS_BACKGROUND_LOCATION`. Because the
foreground service is started while the app is visible, continued location with
the screen off is granted without it — avoiding the "Allow all the time"
settings-redirect flow.

osmdroid writes a small tile cache to app-private storage (no extra permission on
modern Android).

---

## 9. Dependencies (approved set — no Google Play Services)

| Dependency | Purpose |
|---|---|
| Jetpack Compose BOM + Material 3 | UI |
| AndroidX Activity-Compose, Lifecycle, ViewModel, Navigation-Compose | App scaffolding |
| Room (runtime, ktx) + KSP compiler | Local storage |
| osmdroid-android | OSM map + route overlay |
| JUnit (test) | Unit tests for the metrics core |
| kotlinx-coroutines-test (test, if needed) | Flow/coroutine tests |

Conservative stable versions, pinned in the version catalog.

---

## 10. Testing Strategy

Per project conventions, tests live in `app/src/test/` (JVM, deterministic — no
device, no network, no sleep).

- **`metrics/` is the primary test target.** Cover happy path + at least one
  edge/failure per function:
  - `GeoMath`: known distances (e.g., 1° lat ≈ 111 km), zero distance, antimeridian sanity.
  - `RunAccumulator`: distance accumulation; accuracy-gate rejection; GPS-jump
    rejection; stationary-noise gate; elevation hysteresis (gain vs. noise);
    pause excludes time/distance; avg & current pace including zero-distance
    guards (`--`).
- Service/UI glue is kept thin and is **not** unit-tested in v1 (no Robolectric);
  the risk is mitigated by pushing all real logic into the pure core.

---

## 11. Build & Sideload Procedure

1. Generate `local.properties` with `sdk.dir=/home/earl/Android/Sdk`.
2. `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`.
3. Sideload: `adb install -r app-debug.apk` (adb at
   `~/Android/Sdk/platform-tools/adb`), or copy the APK to the phone and tap it
   (enable "Install unknown apps").
4. First launch: grant location + notifications when prompted.

---

## 12. Project Structure

```
local-strava/
  settings.gradle.kts
  build.gradle.kts
  gradle/libs.versions.toml
  gradle/wrapper/gradle-wrapper.{properties,jar}
  gradlew, gradlew.bat
  app/
    build.gradle.kts
    src/main/AndroidManifest.xml
    src/main/res/...                       (theme, icons, strings)
    src/main/java/com/localstrava/
      MainActivity.kt
      LocalStravaApp.kt                    (Application)
      metrics/   GeoMath.kt, RunAccumulator.kt, Filters.kt   ← pure, tested
      data/      Activity.kt, TrackPoint.kt, RunDao.kt, RunDatabase.kt, ActivityRepository.kt
      tracking/  LocationSource.kt, TrackingService.kt, TrackingStateHolder.kt, TrackingUiState.kt
      ui/        nav/AppNav.kt
                 history/HistoryScreen.kt, HistoryViewModel.kt
                 record/RecordScreen.kt, RecordViewModel.kt
                 detail/DetailScreen.kt, DetailViewModel.kt
                 theme/...
    src/test/java/com/localstrava/metrics/  GeoMathTest.kt, RunAccumulatorTest.kt
  docs/superpowers/specs/2026-06-07-local-strava-design.md
```

---

## 13. Known Limitations / Risks

- **Elevation accuracy:** GPS-altitude based; approximate. Acceptable for v1.
- **Track quality:** raw `LocationManager` is noisier than fused; mitigated by the
  filtering core, but expect occasional jitter on poor-signal segments.
- **No in-run crash recovery:** a killed process loses the in-progress run.
- **First build needs network** to fetch the toolchain + dependencies.
- **Tile licensing:** osmdroid hits OSM's tile servers; fine for personal use.
  Heavy use should set a custom User-Agent (we set the package name) and respect
  the tile usage policy.
```
