# Implementation Plan — Fix GPS distance under-count + elevation (Approach A)

**Date:** 2026-06-15
**Status:** Approved and implemented 2026-06-15. Full APK build + 34 unit tests green; metrics/ confirmed framework-free. **§4 re-measure PASSED 2026-07-07:** app 1.37 km / 15:28 vs Strava 1.41 km / 15:30 (app stopped a few seconds earlier ≈0.5–1% of distance) — effective gap ≈2%, inside the ~5% target, not over-counting. Down from 23% short. No gate retuning justified; second-pass levers stay closed.
**Trigger:** App reads ~23% short on distance vs Strava (same run: Strava ~1.80 km / 15:08 @ 8:25/km; app ~1.38 km / 13:52 @ 10:01/km), only ~8% short on moving time; elevation gain "way off".

## 1. Root-cause summary

The 23% distance short / 8% time short asymmetry is the signature of **sparse, unreliable GPS feeding chord-based distance accumulation**, not an accumulator math bug. Confirmed mechanisms:

- **Sparse fixes → chord error.** `RunAccumulator.update` takes one haversine from the last anchor to the incoming fix. Raw `GPS_PROVIDER` drops fixes and cold-starts slowly, so anchors are several seconds apart; the straight chord cuts the curved running path. Across far fewer effective fixes than the ~833 wall-clock seconds, this compounds to a large distance loss.
- **Accuracy gate rejects unfused cold-start/fade fixes.** Any fix with `accuracy > MAX_ACCURACY_M (35 m)` returns null; raw GPS cold-starts at ~50 m for 30–90 s. Rejections discard both distance *and* the interval's time.
- **`MAX_FIX_GAP_MS=5000` segment breaks.** Gaps > 5 s start a new segment with no bridged distance. Raw GPS produces these gaps far more than a fused feed; real distance during the gap is lost.
- **Why time only drops 8%:** a gap break still counts the full `dt` against subsequent average pace, so lost distance reads as "slower pace" rather than "less time" — masking the distance loss.

The fix is overwhelmingly **denser/more reliable fixes (FLP)**, not gate loosening. The asymmetry means the system **under-counts**; we must not over-correct into over-count (Kalman/smoothing is forbidden — it cuts corners and worsens chord cutting).

**Critical scoping decision from review:** the two proposed accumulator constant changes (`STATIONARY_SPEED_MPS` and `MAX_FIX_GAP_MS`) are **dropped from this cycle**. Review (over-correction lens, blocker/high) showed they are unjustified by the measured data — the run's moving pace (1.66–1.98 m/s) is 3–4x above the stationary gate, so stationary rejection is not the cause; and loosening these gates is precisely the under-count→over-count flip the task forbids. FLP density is the lever. Gate retuning, if ever needed, happens in a **second pass gated on empirical re-measure**, with over-count-direction tests added first. **Constants.kt is therefore unchanged in this cycle.**

**Elevation gap:** GPS vertical accuracy is poor and is gated at `ELEV_VACC_MAX_M=6.0`, so most altitude samples are discarded or noisy. A barometric pressure sensor gives ~0.5–1 m relative accuracy; using relative pressure-derived altitude deltas fixes elevation gain on devices that have the sensor, with GPS altitude as fallback. Barometer gain is **relative-only** and subject to slow weather/thermal drift (a few metres over a run); no sea-level calibration or temperature compensation is attempted.

---

## 2. Change set (ordered for incremental TDD)

Order chosen so each step is independently testable; pure-metrics changes (JVM-testable) land before/independently of Android-only wiring.

### Step 0 — Dependency + manifest (no logic change)

**Files:** `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`

