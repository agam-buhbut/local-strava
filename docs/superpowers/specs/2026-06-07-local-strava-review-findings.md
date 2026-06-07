# Local Strava — Consolidated Design Review Findings

**Date:** 2026-06-07
**Inputs:** Five parallel review agents — (A1) Android platform & build, (A2) GPS
metrics & algorithms, (A3) architecture/lifecycle/state, (A4) spec
quality/scope/testability, (A5) security & privacy.

Severity is the **max** assigned across agents. `[A#]` tags show which agent(s)
raised it — multiple tags = independent corroboration (higher confidence).
**Disposition:** `FOLD` = fold into spec v2 automatically (clear win, no real
trade-off); `DECIDE` = needs a user decision before the spec rewrite.

Overall verdict: the locked decisions and the layered pure-core architecture are
sound, and the load-bearing platform bet (screen-off location via a
foreground-started `location` FGS with FINE-only, **no** `ACCESS_BACKGROUND_LOCATION`)
was **verified correct against Android docs for 13/14/15**. The happy path is
solid. The weaknesses cluster in four places: (1) GPS filtering accuracy,
(2) the service↔UI state/lifecycle seam, (3) degenerate/real-world paths, and
(4) two privacy-defeating platform defaults. None is fatal; all are fixable in
the spec before implementation.

---

## A. Build & Toolchain — mostly mechanical, fix before first build

- **[A1] FOLD — Gradle wrapper must target the cached distribution.** Only
  `gradle-8.10.2-bin` is in `~/.gradle`; no `gradle` on PATH. Point
  `gradle-wrapper.properties` at `gradle-8.10.2-bin` (satisfies AGP 8.7 min 8.9,
  runs on JDK 21) and ensure `gradle-wrapper.jar` is actually present (committed
  or copied from cache) — there's no `gradle` binary to bootstrap one.
- **[A1] FOLD — Add the Compose compiler Gradle plugin.** Kotlin 2.0 requires
  `org.jetbrains.kotlin.plugin.compose` (versioned = Kotlin). Without it, no
  `@Composable` compiles. Add to the version catalog `[plugins]` and apply in
  `app/build.gradle.kts`.
- **[A1] FOLD — Resolve Room schema export.** Set `exportSchema = false` on
  `@Database` for v1 (no migrations) to avoid the KSP "schema export directory
  not provided" build failure.
- **[A1] FOLD — Pin exact, coherent toolchain versions** (replace the "~"
  ranges): Kotlin **2.0.21**, KSP **2.0.21-1.0.28** (prefix must match Kotlin),
  AGP **8.7.3**, Gradle **8.10.2**, compileSdk/targetSdk **35**, JDK 21. Room:
  prefer **2.7.x** (built for Kotlin 2.0 / KSP2) or 2.6.1 with KSP2 validated
  (fallback `ksp { useKsp2 = false }`).
- **[A1] FOLD — Notification channel is mandatory (minSdk 26).** Create a
  low-importance channel in `Application.onCreate` before `startForeground`.

## B. GPS Metrics & Filtering — the core value; needs real rework (§5)

- **[A2] FOLD (CRITICAL) — Don't bridge dropped-fix gaps with a straight chord.**
  Today, rejected fixes are discarded but the anchor stays put, so the next
  accepted fix's distance is measured straight across the gap — cutting every
  corner inside it (≈29% under-count on a 90° turn taken across two held fixes;
  unbounded across a curved signal-loss gap). **Fix:** when the time-gap exceeds
  `MAX_FIX_GAP_MS = 5_000` or fixes were dropped, set `segmentBreak = true`, add
  no bridged distance, and reset the anchor to the new fix (start a fresh
  segment). The detail-map polyline already splits on `segmentBreak`.
- **[A2] FOLD (CRITICAL) — Define the elevation pipeline.** "Smoothed altitude"
  is undefined; hysteresis on *raw* GPS altitude leaks ~kilometres of phantom
  gain per hour. **Fix:** smoother (median-of-5 or EMA α≈0.2) → gate on
  `Location.verticalAccuracyMeters` (drop null or > ~6 m) → commit gain past a
  *smaller* hysteresis. Keep "approximate" caveat, but the algorithm must control
  noise first.
- **[A2][A4] FOLD (HIGH) — Replace the fixed `MIN_SEGMENT_M = 1.5` gate.** At
  ~1 Hz it straddles real slow-walk/jog speeds (1.3 m/s = 1.3 m/fix → dropped)
  yet barely suppresses drift (σ=5 m → ~6.3 m/fix passes). **Fix:** a
  speed-based stationary detector — accumulate no distance while smoothed speed
  < ~0.5 m/s (prefer device `speedMps` when present) — plus a tiny 1.0 m
  duplicate-fix floor. Optionally scale the floor with accuracy.
