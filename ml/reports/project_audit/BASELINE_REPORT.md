# CropDoc — Baseline Project Audit Report

**Date:** October 24, 2026  
**Auditor:** Senior Android Application Architect & ML Systems Engineer  
**Workspace:** `C:\Users\ojas\OneDrive\Desktop\MajorProjectMAD\CropDoc`  
**Git Branch:** `ml-safety-phase-g`  
**Git Status:** Modified `app/build.gradle` (debug x86_64 ABI filter for emulator), `data/licenses/SOURCES.md`, untracked research assets/manifests/reports.  

---

## 1. Project Root & Module Architecture

* **Root Project Name:** `CropDoc` (`settings.gradle`)
* **Included Modules:** Single application module `:app` (`app/`)
* **Build Tooling:**
  - **Gradle Version:** `9.6.0` (`gradle/wrapper/gradle-wrapper.properties`)
  - **Android Gradle Plugin (AGP):** `9.4.0` (`gradle/libs.versions.toml`)
  - **Host JVM Runtime:** Oracle Java SE 27 (`build 27+35-2325`, 64-Bit Server VM)
  - **Source & Target Java Compatibility:** `JavaVersion.VERSION_17`
  - **Android Compile SDK:** `37` (Android 17)
  - **Android Target SDK:** `37` (Android 17)
  - **Android Min SDK:** `24` (Android 7.0 Nougat)
* **Build Features & Flags:**
  - `viewBinding = true`
  - `buildConfig = true`
  - `org.gradle.configuration-cache = true`
  - `android.nonTransitiveRClass = true`
  - `android.enableR8.fullMode = true`
  - `noCompress 'tflite'`
* **Build Variants:**
  - `debug`: `minifyEnabled = false`, `abiFilters = ['arm64-v8a', 'armeabi-v7a', 'x86_64']`
  - `release`: `minifyEnabled = true`, `shrinkResources = true`, `abiFilters = ['arm64-v8a', 'armeabi-v7a']`, ProGuard `proguard-rules.pro`

---

## 2. Dependency Catalog Baseline

| Category | Library Dependency | Declared Version |
| :--- | :--- | :--- |
| **AndroidX Core & UI** | `androidx.core:core`, `appcompat`, `activity`, `fragment`, `constraintlayout`, `recyclerview`, `exifinterface`, `google.android.material` | Core 1.19.1, Appcompat 1.8.0, Activity 1.13.0, Material 1.13.0 |
| **CameraX** | `androidx.camera:camera-core`, `camera2`, `camera-lifecycle`, `camera-view` | 1.6.2 |
| **Navigation** | `androidx.navigation:navigation-fragment`, `navigation-ui` | 2.10.2 |
| **Persistence** | `androidx.room:room-runtime`, `room-compiler` | 2.8.5 |
| **Background Work** | `androidx.work:work-runtime` | 2.12.0 |
| **Lifecycle** | `androidx.lifecycle:lifecycle-viewmodel`, `livedata`, `runtime`, `common-java8` | 2.11.0 |
| **Authentication** | `androidx.credentials:credentials`, `credentials-play-services-auth`, `googleid` | Credentials 1.6.0, GoogleID 1.1.1 |
| **Firebase** | `firebase-bom` (Auth, Firestore, Crashlytics, RemoteConfig, AppCheck PlayIntegrity/Debug) | BOM 35.0.0 |
| **ML Inference** | `com.google.ai.edge.litert:litert` | 2.3.0 |
| **Image Loading** | `com.github.bumptech.glide:glide` | 5.0.9 |
| **Testing** | `junit:junit:4.13.2`, `org.mockito:mockito-core:5.14.2`, `archCoreTesting:2.2.0`, `espresso-core:3.7.0` | Production standard |
| **Static Analysis** | `checkstyle:10.26.1`, Android Lint (integrated) | Configured in `config/` |

---

## 3. Baseline Verification Commands & Status

### 3.1 Static Analysis — Checkstyle
* **Command:** `./gradlew.bat checkstyle`
* **Exit Status:** `FAILED (Exit Code 1)`
* **Duration:** 18 seconds
* **Finding Details:** 77 violations detected across 21 files.
  - **FileLength Violations (> 250 lines):**
    - `LiteRtLeafClassifier.java` (387 lines, max 250)
    - `ModelValidator.java` (402 lines, max 250)
    - `EndToEndSafetyPipeline.java` (280 lines, max 250)
    - `InferenceDecisionPolicy.java` (309 lines, max 250)
    - `CropDomainGateSafetyTest.java` (230 lines)
    - `EndToEndSafetyTest.java` (286 lines)
    - `InferenceDecisionPolicyTest.java` (404 lines)
    - `ContainmentSafetyTestSuite.java` (847 lines)
  - **MethodLength Violations (> 40 lines):**
    - `LiteRtLeafClassifier.classify()` (78 lines)
    - `LiteRtLeafClassifier.initializeBundledPair()` (45 lines)
    - `ModelValidator.performValidation()` (134 lines)
    - `ModelValidator.validateModel()` (59 lines)
    - `ConfidenceCalibrator.evaluateLogitsOutcome()` (54 lines)
    - Multiple test methods with extensive assertion blocks.
  - **Unused Imports & Minor Formatting:**
    - Unused imports in `ClassificationOutcome.java`, `ConfidenceCalibrator.java`, `ModelManifest.java`, `ModelValidator.java`, `EndToEndSafetyPipeline.java`.
    - Missing braces (`NeedBraces`) in single-line `if` statements in `ModelValidator.java`.