- `libs.versions.toml` `[versions]`: add `playServicesLocation = "21.3.0"` (verified compatible with compileSdk 35 / AGP 8.7.3 / Kotlin 2.0.21 / minSdk 26; fall back to 21.0.1 only if a build issue surfaces).
- `libs.versions.toml` `[libraries]`: add `play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "playServicesLocation" }`.
- `app/build.gradle.kts`: `implementation(libs.play.services.location)`.
- **Manifest:** no permission change needed (`ACCESS_FINE_LOCATION`/`FOREGROUND_SERVICE_LOCATION` already present; barometer needs no permission). Add one optional feature tag:
  `<uses-feature android:name="android.hardware.sensor.barometer" android:required="false" />`
- **FOSS-posture supply-chain check (required before commit):** the project is explicitly NO-GMS by design and there is no flavor split, so GMS client libs link into all builds.
  - Run `./gradlew :app:dependencies` to confirm no unexpected transitive bloat.
  - Run `./gradlew :app:processDebugManifest` and inspect the merged manifest for an injected `com.google.android.gms.permission.AD_ID` permission or any ads/measurement `ContentProvider`. If `AD_ID` appears, add `<uses-permission android:name="com.google.android.gms.permission.AD_ID" tools:node="remove"/>` (and the `tools` namespace if missing). Document the merged-manifest delta in the PR description.

### Step 1 — LocationSource → interface + FLP impl + LocationManager fallback (PRIMARY FIX)

**Files:** `app/src/main/java/com/localstrava/tracking/LocationSource.kt` (refactor to interface), new `FusedLocationSource.kt`, current impl moved verbatim to new `LegacyLocationSource.kt`, new `LocationSourceFactory.kt`. `TrackingService.kt` (one-line construction change).

> **Public-surface note (CLAUDE.md hard-stop, confirming as approved Approach A scope):** `LocationSource` changes from a concrete class to an interface of the same name. Only `TrackingService` references it. Flagged here per the API-change hard stop; in scope under Approach A.

Chosen shape (signature-level):

```kotlin
// LocationSource.kt — interface; preserves the existing API the service depends on.
interface LocationSource {
    fun start()   // main-thread only
    fun stop()    // main-thread only
}

// Shared Location -> Fix mapper kept verbatim (top-level internal fun). Fix is unchanged.
internal fun Location.toFix(): Fix = /* identical to current LocationSource.kt mapping */

// LegacyLocationSource.kt — current LocationManager GPS_PROVIDER code moved verbatim.
class LegacyLocationSource(context, onFix: (Fix)->Unit, onLost: ()->Unit) : LocationSource

// FusedLocationSource.kt
class FusedLocationSource(
    context: Context,
    private val onFix: (Fix) -> Unit,
    private val onLost: () -> Unit,
) : LocationSource {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val looper = context.mainLooper   // SINGLE-THREAD CONFINEMENT preserved
    private var callback: LocationCallback? = null
    private var firstFixReceived = false       // suppress premature onLost() at acquisition

    @SuppressLint("MissingPermission")
    override fun start() {
        if (callback != null) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(1000L)
            .setWaitForAccurateLocation(true)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                firstFixReceived = true
                result.locations.forEach { onFix(it.toFix()) }   // FLP may batch >1 Location
            }
            override fun onLocationAvailability(a: LocationAvailability) {
                // Suppress the "GPS lost" flash during initial acquisition.
                if (!a.isLocationAvailable && firstFixReceived) onLost()
            }
        }
        callback = cb
        try {
            client.requestLocationUpdates(request, cb, looper)  // delivers on main Looper
        } catch (e: SecurityException) { callback = null; onLost() }
          catch (e: Exception)         { Log.e(TAG, "FLP start failed", e); callback = null; onLost() }
    }

    override fun stop() { callback?.let { client.removeLocationUpdates(it) }; callback = null; firstFixReceived = false }
}

// LocationSourceFactory.kt
object LocationSourceFactory {
    fun create(context: Context, onFix: (Fix)->Unit, onLost: ()->Unit): LocationSource =
        if (flpUsable(context)) FusedLocationSource(context, onFix, onLost)
        else LegacyLocationSource(context, onFix, onLost)

    // SUCCESS or SERVICE_VERSION_UPDATE_REQUIRED -> FLP is usable; anything else -> legacy.
    private fun flpUsable(context: Context): Boolean {
        val r = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        return r == ConnectionResult.SUCCESS || r == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED
    }
}
```

