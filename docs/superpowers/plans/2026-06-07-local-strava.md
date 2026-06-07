# Local Strava Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a minimal, local-only Android run tracker (sideloaded debug APK) that records GPS runs in the background and shows distance, pace, elevation, and the route on an OpenStreetMap map.

**Architecture:** Native Kotlin + Jetpack Compose. A framework-free pure-Kotlin `metrics/` core (the unit-test target) does all distance/pace/elevation filtering; a foreground `TrackingService` owns the `LocationManager` subscription, publishes live state via a singleton `StateFlow`, and persists points to Room incrementally; three Compose screens (History, Record, Detail) read Room/state. osmdroid renders the route on the Detail screen only.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, Compose (BOM 2024.12.01) + Material 3, Navigation-Compose, Room 2.6.1 (KSP1), osmdroid 6.1.20, JUnit4. minSdk 26 / target 35, JDK 21.

**Reference spec:** `docs/superpowers/specs/2026-06-07-local-strava-design.md` (v2). Review findings: `docs/superpowers/specs/2026-06-07-local-strava-review-findings.md`.

**Working directory:** `/home/earl/Documents/prog/local-strava` (already a git repo). All commands run from there. `adb` is at `~/Android/Sdk/platform-tools/adb`.

**Two deviations from the spec, made for first-build reliability (both noted in spec §2 as acceptable):**
1. **Room 2.6.1 with KSP1** (`ksp.useKSP2=false` in `gradle.properties`) instead of Room 2.7.x/KSP2 — the most battle-tested combo with Kotlin 2.0.21. If you later want KSP2, bump Room to 2.7.x and drop the flag.
2. **`android:allowBackup="false"` alone** (no custom `dataExtractionRules.xml`) — this fully disables cloud backup (the CRITICAL privacy finding). Device-to-device-transfer exclusion is deferred as future hardening to avoid a fragile XML schema on first build.

---

## File Structure

```
local-strava/
  settings.gradle.kts                    # repos + module include
  build.gradle.kts                       # root: declare plugins (apply false)
  gradle.properties                      # AndroidX, KSP1 flag, jvm args
  gradle/libs.versions.toml              # version catalog (single source of versions)
  gradle/wrapper/gradle-wrapper.{jar,properties}   # bootstrapped from cache → 8.10.2
  gradlew, gradlew.bat
  app/
    proguard-rules.pro                   # empty (release unused)
    build.gradle.kts                     # android{} + deps
    src/main/AndroidManifest.xml         # perms, FGS, hardened components
    src/main/res/values/strings.xml      # app_name + UI strings
    src/main/res/values/themes.xml       # Material3 theme stub
    src/main/res/mipmap-anydpi-v26/*     # adaptive launcher icon
    src/main/res/drawable/*              # icon glyph + background
    src/main/java/com/localstrava/
      LocalStravaApp.kt                  # Application: notif channel + osmdroid init
      MainActivity.kt                    # single activity, hosts Compose nav
      metrics/                           # PURE Kotlin (no Android imports) — test target
        Constants.kt                     # all tuning constants
        Fix.kt                           # pure GPS-sample DTO
        GeoMath.kt                       # haversine
        RunAccumulator.kt                # filtering + distance/pace/elevation; RunStats, AcceptedPoint
      data/
        Activity.kt                      # @Entity run summary
        TrackPoint.kt                    # @Entity per-point row
        RunDao.kt                        # queries
        RunDatabase.kt                   # @Database
        ActivityRepository.kt            # suspend writes / Flow reads + finalize/recover
      tracking/
        TrackingUiState.kt               # immutable live-state model + phase
        TrackingStateHolder.kt           # process singleton StateFlow (service writes, UI reads)
        LocationSource.kt                # LocationManager wrapper, Location→Fix, SecurityException
        TrackingService.kt               # foreground service + state machine + incremental flush
      ui/
        nav/AppNav.kt                    # NavHost (history/record/detail)
        theme/Theme.kt                   # Compose theme
        format/Format.kt                 # distance/pace/time formatting helpers
        history/HistoryScreen.kt, HistoryViewModel.kt
        record/RecordScreen.kt, RecordViewModel.kt
        detail/DetailScreen.kt, DetailViewModel.kt
    src/test/java/com/localstrava/metrics/
      GeoMathTest.kt
      RunAccumulatorTest.kt
```

Source of truth for behavior is the spec. When in doubt about a constant or rule, consult spec §5/§6.

**After every task: run the build/tests shown, confirm the expected output, then commit.** Do not proceed to the next task on a red build.

---

## Phase 0 — Scaffolding & first green build

No unit tests here (this is build config); the "test" is a successful Gradle build.

### Task 1: Bootstrap the Gradle wrapper + project settings

**Files:**
- Create: `settings.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Create (generated): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.{jar,properties}`

- [ ] **Step 1: Create `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "LocalStrava"
include(":app")
```

