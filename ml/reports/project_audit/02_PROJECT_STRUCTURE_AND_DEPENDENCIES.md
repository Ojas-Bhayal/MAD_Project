# Phase B: Complete Project Structure and Dependency Audit

**Audit Date:** 2026-10-24T23:26:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Senior Android Architect, Quality & Security Auditor  

---

## 1. Build System and Configuration Architecture

### A. Gradle and Android Gradle Plugin (AGP) Matrix
- **Gradle Version:** `9.6.0` (Distribution URL: `gradle-9.6.0-bin.zip`).
- **Android Gradle Plugin (AGP):** `9.4.0` defined in `gradle/libs.versions.toml` (`libs.plugins.android.application`).
- **Java Toolchain & Target Compatibility:** 
  - `sourceCompatibility = JavaVersion.VERSION_17`
  - `targetCompatibility = JavaVersion.VERSION_17`
  - Active execution daemon runs on JVM 17 (`17.0.20.1+1`, Eclipse Adoptium).
- **SDK Target Boundaries:**
  - `compileSdk = 37`
  - `targetSdk = 37` (Android 16 / Vanilla Ice Cream preview tier)
  - `minSdk = 24` (Android 7.0 Nougat — covering $\ge 96.5\%$ of active global Android fleet)
  - `resConfigs 'en', 'hi', 'te'` restricts packaged strings to the 3 supported languages.

### B. Build Types and Variant Architecture
```groovy
buildTypes {
    debug {
        minifyEnabled = false
        ndk {
            abiFilters('arm64-v8a', 'armeabi-v7a', 'x86_64')
        }
    }
    release {
        minifyEnabled = true
        shrinkResources = true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```
- **ABI Filtering:**
  - Debug includes `x86_64` to support desktop Android Virtual Devices (AVD).
  - Production `defaultConfig` restricts native binaries to ARM architectures (`arm64-v8a`, `armeabi-v7a`), preventing unnecessary x86/x86_64 bloat in Play Store releases.
- **Bundle Packaging:**
  - `language.enableSplit = false`: Essential design choice! Retains all 3 languages (English, Hindi, Telugu) on the client device so the in-app `LocaleHelper` can switch instantaneously offline without querying the Google Play Dynamic Delivery service.
  - `density.enableSplit = true` and `abi.enableSplit = true`: Generates lean device-targeted APKs via Android App Bundle (AAB).
- **Resource Packaging:**
  - `androidResources.noCompress 'tflite'`: Critical for memory-mapped file access. Enables LiteRT to map `.tflite` model files directly into process virtual memory using `FileChannel.map(MapMode.READ_ONLY)` with zero heap allocation overhead.

### C. Release Integrity Gate
- `validateBundledModelForRelease`: Wired via `tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn("validateBundledModelForRelease") }`.
- Guarantees that no release artifact (`assembleRelease` or `bundleRelease`) can compile if the bundled production model is a placeholder, fails SHA-256 digest verification, fails label CRC32 checks, or fails tensor shape/dtype graph inspection.

---

## 2. Directory Tree and Source Set Inventory