- **Threading / confinement (broadened per review):** `requestLocationUpdates(request, callback, context.mainLooper)` delivers **both** `onLocationResult` and `onLocationAvailability` on the main Looper — the *same* thread `onStartCommand`/`handleStop` run on. This preserves single-thread confinement of not just the accumulator but also the `buffer` `ArrayList` (mutated in `onFix`, read/cleared in `handleStop`) and `TrackingStateHolder` writes. The mainLooper instance passed must be the service's main Looper (matches the existing `LegacyLocationSource`).
- **LocationRequest params:** `PRIORITY_HIGH_ACCURACY`, interval 1000 ms, `minUpdateInterval 1000 ms`, `setWaitForAccurateLocation(true)` (delays first fix ~5–10 s for accuracy → fewer cold-start rejections). This **targets** 1 Hz; FLP may batch-deliver multiple `Location`s per callback (hence `forEach`). The gap gate tolerates occasional jitter; `fix.timeMs` uses `Location.getTime()` and the existing `dt<=0` guard handles non-monotonic ordering within a burst.
- **GMS selection caveat (documented):** selection happens once at `handleStart` and is never re-evaluated. A transient GMS state at start (e.g. `SERVICE_UPDATING`) would route a GMS device onto the legacy path for the whole session. Accepting `SERVICE_VERSION_UPDATE_REQUIRED` as usable narrows this; the try/catch still degrades to `onLost()` (not a crash) if FLP turns out unusable at runtime.
- **Loss signaling:** `onLocationAvailability(!isLocationAvailable && firstFixReceived) → onLost()`. The `firstFixReceived` guard suppresses a premature "GPS signal lost" notification during initial acquisition (cosmetic regression flagged in review). `TrackingService.onLocationLost()` semantics otherwise unchanged (do **not** pause the accumulator; gap logic handles it).
- **Fallback:** GMS-absent (F-Droid/GrapheneOS) → `LegacyLocationSource` (current behavior).
- **Service change:** construction line becomes `locationSource = LocationSourceFactory.create(this, ::onFix, ::onLocationLost)`. Field type changes from concrete `LocationSource` to the same-named interface; `start()/stop()` call sites unchanged.
- **Teardown (review — leaked callback):** ensure `removeLocationUpdates` runs on **every** teardown path. `handleStop()` already calls `locationSource.stop()` synchronously on the main thread (before/outside the `scope.launch` block). **Add `locationSource.stop()` to `onDestroy()`** as idempotent belt-and-suspenders (the `callback != null` guard makes a double-stop a no-op), so an abnormal teardown does not leak a 1 Hz GMS request.

### Step 2 — Barometer elevation (sensor in tracking/, pure channel into metrics/)

Barometer is **in scope** (task mandates Approach A item 3). It flows via a **separate pure-Double channel**, not via `Fix`, preserving the framework-free `metrics/` invariant.

**Files:** new `app/src/main/java/com/localstrava/tracking/BarometerSource.kt` (Android), `RunAccumulator.kt` (+ new pure methods), `Constants.kt` (+ barometer constants only — no gate retuning), `TrackingService.kt` (lifecycle wiring), `Fix.kt` **unchanged**.

> **Public-surface note (CLAUDE.md hard-stop, confirming as approved Approach A scope):** two new public methods are added to `RunAccumulator` (`setBarometerActive`, `onBarometerAltitude`). Purely additive — existing `update()`/`snapshot()` signatures unchanged.