- [ ] **Step 2: Create `gradle.properties`**

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.caching=true
android.useAndroidX=true
kotlin.code.style=official
# Use KSP1 — most reliable with Room 2.6.1 + Kotlin 2.0.21
ksp.useKSP2=false
```

- [ ] **Step 3: Create `gradle/libs.versions.toml`**

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
coreKtx = "1.13.1"
lifecycle = "2.8.7"
activityCompose = "1.9.3"
composeBom = "2024.12.01"
navigationCompose = "2.8.5"
room = "2.6.1"
osmdroid = "6.1.20"
junit = "4.13.2"
coroutinesTest = "1.9.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
androidx-ui = { group = "androidx.compose.ui", name = "ui" }
androidx-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
androidx-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
androidx-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
androidx-material3 = { group = "androidx.compose.material3", name = "material3" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }
androidx-room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
androidx-room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
androidx-room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }
osmdroid-android = { group = "org.osmdroid", name = "osmdroid-android", version.ref = "osmdroid" }
junit = { group = "junit", name = "junit", version.ref = "junit" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutinesTest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 4: Generate the wrapper from the cached Gradle distribution** (no network / no `gradle` on PATH needed)

Run:
```bash
GRADLE_BIN=$(echo ~/.gradle/wrapper/dists/gradle-8.10.2-bin/*/gradle-8.10.2/bin/gradle)
"$GRADLE_BIN" wrapper --gradle-version 8.10.2 --distribution-type bin
```
Expected: creates `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, and `gradle/wrapper/gradle-wrapper.properties` with `distributionUrl=...gradle-8.10.2-bin.zip`.

- [ ] **Step 5: Verify the wrapper runs offline against the cached distribution**

Run: `./gradlew --version`
Expected: prints `Gradle 8.10.2` and `JVM: 21.0.11`.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts gradle.properties gradle/ gradlew gradlew.bat
git commit -m "build: bootstrap gradle wrapper 8.10.2 + version catalog"
```

### Task 2: Root + app Gradle build files

**Files:**
- Create: `build.gradle.kts` (root), `app/build.gradle.kts`, `app/proguard-rules.pro`

- [ ] **Step 1: Create root `build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 2: Create `app/proguard-rules.pro`** (empty placeholder; release build unused)

```proguard
# Release build is not used in v1 (debug-signed APK). Intentionally empty.
```

- [ ] **Step 3: Create `app/build.gradle.kts`**

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.localstrava"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localstrava"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.osmdroid.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    debugImplementation(libs.androidx.ui.tooling)
}
```

- [ ] **Step 4: Verify Gradle can configure the project**

Run: `./gradlew :app:tasks --offline 2>&1 | head -20` (downloads AGP/Kotlin plugins on first run — drop `--offline` if they aren't cached yet: `./gradlew :app:tasks`)
Expected: configures without script errors and lists tasks (e.g. `assembleDebug`). A complaint about a missing `AndroidManifest.xml` is fine at this stage if it appears; a Kotlin-DSL script error is not.

- [ ] **Step 5: Commit**

```bash
git add build.gradle.kts app/build.gradle.kts app/proguard-rules.pro
git commit -m "build: add root + app gradle config"
```

### Task 3: Manifest, resources, Application, and a minimal Activity (first APK)

**Files:**
- Create: `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/java/com/localstrava/LocalStravaApp.kt`, `app/src/main/java/com/localstrava/MainActivity.kt`

> The adaptive launcher icon is added in Task 19; for now the build uses the default system icon (no `android:icon`), which is fine for a debug APK.

- [ ] **Step 1: Create `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">Local Strava</string>
</resources>
```

- [ ] **Step 2: Create `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.LocalStrava" parent="android:Theme.Material.NoActionBar" />
</resources>
```

- [ ] **Step 3: Create `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <uses-feature
        android:name="android.hardware.location.gps"
        android:required="true" />

    <application
        android:name=".LocalStravaApp"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.LocalStrava"
        android:usesCleartextTraffic="false">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".tracking.TrackingService"
            android:exported="false"
            android:foregroundServiceType="location" />
    </application>
</manifest>
```

> Note: the `<service>` references `TrackingService` (created in Task 12). The build resolves manifest class names lazily, but if a manifest-merger/lint check fails on the missing class before Task 12, temporarily comment out the `<service>` block and restore it in Task 12. Verify this at Step 6.

- [ ] **Step 4: Create `app/src/main/java/com/localstrava/LocalStravaApp.kt`**

```kotlin
package com.localstrava

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class LocalStravaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        createTrackingChannel()
        configureOsmdroid()
    }

    private fun createTrackingChannel() {
        // minSdk 26 → NotificationChannel always available, no version guard.
        val channel = NotificationChannel(
            TRACKING_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Ongoing run recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun configureOsmdroid() {
        // Must run before any MapView is created. Pin cache to app-private storage,
        // and set a non-default User-Agent (the default "osmdroid" is banned by OSM).
        val conf = Configuration.getInstance()
        conf.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        conf.userAgentValue = BuildConfig.APPLICATION_ID
        val base = File(cacheDir, "osmdroid")
        conf.osmdroidBasePath = base
        conf.osmdroidTileCache = File(base, "tiles")
    }

    companion object {
        const val TRACKING_CHANNEL_ID = "tracking"
    }
}
```

- [ ] **Step 5: Create `app/src/main/java/com/localstrava/MainActivity.kt`** (temporary placeholder UI; replaced in Task 14)

```kotlin
package com.localstrava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { Text("Local Strava") }
            }
        }
    }
}
```

- [ ] **Step 6: Build the debug APK (first green build — proves the whole toolchain resolves)**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`. (First run downloads AGP/Compose/osmdroid — needs network.) If the manifest `<service>` causes a class-not-found merge error, comment it out per Step 3's note and re-run.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res app/src/main/java/com/localstrava/LocalStravaApp.kt app/src/main/java/com/localstrava/MainActivity.kt
git commit -m "feat: minimal app scaffold builds to a debug APK"
```

---

## Phase 1 — Metrics core (pure Kotlin, TDD)

This is the only code with real unit tests. Everything here lives in `metrics/` and imports **no** Android types, so it runs on the plain JVM with `./gradlew :app:testDebugUnitTest`.

### Task 4: GeoMath (haversine) — TDD

**Files:**
- Create: `app/src/main/java/com/localstrava/metrics/Constants.kt`
- Create: `app/src/main/java/com/localstrava/metrics/GeoMath.kt`
- Test: `app/src/test/java/com/localstrava/metrics/GeoMathTest.kt`

- [ ] **Step 1: Create `Constants.kt`** (all tuning constants in one place — referenced throughout Phase 1)

```kotlin
package com.localstrava.metrics

object Constants {
    const val EARTH_RADIUS_M = 6_371_000.0

    // Accuracy gate (metres)
    const val MAX_ACCURACY_M = 35.0f
    const val MAX_ACCURACY_HARD_M = 40.0f
    const val MAX_ACCURACY_TOLERATE_FIXES = 3

    // Motion / filtering
    const val STATIONARY_SPEED_MPS = 0.5
    const val MIN_DUP_M = 1.0
    const val MAX_PLAUSIBLE_SPEED_MPS = 12.0
    const val MAX_JUMP_M = 200.0
    const val MAX_TELEPORT_REJECTS = 3
    const val MAX_FIX_GAP_MS = 5_000L

    // Pace
    const val CURRENT_PACE_WINDOW_MS = 20_000L
    const val CURRENT_PACE_MIN_DISTANCE_M = 3.0
    const val MIN_PACE_DISTANCE_M = 10.0

    // Elevation
    const val ELEV_VACC_MAX_M = 6.0f
    const val ELEV_THRESHOLD_M = 1.5
    const val ELEV_SMOOTH_WINDOW = 5

    // Save policy
    const val MIN_SAVE_M = 10.0
    const val MIN_SAVE_POINTS = 2
}
```

- [ ] **Step 2: Write the failing test `GeoMathTest.kt`**

```kotlin
package com.localstrava.metrics

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {
    @Test fun zeroDistanceForSamePoint() {
        assertEquals(0.0, GeoMath.haversineMeters(45.0, 8.0, 45.0, 8.0), 1e-6)
    }

    @Test fun oneDegreeLatitudeIsAbout111Km() {
        // 1° of latitude = R * π/180 = 111194.93 m
        val d = GeoMath.haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.93, d, 1.0)
    }

    @Test fun shortRunningSegmentAboutThreeMetres() {
        // 0.000027° latitude ≈ 3.00 m
        val d = GeoMath.haversineMeters(0.0, 0.0, 0.000027, 0.0)
        assertEquals(3.00, d, 0.05)
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.metrics.GeoMathTest"`
Expected: FAIL — `GeoMath` is unresolved / does not compile.

- [ ] **Step 4: Implement `GeoMath.kt`**

```kotlin
package com.localstrava.metrics

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoMath {
    /** Great-circle distance in metres between two WGS84 lat/lon points. */
    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        return Constants.EARTH_RADIUS_M * 2 * atan2(sqrt(a), sqrt(1 - a))
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.metrics.GeoMathTest"`
Expected: PASS (3 tests).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/localstrava/metrics/Constants.kt app/src/main/java/com/localstrava/metrics/GeoMath.kt app/src/test/java/com/localstrava/metrics/GeoMathTest.kt
git commit -m "feat(metrics): haversine distance + tuning constants"
```

### Task 5: The pure `Fix` DTO

**Files:**
- Create: `app/src/main/java/com/localstrava/metrics/Fix.kt`

This is a plain data class (no logic → no separate test; it's exercised by `RunAccumulatorTest`).

- [ ] **Step 1: Create `Fix.kt`**

```kotlin
package com.localstrava.metrics

/**
 * A pure, framework-free GPS sample. `LocationSource` maps android.location.Location
 * into this so the metrics core never imports Android types (keeps it JVM-unit-testable).
 */
data class Fix(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timeMs: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val verticalAccuracyMeters: Float?,
)
```

- [ ] **Step 2: Verify it compiles** (no test yet)

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/localstrava/metrics/Fix.kt
git commit -m "feat(metrics): pure Fix DTO"
```

### Task 6: RunAccumulator (filtering + distance/pace/elevation) — TDD

This is the heart of the app. The test suite below pins every filtering rule from spec §5. Implement the class so all tests pass.

**Files:**
- Create: `app/src/main/java/com/localstrava/metrics/RunAccumulator.kt`
- Test: `app/src/test/java/com/localstrava/metrics/RunAccumulatorTest.kt`

- [ ] **Step 1: Write the full failing test `RunAccumulatorTest.kt`**

```kotlin
package com.localstrava.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAccumulatorTest {

    // ~3 m north per step at the equator.
    private val stepLat = 0.000027
    private fun fix(
        lat: Double, lon: Double = 0.0, t: Long,
        acc: Float? = 5f, alt: Double? = null, speed: Float? = null, vacc: Float? = 3f,
    ) = Fix(lat, lon, alt, t, acc, speed, vacc)

    @Test fun firstFixSetsAnchorWithNoDistance() {
        val acc = RunAccumulator()
        val p = acc.update(fix(0.0, t = 0))
        assertNotNull(p)
        assertFalse(p!!.segmentBreak)
        val s = acc.snapshot(0)
        assertEquals(0.0, s.distanceMeters, 1e-9)
        assertEquals(1, s.pointCount)
    }

    @Test fun twoMovingFixesAccumulateDistanceAndMovingTime() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000))
        val s = acc.snapshot(1000)
        assertEquals(3.0, s.distanceMeters, 0.1)
        assertEquals(1000L, s.movingTimeMs)
    }

    @Test fun stationaryFixesAddNoDistanceOrTime() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // device-reported speed 0 → stationary, regardless of tiny jitter
        acc.update(fix(0.0000005, t = 1000, speed = 0.0f))
        acc.update(fix(0.0000005, t = 2000, speed = 0.0f))
        val s = acc.snapshot(2000)
        assertEquals(0.0, s.distanceMeters, 1e-6)
        assertEquals(0L, s.movingTimeMs)
    }

    @Test fun teleportSpikeIsRejectedAndAnchorKept() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // 0.01° ≈ 1.1 km in 1 s → teleport, rejected, not stored
        val spike = acc.update(fix(0.01, t = 1000))
        assertNull(spike)
        // next real fix is measured from the ORIGINAL anchor (3 m), not the spike
        acc.update(fix(stepLat, t = 2000))
        val s = acc.snapshot(2000)
        assertEquals(3.0, s.distanceMeters, 0.2)
    }

    @Test fun timeGapStartsNewSegmentWithoutBridgingDistance() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        // 10 s gap then a fix ~30 m away: do NOT add 30 m; start a new segment
        val p = acc.update(fix(stepLat * 10, t = 10_000))
        assertNotNull(p)
        assertTrue(p!!.segmentBreak)
        val s = acc.snapshot(10_000)
        assertEquals(0.0, s.distanceMeters, 1e-6)
    }

    @Test fun nonPositiveDeltaTimeRejected() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 1000))
        assertNull(acc.update(fix(stepLat, t = 1000))) // duplicate timestamp
        assertNull(acc.update(fix(stepLat, t = 500)))  // backwards
        assertEquals(0.0, acc.snapshot(1000).distanceMeters, 1e-9)
    }

    @Test fun nullAccuracyRejected() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        assertNull(acc.update(fix(stepLat, t = 1000, acc = null)))
        assertEquals(0.0, acc.snapshot(1000).distanceMeters, 1e-9)
    }

    @Test fun manualPauseExcludesTimeAndDistanceThenResumeBreaks() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000))          // 3 m
        acc.setManualPaused(true)
        assertNull(acc.update(fix(stepLat * 2, t = 2000)))  // ignored while paused
        acc.setManualPaused(false)
        val resume = acc.update(fix(stepLat * 3, t = 3000)) // first fix after resume
        assertNotNull(resume)
        assertTrue(resume!!.segmentBreak)
        val s = acc.snapshot(3000)
        // only the first 3 m counted; the paused movement and the resume bridge are excluded
        assertEquals(3.0, s.distanceMeters, 0.2)
        assertEquals(1000L, s.movingTimeMs)
    }

    @Test fun elevationGainCountsSustainedClimbAboveThreshold() {
        val acc = RunAccumulator()
        // climb 100 → 105 m over good-vertical-accuracy fixes; median-smoothed gain ≈ 5 m
        val alts = listOf(100.0, 100.0, 101.0, 102.0, 103.0, 104.0, 105.0, 105.0, 105.0)
        alts.forEachIndexed { i, a -> acc.update(fix(stepLat * i, t = i * 1000L, alt = a)) }
        val s = acc.snapshot(alts.size * 1000L)
        assertEquals(5.0, s.elevationGainMeters, 1.5)
    }

    @Test fun elevationNoiseBelowThresholdProducesNoGain() {
        val acc = RunAccumulator()
        val alts = listOf(100.0, 100.5, 99.5, 100.5, 99.5, 100.5, 99.5, 100.0)
        alts.forEachIndexed { i, a -> acc.update(fix(stepLat * i, t = i * 1000L, alt = a)) }
        assertEquals(0.0, acc.snapshot(alts.size * 1000L).elevationGainMeters, 0.5)
    }

    @Test fun altitudeWithPoorVerticalAccuracyIgnored() {
        val acc = RunAccumulator()
        val alts = listOf(100.0, 110.0, 120.0, 130.0, 140.0) // big climb...
        alts.forEachIndexed { i, a ->
            acc.update(fix(stepLat * i, t = i * 1000L, alt = a, vacc = 50f)) // ...but unreliable
        }
        assertEquals(0.0, acc.snapshot(alts.size * 1000L).elevationGainMeters, 0.01)
    }

    @Test fun averagePaceIsNullBelowMinDistance() {
        val acc = RunAccumulator()
        acc.update(fix(0.0, t = 0))
        acc.update(fix(stepLat, t = 1000)) // only 3 m < MIN_PACE_DISTANCE_M (10)
        assertNull(acc.snapshot(1000).avgPaceSecPerKm)
    }

    @Test fun averagePaceAtThreeMetresPerSecondIsAbout333() {
        val acc = RunAccumulator()
        // 10 steps of 3 m at 1 Hz → 30 m, 10 s → 3 m/s → 1000/3 ≈ 333 s/km
        for (i in 0..10) acc.update(fix(stepLat * i, t = i * 1000L))
        val s = acc.snapshot(10_000)
        assertEquals(30.0, s.distanceMeters, 1.0)
        assertEquals(333.0, s.avgPaceSecPerKm!!, 15.0)
    }

    @Test fun currentPaceIsNullWhenStopped() {
        val acc = RunAccumulator()
        for (i in 0..10) acc.update(fix(stepLat * i, t = i * 1000L))
        // 40 s later with no new movement: window is empty → current pace undefined
        assertNull(acc.snapshot(50_000).currentPaceSecPerKm)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.metrics.RunAccumulatorTest"`
Expected: FAIL — `RunAccumulator`, `RunStats`, `AcceptedPoint` unresolved.

- [ ] **Step 3: Implement `RunAccumulator.kt`** (full)

```kotlin
package com.localstrava.metrics

/** What the service should persist for an accepted fix. */
data class AcceptedPoint(val fix: Fix, val segmentBreak: Boolean)

/** Immutable snapshot of live run metrics. Pace values are null when undefined ("--"). */
data class RunStats(
    val distanceMeters: Double,
    val movingTimeMs: Long,
    val elevationGainMeters: Double,
    val currentPaceSecPerKm: Double?,
    val avgPaceSecPerKm: Double?,
    val pointCount: Int,
)

/**
 * Stateful but framework-free run accumulator. Feed it [Fix]es in time order via [update];
 * read live metrics via [snapshot]. All filtering rules live here (spec §5).
 *
 * Not thread-safe: the service confines all calls to a single thread (spec §3).
 */
class RunAccumulator {

    private data class Anchor(val lat: Double, val lon: Double, val timeMs: Long)
    private data class WindowSample(val lat: Double, val lon: Double, val timeMs: Long, val segmentBreak: Boolean)

    private var anchor: Anchor? = null
    private var distance = 0.0
    private var movingTime = 0L
    private var points = 0

    private var manualPaused = false
    private var pendingResumeBreak = false
    private var pendingGapBreak = false
    private var overAccuracyCount = 0
    private var teleportRejects = 0

    private val window = ArrayDeque<WindowSample>()

    // Elevation
    private val altBuffer = ArrayDeque<Double>()
    private var lastCommittedAlt: Double? = null
    private var elevationGain = 0.0

    fun setManualPaused(paused: Boolean) {
        if (paused && !manualPaused) {
            manualPaused = true
        } else if (!paused && manualPaused) {
            manualPaused = false
            pendingResumeBreak = true
        }
    }

    /** Process one fix. Returns the point to persist, or null if rejected/not stored. */
    fun update(fix: Fix): AcceptedPoint? {
        if (manualPaused) return null

        val acc = fix.accuracyMeters ?: return null
        if (acc > Constants.MAX_ACCURACY_M) {
            overAccuracyCount++
            if (acc > Constants.MAX_ACCURACY_HARD_M ||
                overAccuracyCount > Constants.MAX_ACCURACY_TOLERATE_FIXES
            ) {
                pendingGapBreak = true // sustained bad signal → treat like a gap
            }
            return null
        }
        overAccuracyCount = 0

        val a = anchor
        if (a == null) {
            return startSegment(fix, segmentBreak = false)
        }
        if (pendingResumeBreak) {
            pendingResumeBreak = false
            return startSegment(fix, segmentBreak = true)
        }

        val dt = fix.timeMs - a.timeMs
        if (dt <= 0L) return null

        if (dt > Constants.MAX_FIX_GAP_MS || pendingGapBreak) {
            pendingGapBreak = false
            return startSegment(fix, segmentBreak = true)
        }

        val dist = GeoMath.haversineMeters(a.lat, a.lon, fix.latitude, fix.longitude)
        val dtSec = dt / 1000.0

        // Teleport spike: reject, KEEP the old anchor (don't measure from a bad fix).
        if (dist > Constants.MAX_JUMP_M || dist > Constants.MAX_PLAUSIBLE_SPEED_MPS * dtSec) {
            teleportRejects++
            if (teleportRejects > Constants.MAX_TELEPORT_REJECTS) {
                teleportRejects = 0
                return startSegment(fix, segmentBreak = true) // GPS genuinely relocated
            }
            return null
        }
        teleportRejects = 0

        // Stationary: advance anchor (so the next dt stays small) but add no distance/time.
        val speed = fix.speedMps?.toDouble() ?: (dist / dtSec)
        if (speed < Constants.STATIONARY_SPEED_MPS) {
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        if (dist < Constants.MIN_DUP_M) {
            anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
            return null
        }

        // Moving fix.
        distance += dist
        movingTime += dt
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        updateElevation(fix)
        addToWindow(fix, segmentBreak = false)
        points++
        return AcceptedPoint(fix, segmentBreak = false)
    }

    private fun startSegment(fix: Fix, segmentBreak: Boolean): AcceptedPoint {
        anchor = Anchor(fix.latitude, fix.longitude, fix.timeMs)
        teleportRejects = 0
        updateElevation(fix)
        addToWindow(fix, segmentBreak)
        points++
        return AcceptedPoint(fix, segmentBreak)
    }

    private fun updateElevation(fix: Fix) {
        val alt = fix.altitudeMeters ?: return
        val vacc = fix.verticalAccuracyMeters ?: return
        if (vacc > Constants.ELEV_VACC_MAX_M) return

        altBuffer.addLast(alt)
        while (altBuffer.size > Constants.ELEV_SMOOTH_WINDOW) altBuffer.removeFirst()
        val smoothed = altBuffer.sorted()[altBuffer.size / 2] // median of last ≤5

        val last = lastCommittedAlt
        if (last == null) {
            lastCommittedAlt = smoothed
        } else {
            val diff = smoothed - last
            if (diff >= Constants.ELEV_THRESHOLD_M) {
                elevationGain += diff
                lastCommittedAlt = smoothed
            } else if (smoothed < last) {
                lastCommittedAlt = smoothed // ratchet down so future climbs count from the low
            }
        }
    }

    private fun addToWindow(fix: Fix, segmentBreak: Boolean) {
        window.addLast(WindowSample(fix.latitude, fix.longitude, fix.timeMs, segmentBreak))
    }

    fun snapshot(nowMs: Long): RunStats {
        val avgPace =
            if (distance >= Constants.MIN_PACE_DISTANCE_M && movingTime > 0L)
                movingTime.toDouble() / distance
            else null
        return RunStats(
            distanceMeters = distance,
            movingTimeMs = movingTime,
            elevationGainMeters = elevationGain,
            currentPaceSecPerKm = currentPace(nowMs),
            avgPaceSecPerKm = avgPace,
            pointCount = points,
        )
    }

    private fun currentPace(nowMs: Long): Double? {
        val cutoff = nowMs - Constants.CURRENT_PACE_WINDOW_MS
        while (window.isNotEmpty() && window.first().timeMs < cutoff) window.removeFirst()
        if (window.size < 2) return null
        var windowDistance = 0.0
        val it = window.iterator()
        var prev = it.next()
        while (it.hasNext()) {
            val cur = it.next()
            if (!cur.segmentBreak) {
                windowDistance += GeoMath.haversineMeters(prev.lat, prev.lon, cur.lat, cur.lon)
            }
            prev = cur
        }
        val windowTime = window.last().timeMs - window.first().timeMs
        if (windowDistance < Constants.CURRENT_PACE_MIN_DISTANCE_M || windowTime <= 0L) return null
        return windowTime.toDouble() / windowDistance
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.metrics.RunAccumulatorTest"`
Expected: PASS (all 14 tests). If a tolerance is marginally off on your machine's math, widen the specific `assertEquals` delta — do **not** change a constant to chase a test (that would change app behavior). Flag any genuine logic mismatch instead of editing the test's intent.

- [ ] **Step 5: Run the full metrics suite**

Run: `./gradlew :app:testDebugUnitTest`
Expected: PASS (GeoMath + RunAccumulator).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/localstrava/metrics/RunAccumulator.kt app/src/test/java/com/localstrava/metrics/RunAccumulatorTest.kt
git commit -m "feat(metrics): RunAccumulator filtering + distance/pace/elevation (TDD)"
```

---

## Phase 2 — Data layer (Room)

No JVM unit tests (Room DAO tests need a device/Robolectric, out of scope per spec §10). Verify each task with a compile/KSP build.

### Task 7: Room entities

**Files:**
- Create: `app/src/main/java/com/localstrava/data/Activity.kt`
- Create: `app/src/main/java/com/localstrava/data/TrackPoint.kt`

- [ ] **Step 1: Create `Activity.kt`**

```kotlin
package com.localstrava.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "activities")
data class Activity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,        // null = in-progress (crash-recoverable)
    val movingTimeMs: Long,
    val distanceMeters: Double,
    val elevationGainMeters: Double,
)
```

- [ ] **Step 2: Create `TrackPoint.kt`**

```kotlin
package com.localstrava.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "track_points",
    foreignKeys = [ForeignKey(
        entity = Activity::class,
        parentColumns = ["id"],
        childColumns = ["activityId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("activityId")],
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val activityId: Long,
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double?,
    val timestampEpochMs: Long,
    val accuracyMeters: Float?,
    val speedMps: Float?,
    val segmentBreak: Boolean,
)
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/localstrava/data/Activity.kt app/src/main/java/com/localstrava/data/TrackPoint.kt
git commit -m "feat(data): Room entities for Activity + TrackPoint"
```

### Task 8: DAO + Database

**Files:**
- Create: `app/src/main/java/com/localstrava/data/RunDao.kt`
- Create: `app/src/main/java/com/localstrava/data/RunDatabase.kt`

- [ ] **Step 1: Create `RunDao.kt`**

```kotlin
package com.localstrava.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RunDao {
    @Insert suspend fun insertActivity(activity: Activity): Long
    @Update suspend fun updateActivity(activity: Activity)
    @Insert suspend fun insertPoints(points: List<TrackPoint>)

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getActivity(id: Long): Activity?

    @Query("SELECT * FROM activities WHERE endedAtEpochMs IS NOT NULL ORDER BY startedAtEpochMs DESC")
    fun observeCompletedActivities(): Flow<List<Activity>>

    @Query("SELECT * FROM activities WHERE endedAtEpochMs IS NULL")
    suspend fun getInProgressActivities(): List<Activity>

    @Query("SELECT * FROM track_points WHERE activityId = :activityId ORDER BY timestampEpochMs ASC")
    suspend fun getPoints(activityId: Long): List<TrackPoint>

    @Query("DELETE FROM activities WHERE id = :id")
    suspend fun deleteActivity(id: Long)
}
```

- [ ] **Step 2: Create `RunDatabase.kt`**

```kotlin
package com.localstrava.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [Activity::class, TrackPoint::class], version = 1, exportSchema = false)
abstract class RunDatabase : RoomDatabase() {
    abstract fun runDao(): RunDao

    companion object {
        @Volatile private var instance: RunDatabase? = null
        fun get(context: Context): RunDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    RunDatabase::class.java,
                    "local-strava.db",
                ).build().also { instance = it }
            }
    }
}
```

- [ ] **Step 3: Verify KSP generates the Room code**

Run: `./gradlew :app:kspDebugKotlin`
Expected: `BUILD SUCCESSFUL` and generated `RunDao_Impl` / `RunDatabase_Impl` under `app/build/generated/ksp/`. If you hit a `NoSuchMethodError` or KSP processing error, confirm `ksp.useKSP2=false` is in `gradle.properties` (Task 1) — that is the documented fix.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/localstrava/data/RunDao.kt app/src/main/java/com/localstrava/data/RunDatabase.kt
git commit -m "feat(data): RunDao + RunDatabase (Room, KSP1)"
```