- **[A2] FOLD (HIGH) — Loosen + soften the accuracy gate.** `MAX_ACCURACY_M = 25`
  is a hard cliff that, combined with bridging, worsens corner-cutting. Raise to
  ~30–35 m with short hysteresis (tolerate ~40 m for a few fixes before declaring
  a gap). Always reject **null**-accuracy fixes.
- **[A2] FOLD (HIGH) — Lower `ELEV_THRESHOLD_M` to ~1–2 m** *after* the smoother
  is in place (3 m erases real rolling terrain — 50× ±2 m rollers = 100 m true /
  0 m counted).
- **[A2][A4] FOLD (HIGH) — Guard the enumerated edge cases and test them:** first
  fix (accuracy-gate only, set anchor, zero distance), `dt <= 0` / duplicate /
  backwards timestamps (drop), null accuracy (drop), null altitude (skip in
  elevation, don't treat as 0), provider-disabled mid-run, very long pause.
- **[A2] FOLD (MED) — Teleport gate after gaps.** `dist/dt` balloons across a
  large `dt`, letting post-reacquisition spikes through. Tie teleport rejection
  to the gap rule (break instead of validate) and add an absolute
  `MAX_JUMP_M ≈ 200 m` ceiling; never adopt a rejected fix as the new anchor.
- **[A2] FOLD (MED) — Precise current-pace window.** Deque of accepted fixes;
  `windowDistance = Σ` over fixes with `ts ≥ now − 20_000`; `windowTime =
  newest − oldest`; show `--` when windowDistance < ~3 m or < 2 fixes or
  windowTime ≤ 0; partial window in the first < 20 s. 20 s smoothing kept.
- **[A2][A3][A4] FOLD (MED) — Pace sentinel.** Stop overloading `0.0` (= infinitely
  fast). Use `Double?`/`null` (mapped to `--` in the view) consistently across
  `TrackPoint`/`Activity`/`TrackingUiState`. Make `Activity.avgPaceSecPerKm`
  nullable or derive it at read time (see C).
- **[A2] FOLD (LOW) — Use device `speedMps` when present** for the stationary
  test and as a teleport cross-check; fall back to `dist/dt` when null. (This also
  makes the stored `speedMps` field a live input, not dead weight.)
- **[A2] FOLD (LOW) — Pin `EARTH_RADIUS_M = 6_371_000.0`** for reproducible tests.
- **[A2][A4] DECIDE — Auto-pause vs. manual-pause only** (see Decisions §1). Drives
  whether "moving time" auto-excludes stops and what the big timer shows.

## C. Architecture, Lifecycle & State — the service↔UI seam (§3, §4, §6)

- **[A3] FOLD (CRITICAL) — Replace the `SharedFlow<Long>` post-stop nav one-shot
  with retained state.** A zero-replay SharedFlow drops the saved-activity id if
  the Record collector isn't active at emit (the normal "tap Stop, pocket phone"
  gesture → DB write completes later → id lost → no navigation). **Fix:** model
  the result as durable state — `TrackingUiState` gains a `Finished(activityId)`
  case the UI observes and `consume()`s once; or navigate to History and let its
  Room `Flow` surface the new run.
- **[A3][A4] FOLD (CRITICAL) — Define the `TrackingStateHolder` contract.** A
  process-mutable singleton with no reset/ownership rules shows stale stats on
  re-entry. **Fix:** `Idle` is the default and the post-consume state; the
  **service is the only writer**; ViewModels are read-only observers that send
  intents; reset to `Idle` on new START and on result-consumed. "Is a run in
  progress" derives from the service's own state, not a stale last value.
- **[A3][A4] FOLD (HIGH) — Idempotent service state machine.** Define
  Idle→Active⇄Paused→Finalizing→Idle; every intent checks state. START is a
  no-op when already active (no accumulator reset, no double `removeUpdates`-less
  listener → no double-counted distance); STOP is safe when inactive and guarded
  against re-entry during the async write; PAUSE/STOP both `removeUpdates`.
  On launch / Record mount, **rehydrate** into the live run if active and suppress
  the "Start a run" affordance.
- **[A3] FOLD (HIGH) — `START_NOT_STICKY` + null-intent-safe `onStartCommand`**
  (matches "killed process ends the run"; avoids zombie-notification crashes).