**`BarometerSource.kt` (Android only):**
- Registers a `SensorEventListener` for `Sensor.TYPE_PRESSURE` using the **4-arg overload** to guarantee main-Looper delivery:
  `sensorManager.registerListener(listener, pressureSensor, SensorManager.SENSOR_DELAY_NORMAL, Handler(context.mainLooper))`.
  **This is a hard requirement** (blocker in two review lenses): there is no `registerListener` overload that takes a `Looper`; the 3-arg form delivers on a SensorManager-managed thread and would race `onFix`'s mutation of the shared elevation state (`altBuffer` `ArrayDeque`, `lastCommittedAlt`, `elevationGain` — `RunAccumulator` is explicitly not thread-safe). The 4-arg `Handler(mainLooper)` posts callbacks to the same confinement thread as the location path.
- Null-check `getDefaultSensor(TYPE_PRESSURE)`; if null → `onUnavailable()`.
- **Cadence reconciliation (review high):** `SENSOR_DELAY_NORMAL` is ~5 Hz, but the accumulator's median-of-`ELEV_SMOOTH_WINDOW` + `ELEV_THRESHOLD_M` hysteresis is tuned for ~1 Hz. **Decimate in `BarometerSource` to ~1 Hz** — emit at most one altitude per second (drop intermediate samples) — so the existing 1 Hz-tuned smoothing constants remain valid and short-term pressure transients (HVAC/doors/wind/gait) get more temporal rejection. Decimation is sensor-domain logic and stays in `tracking/`.
- **Pressure→altitude conversion lives here in `tracking/`** (sensor-domain math, keeps `metrics/` framework-free): standard-atmosphere `44330*(1-(P/1013.25)^(1/5.255))`. **Input units are hPa/millibar** (same as `TYPE_PRESSURE` and the 1013.25 reference) — assert/comment this; no kPa/Pa slip. The absolute value is a pseudo-altitude meaningful **only within the barometer channel** and must never be diffed against GPS altitude (see baseline reset below). Emits an absolute pseudo-altitude `Double` to the accumulator.
- **`start()`** registers; called synchronously on the main thread in `handleStart` after `startForeground`. **`stop()`** unregisters; called synchronously on the main thread in `handleStop` (alongside `locationSource.stop()`, **not** inside `scope.launch`) **and** idempotently in `onDestroy`. `start()` guards against double-registration.
- **Staleness fallback (review medium):** sensors are not guaranteed to fire `onUnavailable` when they stop. `BarometerSource` tracks the last-sample timestamp; if no pressure sample arrives for `BAROMETER_STALE_MS`, it calls `onUnavailable()` (which sets the accumulator back to the GPS channel). Document that `onUnavailable` from the framework is best-effort.

**`RunAccumulator` new pure methods** (no Android imports):

```kotlin
fun setBarometerActive(active: Boolean)   // switches the active elevation source
fun onBarometerAltitude(altitudeMeters: Double)  // pure Double in
```

- **Pause/stationary gating (review medium):** `onBarometerAltitude` must inherit the same gating GPS elevation has. GPS elevation only updates after `update()`'s `manualPaused` early-return and only when the run is advancing (stationary returns null → no `updateElevation`). So `onBarometerAltitude` **early-returns when `manualPaused` is true**, and does not ratchet elevation while the run is auto-paused/stationary. This preserves auto-pause semantics and prevents banking gain while standing at a light.
- **Datum isolation on channel switch (review high, two lenses):** barometer pseudo-altitude and GPS MSL altitude sit on **completely different absolute datums** (tens to hundreds of metres apart). The shared `lastCommittedAlt`/`altBuffer` would produce a spurious gain spike or silent ratchet-down at any runtime switch. Therefore `setBarometerActive(...)` **clears `altBuffer` and resets `lastCommittedAlt = null` on every transition** (without zeroing `elevationGain`), so the new channel re-baselines from its first smoothed sample via the existing `last == null` path. The two channels never share an absolute datum.
- **Double-count guard:** when barometer is the active source, the GPS `updateElevation(fix)` path inside `update()` is **skipped** via a single source-selection flag on the confinement thread; when inactive, GPS elevation resumes. Switch boundary is unit-tested for no double-count and no false gain.
- Barometer altitude reuses the existing median-of-`ELEV_SMOOTH_WINDOW` + `ELEV_THRESHOLD_M` ratchet (validated by the ~1 Hz decimation above).