### Task 9: ActivityRepository

**Files:**
- Create: `app/src/main/java/com/localstrava/data/ActivityRepository.kt`

The repository keeps the `Activity` row's summary current on each incremental flush, so crash recovery only needs to stamp `endedAt` — no replay of the accumulator.

- [ ] **Step 1: Create `ActivityRepository.kt`**

```kotlin
package com.localstrava.data

import com.localstrava.metrics.Constants
import com.localstrava.metrics.RunStats
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dao: RunDao) {

    fun observeCompleted(): Flow<List<Activity>> = dao.observeCompletedActivities()

    suspend fun getActivity(id: Long): Activity? = dao.getActivity(id)
    suspend fun getPoints(id: Long): List<TrackPoint> = dao.getPoints(id)

    suspend fun startActivity(startedAtEpochMs: Long): Long =
        dao.insertActivity(
            Activity(
                startedAtEpochMs = startedAtEpochMs,
                endedAtEpochMs = null,
                movingTimeMs = 0L,
                distanceMeters = 0.0,
                elevationGainMeters = 0.0,
            )
        )

    suspend fun appendPoints(points: List<TrackPoint>) {
        if (points.isNotEmpty()) dao.insertPoints(points)
    }

    /** Keep the in-progress summary fresh (called on each flush). */
    suspend fun updateProgress(id: Long, startedAtEpochMs: Long, stats: RunStats) {
        dao.updateActivity(
            Activity(id, startedAtEpochMs, null, stats.movingTimeMs, stats.distanceMeters, stats.elevationGainMeters)
        )
    }

    suspend fun finalizeActivity(id: Long, startedAtEpochMs: Long, endedAtEpochMs: Long, stats: RunStats) {
        dao.updateActivity(
            Activity(id, startedAtEpochMs, endedAtEpochMs, stats.movingTimeMs, stats.distanceMeters, stats.elevationGainMeters)
        )
    }

    suspend fun delete(id: Long) = dao.deleteActivity(id)

    /** Finalize or discard runs left in-progress by a crash/kill (spec §3.9). */
    suspend fun recoverInProgress() {
        for (a in dao.getInProgressActivities()) {
            val points = dao.getPoints(a.id)
            if (points.size < Constants.MIN_SAVE_POINTS || a.distanceMeters < Constants.MIN_SAVE_M) {
                dao.deleteActivity(a.id)
            } else {
                dao.updateActivity(a.copy(endedAtEpochMs = points.last().timestampEpochMs))
            }
        }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/localstrava/data/ActivityRepository.kt
git commit -m "feat(data): ActivityRepository with incremental writes + crash recovery"
```