```
CropDoc/
├── app/
│   ├── build.gradle                     # Module build and safety gate definitions
│   ├── proguard-rules.pro               # ProGuard/R8 obfuscation & keep rules
│   ├── schemas/                         # Room DB schema json history (v1)
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml      # App permissions, activities, file providers
│       │   ├── assets/
│       │   │   ├── advice/              # Trilingual agronomic treatment catalog
│       │   │   ├── models/              # Production model, manifest, labels, metadata
│       │   │   └── research_models/     # Isolated research models (gate + 6-class) & samples
│       │   ├── java/com/cropdoc/app/
│       │   │   ├── CropDocApplication.java  # StrictMode, Firebase App Check init
│       │   │   ├── data/                # Local Room database, Remote sources, Repositories
│       │   │   ├── di/                  # Manual DI container (AppContainer, AppExecutors)
│       │   │   ├── domain/              # Use cases (AnalyzeLeafUseCase)
│       │   │   ├── ml/                  # Production ML classification & safety gates
│       │   │   │   ├── policy/          # Containment pipeline & decision policy
│       │   │   │   └── research/        # Isolated LiteRT research model executor & harness
│       │   │   ├── ui/                  # UI Fragments, ViewModels, Adapters, Helpers
│       │   │   └── util/                # LocaleHelper, WindowInsetsHelper
│       │   └── res/                     # Layouts, Navigation, Qualifiers (values, values-hi, values-te)
│       ├── test/                        # 19 Unit Test suites (136 tests)
│       └── androidTest/                 # Instrumentation test fixtures
├── config/
│   ├── checkstyle/checkstyle.xml        # Checkstyle rule specification
│   └── lint/lint.xml                    # Lint configuration and suppressions
├── data/                                # Curated datasets, splits, manifests, license attributions
├── gradle/                              # Wrapper and Version Catalog (libs.versions.toml)
└── ml/                                  # ML training, conversion, forensic audit scripts & reports
```

---

## 3. Comprehensive Dependency Audit

| Dependency Key | Group & Artifact | Resolved Version | Classification | Risk & Impact Assessment |
| :--- | :--- | :--- | :--- | :--- |
| `androidx-core` | `androidx.core:core` | `1.19.1` | Core Framework | Stable. Required for backwards-compatible OS APIs and NotificationCompat. |
| `androidx-appcompat` | `androidx.appcompat:appcompat` | `1.8.0` | Core Framework | Required for per-app language selection (`AppCompatDelegate.setApplicationLocales`). |
| `camerax-*` | `androidx.camera:camera-*` | `1.6.2` | Camera Subsystem | CameraX 1.6.2 provides high stability across vendor Camera2 HAL implementations. |
| `navigation-*` | `androidx.navigation:navigation-*` | `2.10.2` | Navigation Architecture | Modern Jetpack Navigation component with SafeArgs. |
| `room-*` | `androidx.room:room-*` | `2.8.5` | Local Database | SQLite ORM with compile-time query verification and schema export. |
| `work-runtime` | `androidx.work:work-runtime` | `2.12.0` | Background Sync | Powers `ScanSyncWorker` for deferred offline synchronization. |
| `credentials-*` | `androidx.credentials:credentials` | `1.6.0` | Identity / Auth | Modern Android Credential Manager replacing legacy Google Sign-In. |
| `googleid` | `com.google.android.libraries.identity.googleid:googleid` | `1.1.1` | Identity / Auth | Google ID token credential parser for Credential Manager. |
| `firebase-bom` | `com.google.firebase:firebase-bom` | `35.0.0` | Cloud Platform | Centralized version management for Auth, Firestore, Crashlytics, RemoteConfig, and App Check. |
| `litert-core` | `com.google.ai.edge.litert:litert` | `2.3.0` | On-Device ML | Official successor to TensorFlow Lite runtime. Fast, zero-allocation native inference engine. |
| `glide-core` | `com.github.bumptech.glide:glide` | `5.0.9` | Image Loading | High-performance bitmap decoding, caching, and downsampling in History and Result screens. |
| `guava` | `com.google.guava:guava` | `33.3.1-android` | Utility Library | **High Dependency Size Finding.** See Finding DEP-01 below. |
| `leakcanary-android` | `com.squareup.leakcanary:leakcanary-android` | `2.14` | Dev Tooling | Commented out in `app/build.gradle` so no leak icon appears in debug builds. |

---

## 4. Key Dependency Findings

### Finding DEP-01 (Severity: Medium) — Redundant Guava Full Library Inclusion
- **Location:** `app/build.gradle` line 144: `implementation(libs.guava)`.
- **Evidence:** Searching for `com.google.common` imports across the entire `app/src/` directory reveals only **one single import**:
  `app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java: import com.google.common.util.concurrent.ListenableFuture;`