**`Constants.kt`:** add `BAROMETER_REFERENCE_PRESSURE_MB = 1013.25f` and `BAROMETER_STALE_MS` (staleness fallback). Keep `ELEV_THRESHOLD_M`, `ELEV_SMOOTH_WINDOW` as-is (barometer decimated to 1 Hz so the existing tuning holds). **No stationary/gap gate changes in this cycle.** Comments record fixed rationale only — no provisional/"awaiting re-measure" action items in delivered code (kept in PR description instead).

**`TrackingService` wiring:** construct `BarometerSource(this, ::onBarometerPressureAltitude, ::onBarometerUnavailable)` in `handleStart`; `onBarometerPressureAltitude` calls `accumulator.onBarometerAltitude(...)` and, on the first sample, `accumulator.setBarometerActive(true)`; `onBarometerUnavailable` calls `accumulator.setBarometerActive(false)`. If the sensor is absent at construction, never activate (GPS channel stays). All callbacks on the main Looper → confinement preserved.

---

## 3. Test plan (TDD — write tests first, per step)

**Existing baseline (corrected per review):** **27 tests** — `RunAccumulatorTest` **16**, `GeoMathTest` 4, `FormatTest` 7. Run `./gradlew testDebugUnitTest` to capture the actual green baseline **before any edit**.

Because **Constants.kt gate values are unchanged this cycle**, there is no boundary risk to existing stationary/gap tests; `jitterWithNoDeviceSpeedReadsAsStationary`, `stationaryFixesAddNoDistanceOrTime`, `timeGapStartsNewSegmentWithoutBridgingDistance`, and `currentPaceDoesNotSpanSegmentBreak` are unaffected. **No existing test file is modified** (hard rule).

**New unit tests (pure JVM, `RunAccumulatorTest.kt`) — barometer only:**

- `elevationGainFromBarometerChannel` — `setBarometerActive(true)`, feed altitude ramp 100→105 via `onBarometerAltitude`; assert gain ≈ 5 m.
- `barometerNoiseBelowThresholdProducesNoGain` — ±0.5 m jitter; assert 0 gain.
- `gpsElevationSuppressedWhenBarometerActive` — barometer active with a **+3 m** climb while GPS-altitude fixes carry a **+10 m** climb; assert gain == 3 m (distinct magnitudes so the test distinguishes suppression from coincidence, not a tautology).
- `gpsElevationFallbackWhenBarometerUnavailable` — `setBarometerActive(false)`; assert existing GPS path still produces gain.
- `switchingElevationChannelDoesNotInjectFalseGain` — barometer climb → `setBarometerActive(false)` → GPS fixes at a **totally different absolute altitude**; assert no spurious gain jump (locks the datum-reset).
- `barometerElevationNotCountedWhileManuallyPaused` — `manualPaused` true while feeding a barometer ramp; assert 0 gain.
- `barometerElevationNotCountedWhileStationary` — run stationary; assert barometer does not ratchet gain.

**Honest coverage statement (review high):** the **PRIMARY fix is FLP fix density**, which lives at the Android boundary (`FusedLocationSource`) and is **not** unit-tested (no Robolectric; mocking GMS adds a dependency). It is validated only by the §4 manual app-vs-Strava re-measure. The previously proposed `denseFixesAroundCurve` test is **relabeled** as a haversine-summation sanity check (it passes identically with old and new code and locks in *nothing* about the under-count fix), and is optional. No accumulator distance/gate logic changes this cycle, so there is intentionally no new distance unit test.