---

## Phase 3 — Tracking (foreground service + live state)

### Task 10: Live-state model + state holder

**Files:**
- Create: `app/src/main/java/com/localstrava/tracking/TrackingUiState.kt`
- Create: `app/src/main/java/com/localstrava/tracking/TrackingStateHolder.kt`

- [ ] **Step 1: Create `TrackingUiState.kt`**

```kotlin
package com.localstrava.tracking

sealed interface TrackPhase {
    data object Idle : TrackPhase
    data object Active : TrackPhase
    data object Paused : TrackPhase
    data class Finished(val activityId: Long) : TrackPhase
    data object Discarded : TrackPhase
}

data class TrackingUiState(
    val phase: TrackPhase = TrackPhase.Idle,
    val locationLost: Boolean = false,
    val distanceMeters: Double = 0.0,
    val movingTimeMs: Long = 0L,
    val elevationGainMeters: Double = 0.0,
    val currentPaceSecPerKm: Double? = null,
    val avgPaceSecPerKm: Double? = null,
    val pointCount: Int = 0,
) {
    val isActive: Boolean get() = phase is TrackPhase.Active || phase is TrackPhase.Paused
}
```

- [ ] **Step 2: Create `TrackingStateHolder.kt`**

```kotlin
package com.localstrava.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Process singleton bridging the foreground service (the SOLE writer) and the UI (readers).
 * The terminal result is RETAINED in the phase (Finished/Discarded), not a transient event,
 * so the UI cannot miss it if its screen was recreated. UI calls [consumeTerminal] once handled.
 */
object TrackingStateHolder {
    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    fun set(newState: TrackingUiState) { _state.value = newState }
    fun update(block: (TrackingUiState) -> TrackingUiState) { _state.update(block) }

    fun consumeTerminal() {
        _state.update {
            if (it.phase is TrackPhase.Finished || it.phase is TrackPhase.Discarded) TrackingUiState()
            else it
        }
    }
}
```