- **[A1][A3] FOLD (HIGH) — FGS start ordering as a hard contract.** Grant
  `ACCESS_FINE_LOCATION` (+ `POST_NOTIFICATIONS` on 33+) **before**
  `startForegroundService`; the service calls `startForeground` as the **first**
  action in `onStartCommand` (before building LocationSource/accumulator); START
  only from a visible Activity. Prevents `ForegroundServiceDidNotStartInTime` /
  `SecurityException` on Android 14+.
- **[A3] FOLD (LOW→important) — Pure `Fix` DTO at the metrics boundary.** Don't
  pass `android.location.Location` into `metrics/` (breaks the "no Android
  imports" rule and JVM unit-testing). `LocationSource` maps `Location → Fix(lat,
  lon, altitude?, timeMs, accuracy?, speed?, verticalAccuracy?)`;
  `RunAccumulator.update(fix: Fix)` stays pure.
- **[A3] FOLD (LOW) — Pin the callback Looper + confine accumulator access.**
  Register `LocationManager` updates on the main Looper (fine at 1 Hz) and confine
  all accumulator mutation/reads to it; StateFlow publication from there is safe.
- **[A1][A3] FOLD (LOW) — Throttle notification updates** (~every 3–5 s / on
  whole-unit change), not every 1 Hz fix.
- **[A3] DECIDE — Incremental Room flush + crash survival** (see Decisions §2).
  Tagged **expensive-to-change-later**: create the `Activity` row at START and
  insert `TrackPoint`s incrementally (vs one big transaction on STOP). Bounds
  memory on long runs, shrinks the STOP race window, and turns "lose the whole
  run on a crash" into "lose the last few seconds."
- **[A3] FOLD (MED) — Resolve `avgPace` denormalization.** Store `elapsedMs` and
  `movingTimeMs` on `Activity`; derive avg pace at read time (one division) rather
  than storing a third field that can drift.

## D. UX & Real-World / Degenerate Paths — implementer would otherwise invent

- **[A4][A3] FOLD (CRITICAL) — Mid-run permission/GPS loss.** If FINE is revoked
  or location services toggled off mid-run, `LocationManager` stops/throws.
  **Fix:** `LocationSource` catches `SecurityException` → `permissionLost` signal
  → service **auto-pauses** (preserving distance/time), notification + in-app
  banner say "Location off — paused"; resume when restored.
- **[A4][A3] FOLD (CRITICAL) — "Start while already recording" collision** —
  covered by the rehydrate + idempotent-START fix in C.
- **[A4] FOLD (HIGH) — System-back during a run.** `BackHandler` intercepts: leave
  the service running and pop to History, which shows a live "recording" indicator
  with tap-to-return. (Back never silently orphans the service.)
- **[A4] FOLD (HIGH) — Confirmations.** Stop: no modal (fast), always navigates to
  the saved run. Delete: confirmation dialog ("Delete this run? This can't be
  undone.").
- **[A4] FOLD (HIGH) — Minimum-viable-run policy.** On Stop, if accepted
  `pointCount < 2` or `distanceMeters < MIN_SAVE_M (~10 m)`, discard (don't
  persist) and toast "Run too short to save." Then History/detail never see
  degenerate rows. Detail map: for a single point, center at fixed zoom with no
  polyline; for none, "No route recorded."
- **[A4] FOLD (HIGH) — Paused notification text.** "Run paused" + frozen stats
  while paused; "Recording run" + live stats otherwise. Make the Stop action
  definite (drop "optional"); decide Pause/Resume action (recommend include).
- **[A4] FOLD (MED) — Detail-map auto-zoom timing.** Call `zoomToBoundingBox(...,
  animated=false, paddingPx)` from the MapView's first layout pass (`doOnLayout`);
  handle the <2-point bounds case. Wire osmdroid `onResume/onPause/onDetach` via a
  Compose `AndroidView` `DisposableEffect`.
- **[A4] FOLD (MED) — Formatting table + locale policy.** distance `%.2f km`
  (or `%d m` < 1 km — pick one), pace `m:ss` zero-padded → `--` when undefined,
  elapsed `H:MM:SS` collapsing to `M:SS` < 1 h, elevation `%d m`, sentinel `--`;
  state the decimal-separator/locale policy explicitly.
- **[A4] FOLD (MED) — Launcher icon.** Ship an adaptive icon (solid background +
  simple glyph via Image Asset Studio); label "Local Strava".
- **[A4] FOLD (LOW) — History sort + labels** (newest-first by `startedAtEpochMs`;
  row titled localized date + start time; empty-state copy).
- **[A4][A5] FOLD (LOW) — `speedMps`/`accuracyMeters` persisted:** keep (aids
  recompute; `speedMps` now also a live filter input per B). Note as
  write-once/raw.
- **[A4] DECIDE — Speed display** (see Decisions §3). User explicitly asked for
  "speed"; spec computes but shows it nowhere.

## E. Security & Privacy — two defaults silently break "no Google / local-only"

- **[A5] FOLD (CRITICAL) — `android:allowBackup="false"`.** Default `true` on
  target 35 → Android Auto Backup uploads the GPS-track DB + tile cache to the
  user's cloud. Set `allowBackup="false"`, `fullBackupContent="false"`, and
  `dataExtractionRules` with empty cloud-backup/device-transfer (API 31+).
- **[A5] FOLD (HIGH) — Pin the osmdroid tile cache to app-private storage.**
  osmdroid's historical default scans for the largest writable mount
  (`/sdcard/osmdroid/...`) — location-derived tiles on **shared** storage. In
  `Application.onCreate` before any MapView: `Configuration.getInstance()` →
  `osmdroidBasePath = File(cacheDir, "osmdroid")`, `osmdroidTileCache =
  File(cacheDir, "osmdroid/tiles")`. Makes the §8 "app-private" claim true.
- **[A5] FOLD (HIGH) — Component export hardening.** `TrackingService`
  `exported="false"` with **no** intent-filter; invoke only via explicit
  `Intent(context, TrackingService::class.java)`. `MainActivity` is the only
  exported component (LAUNCHER). Prevents other apps starting/stopping GPS.
- **[A5] FOLD (HIGH) — `PendingIntent` `FLAG_IMMUTABLE` + action allow-list.**
  Notification Stop action uses `FLAG_IMMUTABLE`; `onStartCommand` switches on a
  fixed allow-list of action constants and ignores unknown/null; never trust
  intent extras (state lives in the singleton).
- **[A1][A4][A5] FOLD (HIGH) — osmdroid init + User-Agent.** Same
  `Configuration.load` call also sets `userAgentValue = BuildConfig.APPLICATION_ID`
  before the first MapView (default UA `"osmdroid"` is banned by OSM servers).
  Do **not** copy legacy `WRITE_EXTERNAL_STORAGE`/`ACCESS_WIFI_STATE` from old
  osmdroid manifests.
- **[A5] FOLD (MED) — HTTPS-only tiles + privacy note.** Mandate an HTTPS tile
  source; set `android:usesCleartextTraffic="false"` to hard-fail any accidental
  `http://`. Add a §8/§13 note: viewing a run's map reveals that run's location to
  the OSM tile server + any network observer — the app's only egress; offline
  tiles are the future mitigation.