**Android boundary (FLP / SensorManager):** not unit-tested. Confinement and lifecycle verified manually (§4). Verify no Android type leaked into metrics: `grep -rE 'import android' app/src/main/java/com/localstrava/metrics/ app/src/test/java/com/localstrava/metrics/` must be empty after the change.

**Run gate:** `./gradlew testDebugUnitTest` green before each commit.

---

## 4. Risks / verify empirically (app + Strava simultaneously)

- **Primary validation:** re-run the same route with app and Strava together. Target: distance within ~5% of Strava. If still >5% short after FLP, the root cause was not solely fix density — investigate before any gate change.
- **Over-count guard (hard precondition):** confirm distance is **not now longer** than Strava and auto-pause still triggers when genuinely standing still. This check is a **hard precondition** that must pass before any future stationary/gap loosening is even considered.
- **Temporary logging** (debug level, once-per-run + periodic, no per-fix overhead): selected source (`FLP` vs `LocationManager`), achieved fix density, gap-duration distribution. Also log once that callbacks land on the main thread (`Looper.myLooper() == Looper.getMainLooper()`) for both location and sensor paths, to confirm confinement.
- **FLP cold start:** confirm run-start latency with `setWaitForAccurateLocation(true)` (~5–10 s) is acceptable, and that the `firstFixReceived` guard actually suppresses the "GPS signal lost" flash at start.
- **No-GMS device:** verify `LocationSourceFactory` falls back and the app still records (de-Googled device or forcing `flpUsable=false`).
- **Teardown:** verify `removeLocationUpdates` / `unregisterListener` are observed on stop (no leaked 1 Hz request / listener) across normal stop and abnormal teardown.
- **Barometer:** on a TYPE_PRESSURE device, verify gain is sane and matches expectation; on a sensorless device, verify clean GPS-altitude fallback with no double-count. Verify mid-run sensor dropout (staleness fallback) does not inject a false gain spike at the switch.

---

## 5. Non-goals / out of scope

- **No Kalman/EMA/path smoothing, no synthetic midpoint/bridge fixes, no distance banked across gaps** — these cut corners or manufacture distance and worsen under-count; explicitly forbidden.
- **No accumulator gate retuning this cycle** — `STATIONARY_SPEED_MPS`, `MAX_FIX_GAP_MS`, `MAX_ACCURACY_M`, `MAX_ACCURACY_HARD_M`, `MIN_DUP_M`, and teleport gates all **unchanged**. Distance correctness rests on FLP density. Any future retuning is a separate pass, gated on the §4 empirical re-measure and on adding over-count-direction tests first.
- **No change to `Fix.kt`** — barometer flows via a separate pure-Double channel, preserving the framework-free invariant.
- **No absolute-altitude / sea-level calibration / weather API / temperature compensation** — relative gain only; barometer gain is acknowledged as relative-only with a few metres of slow drift over a run.
- **No changes to save policy, persistence/serialization, UI, notifications, or auto-pause semantics** beyond the `LocationSourceFactory` construction line and the barometer lifecycle wiring.
- **No new test framework** (Robolectric/instrumented) — FLP/sensor wiring verified manually, not unit-tested.

---

## Review dispositions