- [ ] **Step 3: Verify compile + commit**

Run: `./gradlew :app:compileDebugKotlin`  → Expected: `BUILD SUCCESSFUL`.
```bash
git add app/src/main/java/com/localstrava/tracking/TrackingUiState.kt app/src/main/java/com/localstrava/tracking/TrackingStateHolder.kt
git commit -m "feat(tracking): live-state model + singleton state holder"
```

### Task 11: Formatting helpers — TDD

Pure formatting used by both the notification and the UI (DRY). JVM-testable.

**Files:**
- Create: `app/src/main/java/com/localstrava/format/Format.kt`
- Test: `app/src/test/java/com/localstrava/format/FormatTest.kt`

> Formatting uses `Locale.US` (dot decimals) for deterministic output and to avoid comma-decimal bugs. This is the stated locale policy for v1 (spec §7 noted locale must be explicit).

- [ ] **Step 1: Write the failing test `FormatTest.kt`**

```kotlin
package com.localstrava.format

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test fun distanceBelowKilometreInMetres() = assertEquals("850 m", Format.distance(850.0))
    @Test fun distanceAboveKilometre() = assertEquals("1.23 km", Format.distance(1234.0))
    @Test fun durationUnderHour() = assertEquals("5:30", Format.duration(330_000))
    @Test fun durationOverHour() = assertEquals("1:02:03", Format.duration(3_723_000))
    @Test fun paceNullShowsDashes() = assertEquals("--", Format.pace(null))
    @Test fun paceFormatsMinutesPerKm() = assertEquals("5:30 /km", Format.pace(330.0))
    @Test fun elevationRoundsToMetres() = assertEquals("123 m", Format.elevation(123.4))
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.format.FormatTest"`
Expected: FAIL — `Format` unresolved.

- [ ] **Step 3: Implement `Format.kt`**

```kotlin
package com.localstrava.format

import java.util.Locale

object Format {
    fun distance(meters: Double): String =
        if (meters >= 1000.0) String.format(Locale.US, "%.2f km", meters / 1000.0)
        else String.format(Locale.US, "%d m", meters.toInt())

    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun pace(secPerKm: Double?): String {
        if (secPerKm == null || secPerKm.isNaN() || secPerKm.isInfinite() || secPerKm <= 0.0) return "--"
        val total = secPerKm.toInt()
        return String.format(Locale.US, "%d:%02d /km", total / 60, total % 60)
    }

    fun elevation(meters: Double): String = String.format(Locale.US, "%d m", meters.toInt())
}
```

- [ ] **Step 4: Run to verify pass + commit**

Run: `./gradlew :app:testDebugUnitTest --tests "com.localstrava.format.FormatTest"` → Expected: PASS (7).
```bash
git add app/src/main/java/com/localstrava/format/Format.kt app/src/test/java/com/localstrava/format/FormatTest.kt
git commit -m "feat(format): distance/pace/duration/elevation formatting (TDD)"
```

### Task 12: LocationSource (LocationManager → Fix)

**Files:**
- Create: `app/src/main/java/com/localstrava/tracking/LocationSource.kt`

- [ ] **Step 1: Create `LocationSource.kt`**

```kotlin
package com.localstrava.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import com.localstrava.metrics.Fix

/**
 * Thin wrapper over the framework LocationManager (GPS provider). Maps Location → Fix and
 * delivers callbacks on the main Looper (the single thread the accumulator is confined to).
 * Surfaces permission/provider loss via [onLost].
 */
class LocationSource(
    private val context: Context,
    private val onFix: (Fix) -> Unit,
    private val onLost: () -> Unit,
) {
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    @SuppressLint("MissingPermission") // caller guarantees ACCESS_FINE_LOCATION before start()
    fun start() {
        if (listener != null) return
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location.toFix())
            override fun onProviderDisabled(provider: String) = onLost()
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("Deprecated in API 29") @Suppress("DEPRECATION")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        listener = l
        try {
            manager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 1000L, 0f, l, context.mainLooper,
            )
        } catch (e: SecurityException) {
            listener = null
            onLost()
        }
    }

    fun stop() {
        listener?.let { manager.removeUpdates(it) }
        listener = null
    }

    private fun Location.toFix(): Fix = Fix(
        latitude = latitude,
        longitude = longitude,
        altitudeMeters = if (hasAltitude()) altitude else null,
        timeMs = time,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        speedMps = if (hasSpeed()) speed else null,
        verticalAccuracyMeters = if (hasVerticalAccuracy()) verticalAccuracyMeters else null,
    )
}
```

- [ ] **Step 2: Verify compile + commit**

Run: `./gradlew :app:compileDebugKotlin` → Expected: `BUILD SUCCESSFUL`.
```bash
git add app/src/main/java/com/localstrava/tracking/LocationSource.kt
git commit -m "feat(tracking): LocationSource wrapping LocationManager"
```

### Task 13: TrackingService (foreground service + state machine + incremental flush)

**Files:**
- Create: `app/src/main/java/com/localstrava/tracking/TrackingService.kt`

This implements the spec §6 state machine, the FGS start contract (startForeground first), incremental persistence, auto-pause on location loss, and the immutable notification actions.

- [ ] **Step 1: Create `TrackingService.kt`**