- **[A5][A1][A4] FOLD (MED) — `POST_NOTIFICATIONS` denial is non-fatal.** Request
  only on `SDK_INT >= 33`; declare unconditionally in the manifest (harmless < 33);
  on denial, **keep recording** and show an in-app "recording" banner (Stop stays
  reachable on the Record screen).
- **[A5] DECIDE — APK signing** (see Decisions §4). Debug key is shared/well-known
  and `debuggable=true` exposes the DB via ADB `run-as`.
- **[A5] FOLD (NIT) — Supply chain.** No notable CVE found for
  `org.osmdroid:osmdroid-android`; its *defaults* are the risk (handled above).
  Optionally enable Gradle dependency verification metadata.

---

## Decisions needed before the spec rewrite

1. **Pause model** — auto-pause when stationary (Strava-like; "moving time" and
   avg pace exclude stops automatically) **vs** manual Pause/Resume only (simpler,
   predictable; the big timer shows elapsed time).
2. **Crash survival / data model** — adopt incremental Room flush (Activity created
   at START, points written as you go; survive a crash losing only seconds; bounds
   memory) **vs** keep persist-only-on-STOP (simpler; a crash/OOM loses the whole
   run). *Expensive to change after the first real run exists (Room migration).*
3. **Speed display** — you explicitly asked for "speed." Show it (e.g., current
   speed km/h on Record, avg speed on detail) **vs** treat pace as sufficient and
   omit speed for minimalism.
4. **APK signing** — generate a stable local release keystore now
   (`isDebuggable=false`, authenticated updates, DB not ADB-readable) **vs** ship
   the debug-signed APK for v1 simplicity.

Everything marked **FOLD** above will be incorporated into a spec v2 once these
four are decided.