- **Stationary `0.5→0.4` change — DROPPED** (over-correction lens, high; unsupported by data, risks over-count flip with zero regression coverage in the [0.40,0.50) leak band). Constants.kt stationary gate unchanged this cycle.
- **`MAX_FIX_GAP_MS 5000→7000` — DROPPED** (over-correction lens, medium; loosens a correctness gate prophylactically, enlarges worst-case bridged chord). Gap gate unchanged; genuine >5 s gaps should correctly break.
- **Barometer SensorEventListener not main-Looper-confined — FIXED** (correctness/threading + elevation lenses, blocker/high): mandated 4-arg `registerListener(..., Handler(context.mainLooper))`; called out as a hard requirement with a confinement assertion.
- **Datum mismatch on GPS↔barometer switch — FIXED** (correctness + elevation lenses, high): `setBarometerActive` clears `altBuffer` and resets `lastCommittedAlt=null` on every transition; added `switchingElevationChannelDoesNotInjectFalseGain` test.
- **Barometer cadence vs 1 Hz-tuned smoothing — FIXED** (elevation lens, high): decimate barometer to ~1 Hz in `BarometerSource` so existing smoothing constants stay valid.
- **Barometer must inherit pause/stationary gating — FIXED** (threading lens, medium): `onBarometerAltitude` early-returns on `manualPaused`/stationary; added two tests.
- **Barometer staleness with no `onUnavailable` — FIXED** (elevation lens, medium): added `BAROMETER_STALE_MS` fallback to GPS channel.
- **Teardown leaks FLP callback / off-thread sensor unregister — FIXED** (threading lens, medium): `stop()` added to `onDestroy`; sensor `stop()` runs synchronously on main thread in `handleStop`.
- **Broaden confinement statement beyond accumulator (buffer/state-holder) — FIXED** (threading lens, high): plan now states the main-Looper guarantee covers `buffer` and `TrackingStateHolder`.
- **GMS predicate too strict / transient-state degradation — FIXED** (threading lens, medium): accept `SERVICE_VERSION_UPDATE_REQUIRED`; documented one-shot-selection caveat.
- **Premature "GPS lost" flash at cold start — FIXED** (threading lens, medium): `firstFixReceived` guard suppresses `onLost()` before first fix.
- **play-services-location AD_ID / manifest-merger supply-chain — FIXED** (threading lens, low; build/scope lens nit): added merged-manifest inspection + `tools:node="remove"` for AD_ID to Step 0.
- **FLP batch delivery / "1 Hz guarantees gap assumptions" overstated — FIXED** (threading + build lenses, low): softened to "targets 1 Hz"; noted burst delivery and `dt<=0` guard.
- **Test inventory miscount (24→27) — FIXED** (build lens, low): corrected baseline to 27 (RunAccumulatorTest 16, GeoMathTest 4, FormatTest 7).
- **`denseFixesAroundCurve` mislabeled as under-count lock-in — FIXED** (build lens, high): relabeled as haversine sanity check; added explicit statement that the FLP primary fix has no unit coverage and is manual-only.
- **`gpsElevationSuppressedWhenBarometerActive` could be tautological — FIXED** (build lens, medium): test uses distinct magnitudes (GPS +10 m, barometer +3 m, assert gain == 3 m).
- **Public-surface changes not flagged — FIXED** (build lens, medium): added explicit hard-stop notes for `LocationSource` class→interface and the two new `RunAccumulator` methods, confirmed in-scope under Approach A.
- **Provisional/TODO-style Constants comment — FIXED** (build lens, nit): rationale comments only; no open action items in delivered code.
- **play-services-location 21.3.0 version sanity — ACKNOWLEDGED** (build lens, nit): kept 21.3.0 with 21.0.1 fallback note; no change needed.
- **Pressure→altitude formula precision / linearization suggestion — PARTIALLY ADOPTED** (elevation lens, low): kept the standard hypsometric formula in `tracking/` (deltas make it correct for gain), added the hPa-units assertion and the "never diff against GPS" documentation; did not switch to the local linearization (equivalent for gain, not worth the change).
- **Temperature/drift honesty note — ADOPTED** (elevation lens, nit): added relative-only/slow-drift caveat to §1 and §5.
- **Optional pure fix-density/gap-distribution testable seam — NOTED, NOT ADOPTED** (build lens, high suggestion): out of scope for this cycle; the manual re-measure plus debug logging covers density validation without adding a new pure abstraction. Flagged here for a possible future pass.