```kotlin
package com.localstrava.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.localstrava.LocalStravaApp
import com.localstrava.MainActivity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import com.localstrava.data.TrackPoint
import com.localstrava.format.Format
import com.localstrava.metrics.AcceptedPoint
import com.localstrava.metrics.Constants
import com.localstrava.metrics.Fix
import com.localstrava.metrics.RunAccumulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var repo: ActivityRepository
    private lateinit var locationSource: LocationSource

    private val accumulator = RunAccumulator()
    private var activityId: Long = 0L
    private var startedAtMs: Long = 0L
    private val buffer = ArrayList<AcceptedPoint>()
    private var lastFlushMs: Long = 0L
    private var lastNotifiedHundredM: Int = -1

    override fun onCreate() {
        super.onCreate()
        repo = ActivityRepository(RunDatabase.get(this).runDao())
        locationSource = LocationSource(this, ::onFix, ::onLocationLost)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart()
            ACTION_PAUSE -> handlePause()
            ACTION_RESUME -> handleResume()
            ACTION_STOP -> handleStop()
            else -> Unit // null intent after restart, or unknown action: ignore
        }
        return START_NOT_STICKY
    }

    private fun handleStart() {
        if (TrackingStateHolder.state.value.isActive) return // idempotent
        startedAtMs = System.currentTimeMillis()
        lastFlushMs = startedAtMs
        // startForeground must be the FIRST real action (FGS start window, spec §6).
        startForegroundCompat(buildNotification(paused = false, lost = false))
        TrackingStateHolder.set(TrackingUiState(phase = TrackPhase.Active))
        scope.launch { activityId = repo.startActivity(startedAtMs) }
        locationSource.start()
    }

    private fun handlePause() {
        if (TrackingStateHolder.state.value.phase != TrackPhase.Active) return
        accumulator.setManualPaused(true)
        TrackingStateHolder.update { it.copy(phase = TrackPhase.Paused) }
        pushNotification(paused = true, lost = false)
    }

    private fun handleResume() {
        if (TrackingStateHolder.state.value.phase != TrackPhase.Paused) return
        accumulator.setManualPaused(false)
        TrackingStateHolder.update { it.copy(phase = TrackPhase.Active, locationLost = false) }
        pushNotification(paused = false, lost = false)
    }

    private fun handleStop() {
        val phase = TrackingStateHolder.state.value.phase
        if (phase != TrackPhase.Active && phase != TrackPhase.Paused) return
        locationSource.stop()
        val endedAt = System.currentTimeMillis()
        val stats = accumulator.snapshot(endedAt)
        val id = activityId
        val started = startedAtMs
        val tooShort = stats.pointCount < Constants.MIN_SAVE_POINTS ||
            stats.distanceMeters < Constants.MIN_SAVE_M
        scope.launch {
            flushBufferLocked(id)
            if (id != 0L) {
                if (tooShort) repo.delete(id) else repo.finalizeActivity(id, started, endedAt, stats)
            }
            TrackingStateHolder.set(
                if (tooShort || id == 0L) TrackingUiState(phase = TrackPhase.Discarded)
                else TrackingUiState(phase = TrackPhase.Finished(id)),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun onFix(fix: Fix) {
        val accepted = accumulator.update(fix)
        if (accepted != null) buffer.add(accepted)
        publishStats(fix.timeMs)
        if (accepted != null) maybeFlush(fix.timeMs)
    }

    private fun onLocationLost() {
        if (TrackingStateHolder.state.value.phase == TrackPhase.Active) {
            accumulator.setManualPaused(true) // auto-pause; stats preserved
            TrackingStateHolder.update { it.copy(locationLost = true) }
            pushNotification(paused = true, lost = true)
        }
    }

    private fun publishStats(nowMs: Long) {
        val s = accumulator.snapshot(nowMs)
        TrackingStateHolder.update {
            it.copy(
                distanceMeters = s.distanceMeters,
                movingTimeMs = s.movingTimeMs,
                elevationGainMeters = s.elevationGainMeters,
                currentPaceSecPerKm = s.currentPaceSecPerKm,
                avgPaceSecPerKm = s.avgPaceSecPerKm,
                pointCount = s.pointCount,
            )
        }
        val hundredM = (s.distanceMeters / 100).toInt()
        if (hundredM != lastNotifiedHundredM) {
            lastNotifiedHundredM = hundredM
            val paused = TrackingStateHolder.state.value.phase == TrackPhase.Paused
            pushNotification(paused = paused, lost = TrackingStateHolder.state.value.locationLost)
        }
    }

    private fun maybeFlush(nowMs: Long) {
        if (nowMs - lastFlushMs < FLUSH_INTERVAL_MS || buffer.isEmpty()) return
        lastFlushMs = nowMs
        val id = activityId
        val started = startedAtMs
        val stats = accumulator.snapshot(nowMs)
        val batch = ArrayList(buffer)
        buffer.clear()
        scope.launch {
            if (id != 0L) {
                repo.appendPoints(batch.map { it.toEntity(id) })
                repo.updateProgress(id, started, stats)
            }
        }
    }

    private suspend fun flushBufferLocked(id: Long) {
        if (id != 0L && buffer.isNotEmpty()) {
            repo.appendPoints(buffer.map { it.toEntity(id) })
            buffer.clear()
        }
    }

    private fun AcceptedPoint.toEntity(id: Long) = TrackPoint(
        activityId = id,
        latitude = fix.latitude,
        longitude = fix.longitude,
        altitudeMeters = fix.altitudeMeters,
        timestampEpochMs = fix.timeMs,
        accuracyMeters = fix.accuracyMeters,
        speedMps = fix.speedMps,
        segmentBreak = segmentBreak,
    )

    private fun buildNotification(paused: Boolean, lost: Boolean): Notification {
        val s = TrackingStateHolder.state.value
        val title = when {
            lost -> "Location off — paused"
            paused -> "Run paused"
            else -> "Recording run"
        }
        val text = "${Format.distance(s.distanceMeters)} · ${Format.duration(s.movingTimeMs)}"
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(this, LocalStravaApp.TRACKING_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (paused) b.addAction(0, "Resume", serviceAction(ACTION_RESUME))
        else b.addAction(0, "Pause", serviceAction(ACTION_PAUSE))
        b.addAction(0, "Stop", serviceAction(ACTION_STOP))
        return b.build()
    }

    private fun serviceAction(action: String): PendingIntent {
        val intent = Intent(this, TrackingService::class.java).setAction(action)
        return PendingIntent.getService(
            this, action.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun pushNotification(paused: Boolean, lost: Boolean) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(paused, lost))
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    override fun onDestroy() {
        val id = activityId
        if (id != 0L && buffer.isNotEmpty()) {
            runBlocking { repo.appendPoints(buffer.map { it.toEntity(id) }) }
            buffer.clear()
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.localstrava.action.START"
        const val ACTION_PAUSE = "com.localstrava.action.PAUSE"
        const val ACTION_RESUME = "com.localstrava.action.RESUME"
        const val ACTION_STOP = "com.localstrava.action.STOP"
        private const val NOTIF_ID = 1
        private const val FLUSH_INTERVAL_MS = 10_000L

        /** START must be called from a visible Activity with permissions already granted (spec §6). */
        fun start(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, TrackingService::class.java).setAction(ACTION_START),
        )
        // PAUSE/RESUME/STOP are delivered to the already-running service (no FGS start contract).
        fun pause(context: Context) = deliver(context, ACTION_PAUSE)
        fun resume(context: Context) = deliver(context, ACTION_RESUME)
        fun stop(context: Context) = deliver(context, ACTION_STOP)

        private fun deliver(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
```

- [ ] **Step 2: Build the whole app (proves service + manifest + Room + osmdroid all link)**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`. If you commented out the manifest `<service>` in Task 3, uncomment it now and rebuild.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/localstrava/tracking/TrackingService.kt app/src/main/AndroidManifest.xml
git commit -m "feat(tracking): foreground TrackingService (state machine + incremental flush)"
```

---

## Phase 4 — UI (Compose: theme, nav, three screens)

No unit tests (Compose UI; out of scope per spec §10). Verify each task with `./gradlew assembleDebug` and (final task) a manual smoke test. ViewModels use `AndroidViewModel` so `viewModel()` supplies them with no custom factory.

> Compose API note: if the compiler flags a symbol as requiring `@OptIn(ExperimentalMaterial3Api::class)` (e.g. `TopAppBar`, `ListItem`) on a composable that lacks it, add the annotation to that composable. The snippets annotate the obvious ones; add others as flagged. Resolve any missing imports via the build error — do not invent new dependencies.

### Task 14: Theme + wire MainActivity to navigation

**Files:**
- Create: `app/src/main/java/com/localstrava/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/localstrava/ui/nav/AppNav.kt`
- Modify: `app/src/main/java/com/localstrava/MainActivity.kt` (replace the Task 3 placeholder)

> `AppNav` references the three screens (Tasks 15–18). Create those screen files in the same task-run before building, or build only after Task 18. To keep this task green on its own, you may stub the three `*Screen` composables as empty `@Composable fun XScreen(...) {}` and fill them in the next tasks — but the simplest path is to implement Tasks 14–18 back-to-back and run `assembleDebug` once at the end of Task 18.

- [ ] **Step 1: Create `ui/theme/Theme.kt`**