- **Impact:** `libs.guava` brings in `com.google.guava:guava:33.3.1-android`, which is a ~3.0 MB JAR with over 2,000 classes. However, `ListenableFuture` is already provided transitively by `androidx.camera:camera-core` through `com.google.guava:listenablefuture:1.0` (a tiny 2 KB artifact).
- **Recommendation:** Replace `implementation(libs.guava)` with `implementation("com.google.guava:listenablefuture:1.0")` or remove it entirely, saving DEX method count and reducing APK download size.

### Finding DEP-02 (Severity: Low) — Crashlytics and Google Services Plugin Conditional Application
- **Location:** `app/build.gradle` lines 8–11:
  ```groovy
  if (file("google-services.json").exists()) {
      apply plugin: "com.google.gms.google-services"
      apply plugin: "com.google.firebase.crashlytics"
  }
  ```
- **Evidence:** Gracefully accommodates local development environments that lack a private `google-services.json` without failing the build. `CropDocApplication` safely wraps Firebase initialization in a `try-catch` block.
- **Assessment:** Clean, defensive architectural pattern for open-source and offline resilience.

---

## 5. ProGuard / R8 Obfuscation Analysis

Inspection of `app/proguard-rules.pro` confirms proper rules for:
1. **LiteRT Runtime:**
   ```proguard
   -keep class org.tensorflow.lite.** { *; }
   -keep class com.google.ai.edge.litert.** { *; }
   ```
   Prevents R8 from stripping JNI bindings, native memory mapping methods, and tensor handle pointers.
2. **Room Database Entities:**
   ```proguard
   -keep class com.cropdoc.app.data.local.entity.** { *; }
   ```
   Ensures Room entity field names, annotations, and reflection-free code generation remain unstripped.
3. **Firestore Serialization Safety:**
   ```proguard
   -keepattributes Signature, InnerClasses, EnclosingMethod
   ```
   Preserves generic type signatures necessary for Firestore POJO deserialization.
4. **Crashlytics Stacktrace Deobfuscation:**
   ```proguard
   -keepattributes SourceFile,LineNumberTable
   -renamesourcefileattribute SourceFile
   ```
   Ensures uploaded crash traces contain valid line numbers for remote debugging.

---

## 6. Asset Size and Packaging Impact

| Asset File | Size (Bytes) | Packaged in APK? | Purpose |
| :--- | :--- | :--- | :--- |
| `crop_domain_gate_fp32.tflite` | 6,187,500 (~5.90 MB) | Yes (`assets/research_models/`) | Research crop domain gate model |
| `unified_six_class_fp32.tflite` | 6,199,800 (~5.91 MB) | Yes (`assets/research_models/`) | Research 6-class disease model |
| `cropdoc_leaf_v1_int8.tflite` | 16,800 (~16.4 KB) | Yes (`assets/models/`) | Production placeholder model |
| `advice_catalog.json` | 72,916 (~71.2 KB) | Yes (`assets/advice/`) | Offline trilingual treatment catalog |
| `sample_non_leaf_soil.jpg` | 58,798 (~57.4 KB) | Yes (`assets/research_models/`) | Genuine non-leaf research sample |
| `sample_rice_leaf.jpg` | 18,418 (~18.0 KB) | Yes (`assets/research_models/`) | Genuine rice leaf research sample |
| `model_metadata.json` | 958 bytes | Yes (`assets/models/`) | Auxiliary model descriptor |
| `cropdoc_leaf_v1_manifest.json` | 486 bytes | Yes (`assets/models/`) | Cryptographic manifest |
| `labels.txt` | 316 bytes | Yes (`assets/models/`) | 16-class ordered labels |

- **Total Assets Impact on APK:** ~12.5 MB.
- **Analysis:** 98.7% of the asset footprint is comprised of the two research `.tflite` models in `assets/research_models/`. While acceptable for debug builds, bundling 12 MB of experimental research models into a production APK is unnecessary. (Addressed further in Phase N).