### 3.2 Unit Test Suite Execution
* **Command:** `./gradlew.bat testDebugUnitTest --rerun-tasks`
* **Exit Status:** `SUCCESS (Exit Code 0)`
* **Duration:** 31 seconds
* **Summary:** **16 test suites, 113 total unit tests, 0 failures, 0 errors, 0 skipped.**

| Test Suite Class | Tests | Failures | Errors | Duration (s) |
| :--- | :---: | :---: | :---: | :---: |
| `com.cropdoc.app.data.repository.AdviceRepositoryTest` | 2 | 0 | 0 | 1.243 s |
| `com.cropdoc.app.data.repository.ScanRepositoryTest` | 4 | 0 | 0 | 0.120 s |
| `com.cropdoc.app.ml.ContainmentSafetyTestSuite` | 29 | 0 | 0 | 0.502 s |
| `com.cropdoc.app.ml.LiteRtLeafClassifierTest` | 7 | 0 | 0 | 0.003 s |
| `com.cropdoc.app.ml.MlSafetyReproductionTest` | 3 | 0 | 0 | 0.003 s |
| `com.cropdoc.app.ml.PhaseEReproductionTest` | 4 | 0 | 0 | 0.013 s |
| `com.cropdoc.app.ml.policy.CropDomainGateSafetyTest` | 8 | 0 | 0 | 0.149 s |
| `com.cropdoc.app.ml.policy.EndToEndSafetyTest` | 8 | 0 | 0 | 0.050 s |
| `com.cropdoc.app.ml.policy.InferenceDecisionPolicyTest` | 14 | 0 | 0 | 0.005 s |
| `com.cropdoc.app.ml.research.ResearchInferenceHarnessTest` | 8 | 0 | 0 | 0.098 s |
| `com.cropdoc.app.ml.research.TfliteModelAssetIntegrityTest` | 5 | 0 | 0 | 0.045 s |
| `com.cropdoc.app.ml.research.TflitePipelineSafetyTest` | 5 | 0 | 0 | 0.024 s |
| `com.cropdoc.app.ui.auth.AuthViewModelTest` | 6 | 0 | 0 | 0.019 s |
| `com.cropdoc.app.ui.result.ResultViewModelTest` | 4 | 0 | 0 | 0.005 s |
| `com.cropdoc.app.ui.scan.ScanViewModelTest` | 1 | 0 | 0 | 0.022 s |
| `com.cropdoc.app.ui.UiBreakTest` | 5 | 0 | 0 | 0.005 s |
| **Total** | **113** | **0** | **0** | **~2.30 s (execution)** |

### 3.3 Debug APK Compilation
* **Command:** `./gradlew.bat assembleDebug`
* **Exit Status:** `SUCCESS (Exit Code 0)`
* **Duration:** 8 seconds (cached/up-to-date) / 29 seconds (clean build)
* **Output Path:** `app/build/outputs/apk/debug/app-debug.apk`
* **File Size:** 51,282,514 bytes (48.91 MB)
* **SHA-256 Digest:** `E7A5E79ECFEE0F7221533E315EF55002ED175F99349DFE641410E7B5C15E2DC6`

### 3.4 Release Gate Validation
* **Command:** `./gradlew.bat validateBundledModelForRelease`
* **Exit Status:** `ABORTED / FAILED (Exit Code 1)`
* **Duration:** 10 seconds
* **Finding Details:** Expected fail-closed behavior:
  `Release build aborted: bundled model 'cropdoc_leaf_v1_int8.tflite' is a placeholder (16800 bytes < 524288 bytes threshold). A real, validated model is required before shipping a release build.`
  This confirms that the release gate actively prevents shipping placeholder models to production.

### 3.5 On-Device Instrumentation Verification
* **Command:** `adb shell am instrument -w -r -e class com.cropdoc.app.ml.research.LiteRtOnDeviceInferenceTest,com.cropdoc.app.ml.research.ResearchPipelineDeviceTest com.cropdoc.app.test/androidx.test.runner.AndroidJUnitRunner`
* **Target:** Pixel 7 Emulator (`sdk_gphone16k_x86_64`), Android 17 / API 37
* **Exit Status:** `SUCCESS (Exit Code 0)`
* **Duration:** 3.493 seconds
* **Result:** **8 tests executed, 8 passed, 0 failures, 0 errors.** Verified real native `libLiteRt.so` loading, tensor contracts, on-device latency (DomainGate: 35.33 ms, DiseaseModel: 34.53 ms), and fail-closed non-leaf rejection.

---

## 4. Summary of Baseline State

1. **Compilation & Packaging:** The project builds a working debug APK without errors. All 113 unit tests pass.
2. **Release Protection:** Release validation is strictly fail-closed, blocking placeholder models.
3. **Primary Baseline Deficiency:** Static analysis via Checkstyle currently fails on 21 files due to legacy length limits and formatting rules; no functional regression exists.