```kotlin
package com.localstrava.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val LightColors = lightColorScheme()
private val DarkColors = darkColorScheme()

@Composable
fun LocalStravaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
```

- [ ] **Step 2: Create `ui/nav/AppNav.kt`**

```kotlin
package com.localstrava.ui.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.localstrava.ui.detail.DetailScreen
import com.localstrava.ui.history.HistoryScreen
import com.localstrava.ui.record.RecordScreen

object Routes {
    const val HISTORY = "history"
    const val RECORD = "record"
    const val DETAIL_ARG = "id"
    const val DETAIL_PATTERN = "detail/{id}"
    fun detail(id: Long) = "detail/$id"
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HISTORY) {
        composable(Routes.HISTORY) {
            HistoryScreen(
                onStartRun = { nav.navigate(Routes.RECORD) },
                onOpenRun = { id -> nav.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.RECORD) {
            RecordScreen(
                onFinished = { id -> nav.navigate(Routes.detail(id)) { popUpTo(Routes.HISTORY) } },
                onDiscarded = { nav.popBackStack(Routes.HISTORY, inclusive = false) },
                onExitToHistory = { nav.popBackStack(Routes.HISTORY, inclusive = false) },
            )
        }
        composable(
            route = Routes.DETAIL_PATTERN,
            arguments = listOf(navArgument(Routes.DETAIL_ARG) { type = NavType.LongType }),
        ) { entry ->
            DetailScreen(
                activityId = entry.arguments?.getLong(Routes.DETAIL_ARG) ?: 0L,
                onBack = { nav.popBackStack() },
            )
        }
    }
}
```

- [ ] **Step 3: Replace `MainActivity.kt`**

```kotlin
package com.localstrava

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.localstrava.ui.nav.AppNav
import com.localstrava.ui.theme.LocalStravaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LocalStravaTheme {
                Surface(modifier = Modifier.fillMaxSize()) { AppNav() }
            }
        }
    }
}
```

- [ ] **Step 4: Commit** (build deferred to Task 18)

```bash
git add app/src/main/java/com/localstrava/ui/theme/Theme.kt app/src/main/java/com/localstrava/ui/nav/AppNav.kt app/src/main/java/com/localstrava/MainActivity.kt
git commit -m "feat(ui): theme + navigation host wired into MainActivity"
```

### Task 15: History screen + ViewModel (with crash recovery)

**Files:**
- Create: `app/src/main/java/com/localstrava/ui/history/HistoryViewModel.kt`
- Create: `app/src/main/java/com/localstrava/ui/history/HistoryScreen.kt`

- [ ] **Step 1: Create `HistoryViewModel.kt`**

```kotlin
package com.localstrava.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.Activity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())

    init {
        // Finalize/discard any run left in-progress by a crash or kill (spec §3.9).
        viewModelScope.launch(Dispatchers.IO) { repo.recoverInProgress() }
    }

    val runs: StateFlow<List<Activity>> =
        repo.observeCompleted().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}
```

- [ ] **Step 2: Create `HistoryScreen.kt`**

```kotlin
package com.localstrava.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.Activity
import com.localstrava.format.Format
import com.localstrava.tracking.TrackingStateHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFmt = SimpleDateFormat("EEE d MMM, HH:mm", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onStartRun: () -> Unit,
    onOpenRun: (Long) -> Unit,
    vm: HistoryViewModel = viewModel(),
) {
    val runs by vm.runs.collectAsState()
    val tracking by TrackingStateHolder.state.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Local Strava") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onStartRun) { Text("Start a run") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (tracking.isActive) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth().clickable { onStartRun() },
                ) {
                    Text(
                        "● Recording… tap to return",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            if (runs.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No runs yet — tap Start a run", textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(runs, key = { it.id }) { run -> RunRow(run) { onOpenRun(run.id) } }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunRow(run: Activity, onClick: () -> Unit) {
    val pace = if (run.distanceMeters >= 10.0 && run.movingTimeMs > 0)
        Format.pace(run.movingTimeMs.toDouble() / run.distanceMeters) else "--"
    ListItem(
        headlineContent = { Text(dateFmt.format(Date(run.startedAtEpochMs))) },
        supportingContent = {
            Text("${Format.distance(run.distanceMeters)} · ${Format.duration(run.movingTimeMs)} · $pace")
        },
        modifier = Modifier.clickable { onClick() },
    )
    HorizontalDivider()
}
```

- [ ] **Step 3: Commit** (build at Task 18)

```bash
git add app/src/main/java/com/localstrava/ui/history/
git commit -m "feat(ui): History screen with recovery + recording banner"
```

### Task 16: Record screen + ViewModel (permissions, controls, back, terminal nav)

**Files:**
- Create: `app/src/main/java/com/localstrava/ui/record/RecordViewModel.kt`
- Create: `app/src/main/java/com/localstrava/ui/record/RecordScreen.kt`

- [ ] **Step 1: Create `RecordViewModel.kt`**

```kotlin
package com.localstrava.ui.record

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.localstrava.tracking.TrackingService
import com.localstrava.tracking.TrackingStateHolder

class RecordViewModel(app: Application) : AndroidViewModel(app) {
    val state = TrackingStateHolder.state
    fun start() = TrackingService.start(getApplication())
    fun pause() = TrackingService.pause(getApplication())
    fun resume() = TrackingService.resume(getApplication())
    fun stop() = TrackingService.stop(getApplication())
    fun consumeTerminal() = TrackingStateHolder.consumeTerminal()
}
```

- [ ] **Step 2: Create `RecordScreen.kt`**

```kotlin
package com.localstrava.ui.record

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.format.Format
import com.localstrava.tracking.TrackPhase
import com.localstrava.tracking.TrackingUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordScreen(
    onFinished: (Long) -> Unit,
    onDiscarded: () -> Unit,
    onExitToHistory: () -> Unit,
    vm: RecordViewModel = viewModel(),
) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(state.phase) {
        when (val p = state.phase) {
            is TrackPhase.Finished -> { val id = p.activityId; vm.consumeTerminal(); onFinished(id) }
            is TrackPhase.Discarded -> { vm.consumeTerminal(); onDiscarded() }
            else -> Unit
        }
    }

    // Back during an active run: leave the service recording, return to History.
    BackHandler(enabled = state.isActive) { onExitToHistory() }

    val perms = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        val fine = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) vm.start() // notification denial is non-fatal (spec §8)
    }

    fun onStartClicked() {
        val fine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (fine) vm.start() else launcher.launch(perms)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Record") }) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.locationLost) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Location is off — recording paused. Re-enable location to continue.",
                        Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            BigStat("Time", Format.duration(state.movingTimeMs))
            BigStat("Distance", Format.distance(state.distanceMeters))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                SmallStat("Pace", Format.pace(state.currentPaceSecPerKm))
                SmallStat("Avg pace", Format.pace(state.avgPaceSecPerKm))
                SmallStat("Elev", Format.elevation(state.elevationGainMeters))
            }
            Spacer(Modifier.weight(1f))
            Controls(state, ::onStartClicked, vm::pause, vm::resume, vm::stop)
        }
    }
}

@Composable
private fun Controls(
    state: TrackingUiState,
    onStart: () -> Unit, onPause: () -> Unit, onResume: () -> Unit, onStop: () -> Unit,
) {
    when (state.phase) {
        is TrackPhase.Active -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedButton(onClick = onPause) { Text("Pause") }
            Button(onClick = onStop) { Text("Stop") }
        }
        is TrackPhase.Paused -> Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onResume) { Text("Resume") }
            Button(onClick = onStop) { Text("Stop") }
        }
        else -> Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("Start") }
    }
}

@Composable
private fun BigStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.displaySmall)
    }
}

@Composable
private fun SmallStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
```

- [ ] **Step 3: Commit** (build at Task 18)

```bash
git add app/src/main/java/com/localstrava/ui/record/
git commit -m "feat(ui): Record screen (permissions, controls, back, terminal nav)"
```

### Task 17: Detail screen + ViewModel (osmdroid route map)

**Files:**
- Create: `app/src/main/java/com/localstrava/ui/detail/DetailViewModel.kt`
- Create: `app/src/main/java/com/localstrava/ui/detail/DetailScreen.kt`

- [ ] **Step 1: Create `DetailViewModel.kt`**

```kotlin
package com.localstrava.ui.detail

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localstrava.data.Activity
import com.localstrava.data.ActivityRepository
import com.localstrava.data.RunDatabase
import com.localstrava.data.TrackPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DetailState(
    val activity: Activity? = null,
    val points: List<TrackPoint> = emptyList(),
    val loading: Boolean = true,
)

class DetailViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = ActivityRepository(RunDatabase.get(app).runDao())
    private val _state = MutableStateFlow(DetailState())
    val state: StateFlow<DetailState> = _state.asStateFlow()

    fun load(id: Long) = viewModelScope.launch {
        val activity = withContext(Dispatchers.IO) { repo.getActivity(id) }
        val points = withContext(Dispatchers.IO) { repo.getPoints(id) }
        _state.value = DetailState(activity, points, loading = false)
    }

    fun delete(id: Long, onDone: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { repo.delete(id) }
        onDone()
    }
}
```

- [ ] **Step 2: Create `DetailScreen.kt`**

```kotlin
package com.localstrava.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.localstrava.data.TrackPoint
import com.localstrava.format.Format
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(activityId: Long, onBack: () -> Unit, vm: DetailViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(activityId) { vm.load(activityId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Run") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = { confirmDelete = true }) { Text("Delete") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.activity?.let { a ->
                StatsRow(
                    a.distanceMeters, a.movingTimeMs, a.elevationGainMeters,
                    (a.endedAtEpochMs ?: a.startedAtEpochMs) - a.startedAtEpochMs,
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                when {
                    state.points.size >= 2 -> RouteMap(state.points)
                    !state.loading -> Text("No route recorded")
                    else -> Unit
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this run?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(activityId, onBack) }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatsRow(distanceM: Double, movingMs: Long, elevM: Double, elapsedMs: Long) {
    Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Stat("Distance", Format.distance(distanceM))
        Stat("Moving", Format.duration(movingMs))
        Stat("Pace", if (distanceM >= 10.0) Format.pace(movingMs.toDouble() / distanceM) else "--")
        Stat("Elev", Format.elevation(elevM))
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun RouteMap(points: List<TrackPoint>) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
        }
    }
    DisposableEffectMapLifecycle(lifecycleOwner, mapView)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { mapView },
        update = { map ->
            map.overlays.clear()
            // Split the polyline at segment breaks (pause/gap) so jumps aren't drawn.
            var seg = ArrayList<GeoPoint>()
            points.forEach { p ->
                if (p.segmentBreak && seg.isNotEmpty()) {
                    map.overlays.add(Polyline().apply { setPoints(seg) })
                    seg = ArrayList()
                }
                seg.add(GeoPoint(p.latitude, p.longitude))
            }
            if (seg.isNotEmpty()) map.overlays.add(Polyline().apply { setPoints(seg) })

            val first = points.first(); val last = points.last()
            map.overlays.add(Marker(map).apply { position = GeoPoint(first.latitude, first.longitude); title = "Start" })
            map.overlays.add(Marker(map).apply { position = GeoPoint(last.latitude, last.longitude); title = "End" })

            map.post {
                val box = BoundingBox.fromGeoPoints(points.map { GeoPoint(it.latitude, it.longitude) })
                map.zoomToBoundingBox(box.increaseByScale(1.3f), false, 48)
            }
            map.invalidate()
        },
    )
}

@Composable
private fun DisposableEffectMapLifecycle(
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    mapView: MapView,
) {
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }
}
```

- [ ] **Step 3: Build the full app**

Run: `./gradlew assembleDebug`
Expected: `BUILD SUCCESSFUL`; APK at `app/build/outputs/apk/debug/app-debug.apk`. Fix any missing `@OptIn`/imports the compiler reports (do not add new dependencies).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/localstrava/ui/detail/
git commit -m "feat(ui): Detail screen with osmdroid route map + delete"
```

---

## Phase 5 — Finishing touches & sideload

### Task 18: Adaptive launcher icon

**Files:**
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml` (add `android:icon` / `android:roundIcon`)

- [ ] **Step 1: Create `res/values/colors.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1E6F50</color>
</resources>
```

- [ ] **Step 2: Create `res/drawable/ic_launcher_foreground.xml`** (a simple white diamond glyph — geometrically trivial, guaranteed valid)

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M54,34 L74,54 L54,74 L34,54 Z" />
</vector>
```

- [ ] **Step 3: Create `res/mipmap-anydpi-v26/ic_launcher.xml` and `ic_launcher_round.xml`** (identical contents)

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 4: Add icon attributes to the manifest `<application>` tag**

In `app/src/main/AndroidManifest.xml`, add these two attributes to `<application>` (alongside the existing ones):
```xml
        android:icon="@mipmap/ic_launcher"
        android:roundIcon="@mipmap/ic_launcher_round"
```

- [ ] **Step 5: Build + commit**

Run: `./gradlew assembleDebug` → Expected: `BUILD SUCCESSFUL`.
```bash
git add app/src/main/res/values/colors.xml app/src/main/res/drawable/ app/src/main/res/mipmap-anydpi-v26/ app/src/main/AndroidManifest.xml
git commit -m "feat: adaptive launcher icon"
```

### Task 19: Full verification, sideload, and on-device smoke test

**Files:** none (verification only).

- [ ] **Step 1: Run the full unit-test suite**

Run: `./gradlew testDebugUnitTest`
Expected: PASS — `GeoMathTest` (3), `RunAccumulatorTest` (14), `FormatTest` (7).

- [ ] **Step 2: Build the release-candidate debug APK fresh**

Run: `./gradlew clean assembleDebug`
Expected: `BUILD SUCCESSFUL`; `app/build/outputs/apk/debug/app-debug.apk` exists.

- [ ] **Step 3: Sideload to a connected device** (USB debugging on, device plugged in)

Run:
```bash
~/Android/Sdk/platform-tools/adb devices            # confirm the device is listed
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Expected: `Success`. (If no device, copy the APK to the phone and tap it; enable "Install unknown apps".)

- [ ] **Step 4: Manual smoke test** (this is the real acceptance test — Compose/service aren't unit-tested)

Walk through and confirm each:
1. Launch → History shows "No runs yet".
2. Tap **Start a run** → grant location (and notifications on 13+) → **Start** → stats screen appears; a "Recording run" notification is present.
3. Walk/drive ~200 m: **Time** and **Distance** climb; **Pace** shows a value; the notification updates.
4. Stand still ~30 s → time/distance freeze (auto-pause); resume moving → they continue.
5. Lock the screen and keep moving ~1 min → unlock: distance kept climbing (background tracking works).
6. Tap **Stop** → lands on the run **Detail** screen → the OSM map shows your route; stats are populated.
7. Back → History lists the run (newest first) with distance/time/pace.
8. Re-open the run → map + stats load. **Delete** → confirm dialog → run disappears from History.
9. Start a run, then press system **Back** → returns to History with the "● Recording…" banner → tap it → returns to the live Record screen (rehydrated).
10. Toggle location services **off** mid-run → notification + banner show "Location off — paused"; toggle on → resumes.

- [ ] **Step 5: Final commit (close the build out)**

```bash
git add -A
git commit -m "chore: verified debug APK builds, tests pass, smoke test complete" --allow-empty
```

---

## Self-Review (performed against spec v2)

**Spec coverage:** every spec section maps to a task — §2 build config → Tasks 1–2; §3 architecture (Fix DTO, state holder, service, incremental persistence, recovery) → Tasks 5, 9, 10, 13, 15; §4 data model → Task 7; §5 metrics/filtering → Tasks 4 & 6 (with the gap-break, stationary auto-pause, teleport-keep-anchor, elevation pipeline, and edge cases all under test); §6 service/notification/permission ordering → Task 13; §7 three screens + formatting + back/confirm/min-run handling → Tasks 14–17 (+ Format in 11); §8 permissions + osmdroid init → Tasks 3 (manifest), 3 (Application osmdroid init), 16 (runtime request); §9 dependencies → Task 1 catalog; §10 testing (pure core) → Tasks 4, 6, 11; §11 build/sideload → Task 19; §13 limitations are inherent (debug signing via default debug build type; no separate speed readout — pace only, honored in Tasks 16–17).

**Deferred/with-deviation (all called out at the top and at point of use):** Room 2.6.1+KSP1 instead of 2.7.x/KSP2; `allowBackup="false"` without a custom `dataExtractionRules.xml`; `Locale.US` number formatting instead of device-locale `NumberFormat`. None changes user-facing scope.

**Placeholder scan:** no "TBD"/"add error handling"/"similar to" — all steps carry full code. The only intentional stubs are the optional empty-screen stubs noted in Task 14 (resolved by implementing Tasks 15–17).

**Type consistency:** `Fix`, `AcceptedPoint`, `RunStats`, `TrackingUiState`/`TrackPhase`, `Activity`/`TrackPoint`, and `RunAccumulator.update`/`snapshot(nowMs)` signatures are used identically across the service, repository, and UI. `Constants` names referenced in tests match the file.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-06-07-local-strava.md`. Two execution options:

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints for review.

Which approach?
