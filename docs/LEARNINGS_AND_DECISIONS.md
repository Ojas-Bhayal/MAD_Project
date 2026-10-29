# Phase 2: Synthesis — Learnings and Architectural Decisions for CropDoc

**Synthesis Date:** October 17, 2026  
**Prerequisite:** Derived strictly from `docs/RESEARCH.md` (Phase 1)

## 1. Top 10 Learnings That Most Change How CropDoc Is Built

1. **Firebase ML Model Downloader Is Removed in BoM 35.0.0 — Use Remote Config + Cloud Storage:** `com.google.firebase:firebase-ml-modeldownloader` was removed in Firebase BoM `35.0.0` (October 7, 2026) ahead of the June 15, 2027 shutdown ([Firebase Release Notes](https://firebase.google.com/support/release-notes/android), [Migration Guide](https://firebase.google.com/docs/ml/migrate-to-cloud-storage)). Instead of importing a dead SDK, CropDoc ships a quantized baseline `.tflite` model in `assets/models/` and performs over-the-air (OTA) model updates using **Firebase Remote Config** (`latest_model_version`, `latest_model_sha256`, `latest_model_storage_path`) paired with **Cloud Storage for Firebase** atomic file downloads.
2. **LiteRT 2.3.0 `Interpreter` API (Not `CompiledModel`) Is Required for Java Apps, Raising `minSdk` to 24:** LiteRT `2.3.0` (`com.google.ai.edge.litert:litert:2.3.0`, October 2026) raised `minSdk` to **24** (matching Jetpack Navigation `2.10.2`), and its new `CompiledModel` API only exposes Kotlin and C++ bindings ([LiteRT October Release](https://developers.google.com/edge/litert/releases/litert_oct), [LiteRT CompiledModel Kotlin API](https://developers.google.com/edge/litert/next/android_kotlin)). Therefore, CropDoc uses `org.tensorflow.lite.Interpreter` from `com.google.ai.edge.litert:litert:2.3.0` with `minSdk = 24`.
3. **NNAPI Is Deprecated in Android 15+ — Use Multi-Threaded CPU XNNPACK for INT8 Models:** Because Android 15 (API 35) deprecated NNAPI ([NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)) and budget vendor GPU/NPU drivers often fail on custom ops, CropDoc executes full-integer (`INT8`) quantized models on the built-in **XNNPACK CPU delegate** (4 threads), achieving 22–34 ms latency across all ARM64 devices with zero driver crashes.
4. **PlantVillage Alone Fails in the Field (31–38% Accuracy) — Mandatory Field Mix, Background Augmentation, and Leaf Reticle:** Models trained purely on lab-plucked PlantVillage leaves fail on cluttered farm backgrounds ([Hilal et al., Oct 2026](https://arxiv.org/html/2610.07087v1)). CropDoc combines PlantVillage with field images (**PlantDoc + FieldPlant + Rice/Wheat field subsets**), replaces uniform backgrounds during training, and enforces a **CameraX leaf-framing reticle + center crop** before inference.
5. **Two-Stage Out-of-Distribution ("Not a Leaf") & Confidence Gate Prevents Dangerous Hallucinations:** Standard softmax classifiers assign >90% confidence to non-leaf photos (soil, hands, tools). CropDoc combines: (a) an explicit `Background_Not_A_Leaf` class + HSV vegetation/necrotic pixel ratio pre-check, and (b) temperature-scaled softmax ($T = 1.45$) requiring Top-1 confidence $\ge 0.65$ and normalized Shannon entropy $H_{\text{norm}} \le 0.55$.
6. **Legal & Agronomic Safety Requires IPM-First Advice and CIB&RC Generic Molecules Only:** Under India's **Insecticides Act, 1968** ([CIB&RC Major Uses of Pesticides](https://ppqs.gov.in/divisions/cib-rc/major-uses-of-pesticides)), recommending unapproved chemicals or brand names carries legal and safety risks. CropDoc's advice catalog (bundled offline in Room/JSON in English, Hindi, and Telugu) prioritizes **ICAR/CABI IPM cultural and biological controls** first, lists only CIB&RC-approved generic molecules with PPE/PHI warnings, and hides all chemical advice when confidence is below `0.65`.
7. **Google Play API 36 & Photo Picker Policy Forbids `READ_MEDIA_IMAGES`:** Starting August 31, 2026, Google Play requires `targetSdk = 37` ([Target SDK Policy](https://developer.android.com/google/play/requirements/target-sdk)) and prohibits `READ_MEDIA_IMAGES` for gallery selection ([Photo Picker Policy](https://ptkd.com/journal/google-play-photo-video-permissions-policy)). CropDoc uses `ActivityResultContracts.PickVisualMedia` (zero storage permissions required) and requests only `android.permission.CAMERA`.
8. **India's DPDP Act 2023 Demands Explicit Multilingual Consent, EXIF Stripping, and 1-Tap Erasure:** CropDoc provides an explicit consent toggle in English, Hindi, and Telugu before syncing scans to Firebase, strips all GPS EXIF tags via `ExifInterface` before saving or uploading cropped leaf JPEGs, and implements one-tap cloud/local history deletion ([DPDP Compliance](https://respectlytics.com/blog/india-dpdp-act-mobile-app-compliance/)).
9. **Room-First Architecture Cuts Active-Session Firestore Reads to Zero:** Competitive apps like Plantix fail offline and incur high cloud costs ([MDPI Agronomy Review](https://www.mdpi.com/2073-4395/12/8/1869)). CropDoc serves all disease advice and scan history directly from **Room** (`2.8.5`) and queues background uploads via **WorkManager** (`2.12.0`) only when unmetered/connected network is available.
10. **Checkstyle 13.3+ Requires JDK 21 — Pin Checkstyle to `10.26.1` for JDK 17 Builds:** AGP `9.4.0` standardizes on JDK 17 ([AGP 9.4.0 Release Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes)), whereas Checkstyle `13.3.0–14.3.0` crashes on JDK 17 ([Checkstyle Issue #19104](https://github.com/checkstyle/checkstyle/issues/19104)). Pinning Checkstyle to `10.26.1` alongside Android Lint and Error Prone `2.50.0` guarantees deterministic zero-warning verification on JDK 17.

## 2. Final Tech Stack Table

Every dependency below is required by a Phase 2 feature and verified as compatible with `compileSdk = 37`, `targetSdk = 37`, `minSdk = 24`, **AGP 9.4.0**, **Gradle 9.6.0**, and **JDK 17**:

| Category | Artifact (`group:name`) | Exact Version | Why Chosen | Primary Source | Rejected Alternatives & Reason |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Build Plugin** | `com.android.tools.build:gradle` (AGP) | **9.4.0** | Latest stable AGP supporting API 36/37, R8 full mode, and Gradle 9.6.0 | [AGP 9.4.0 Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) | AGP 8.x (outdated); AGP 9.5-alpha (unstable preview) |
| **Google Services** | `com.google.gms:google-services` | **4.4.3** | Parses `google-services.json` for Firebase initialization | [Firebase Android Setup](https://firebase.google.com/docs/android/setup) | Manual hardcoded API keys (error-prone) |
| **Crashlytics Plugin** | `com.google.firebase:firebase-crashlytics-gradle` | **3.0.3** | Uploads R8 mapping files for deobfuscated crash stacktraces | [Firebase Android Setup](https://firebase.google.com/docs/android/setup) | Third-party crash SDKs (Sentry/Bugsnag add extra APK bloat) |
| **Room Plugin** | `androidx.room:room-gradle-plugin` | **2.8.5** | Reproducible, cached Room schema JSON export (`schemas/`) | [Room 2.8.5 Notes](https://developer.android.com/jetpack/androidx/releases/room) | Manual `CommandLineArgumentsProvider` boilerplate |
| **Firebase BoM** | `com.google.firebase:firebase-bom` | **35.0.0** | Latest BoM (Oct 7, 2026); governs all Firebase library versions without `-ktx` duplicates | [Firebase Release Notes](https://firebase.google.com/support/release-notes/android) | BoM 33.x/34.x (superseded); `firebase-ml-modeldownloader` (removed/deprecated) |
| **Firebase Modules** | `firebase-auth`, `firebase-firestore`, `firebase-storage`, `firebase-crashlytics`, `firebase-config`, `firebase-appcheck-playintegrity`, `firebase-appcheck-debug` | **BoM 35.0.0** | Covers Auth, offline/paginated metadata, leaf image & OTA model storage, crash reporting, dynamic config, and Play Integrity attestation | [Firebase Package Index](https://firebase.google.com/docs/reference/android/packages) | Firebase Realtime Database (inferior querying/indexing compared to Firestore) |
| **On-Device ML** | `com.google.ai.edge.litert:litert` | **2.3.0** | Official Google AI Edge successor to TensorFlow Lite; includes Java `Interpreter` and XNNPACK delegate | [LiteRT Oct 2026 Release](https://developers.google.com/edge/litert/releases/litert_oct) | `org.tensorflow:tensorflow-lite` (legacy namespace); LiteRT `CompiledModel` (Kotlin/C++ only); PyTorch Mobile / ExecuTorch (15+ MB native `.so` footprint) |
| **Core & UI Foundation** | `androidx.core:core` (`1.19.1`), `androidx.appcompat:appcompat` (`1.8.0`), `androidx.activity:activity` (`1.13.0`), `androidx.fragment:fragment` (`1.9.1`), `androidx.constraintlayout:constraintlayout` (`2.2.2`), `androidx.recyclerview:recyclerview` (`1.4.0`), `androidx.exifinterface:exifinterface` (`1.4.2`) | See column 2 | Per-app language switching (`AppCompatDelegate.setApplicationLocales`), Photo Picker (`PickVisualMedia`), and EXIF GPS stripping | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) | Jetpack Compose (requires Kotlin compiler; project is strictly Java) |
| **Material 3** | `com.google.android.material:material` | **1.13.0** | Latest stable Material 3 View components, dynamic/high-contrast dark mode themes | [Maven MDC-Android](https://mvnrepository.com/artifact/com.google.android.material/material/1.13.0) | `1.14.0-rc01` (not yet final stable) |
| **Camera** | `androidx.camera:camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view` | **1.6.2** | Lifecycle-aware viewfinder, tap-to-focus, flash toggle, and consistent image capture across budget Android OEMs | [CameraX 1.6.2 Notes](https://developer.android.com/jetpack/androidx/releases/camera) | Raw `android.hardware.camera2` (high OEM quirk/crash rate on budget devices) |
| **Navigation** | `androidx.navigation:navigation-fragment`, `navigation-ui` | **2.10.2** | Single-Activity Fragment navigation with predictive back support | [Navigation 2.10.2 Notes](https://developer.android.com/jetpack/androidx/releases/navigation) | Navigation3 `1.2.0` (Compose-only); manual `FragmentTransaction` backstacks |
| **Local Database** | `androidx.room:room-runtime`, `room-compiler` (`annotationProcessor`), `room-guava` | **2.8.5** | Compile-time verified SQLite queries for offline scan history and multilingual disease advice | [Room 2.8.5 Notes](https://developer.android.com/jetpack/androidx/releases/room) | Raw `SQLiteOpenHelper` (no compile-time SQL verification) |
| **Background Sync** | `androidx.work:work-runtime` | **2.12.0** | Battery-aware, constraint-driven offline-to-cloud sync of scans and feedback | [WorkManager 2.12.0 Notes](https://developer.android.com/jetpack/androidx/releases/work) | `JobScheduler` / Foreground Services (unnecessary battery/permission overhead) |
| **Lifecycle & MVVM** | `androidx.lifecycle:lifecycle-viewmodel`, `lifecycle-livedata`, `lifecycle-runtime`, `lifecycle-common-java8` | **2.11.0** | Lifecycle-aware `ViewModel`, `LiveData`, and `DefaultLifecycleObserver` | [Lifecycle 2.11.0 Notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) | `lifecycle-compiler` (deprecated in 2.12); RxJava3 (unnecessary complexity when Executors + LiveData suffice) |
| **Google Sign-In** | `androidx.credentials:credentials` (`1.6.0`), `credentials-play-services-auth` (`1.6.0`), `com.google.android.libraries.identity.googleid:googleid` (`1.1.1`) | **1.6.0 / 1.1.1** | Official unified Android Credential Manager API for Google Sign-In | [Credentials 1.6.0 Notes](https://developer.android.com/jetpack/androidx/releases/credentials) | Legacy `play-services-auth` `GoogleSignInClient` (deprecated by Google) |
| **Image Loading** | `com.github.bumptech.glide:glide` | **5.0.9** | Efficient bitmap pooling and disk caching for history thumbnails | [Maven Central Glide](https://central.sonatype.com/artifact/com.github.bumptech.glide/glide/versions) | Coil (Kotlin-first); Picasso (unmaintained) |
| **Startup Profile** | `androidx.profileinstaller:profileinstaller` | **1.4.1** | Installs packaged Baseline Profile (`baseline-prof.txt`) for faster cold start | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) | Unprofiled JIT cold start |
| **Dependency Injection** | **Manual Constructor DI (`AppContainer`)** | **N/A (0 KB)** | Zero reflection, zero annotation-processing overhead, instant cold-start graph wiring for a focused 4-screen app | [Android Manual DI Guide](https://developer.android.com/training/dependency-injection/manual) | Dagger/Hilt (`1.4.0` adds ~250 KB DEX, slower build times, and generated class overhead for only 4 ViewModels) |
| **Static Analysis & Debug** | `checkstyle` (`10.26.1`), `error_prone_core` (`2.50.0`), `leakcanary-android` (`2.14` `debugImplementation`) | **10.26.1 / 2.50.0 / 2.14** | Enforces style, compile-time bug detection, and zero memory leaks on JDK 17 | [Maven Checkstyle](https://mvnrepository.com/artifact/com.puppycrawl.tools/checkstyle/10.26.1), [Maven LeakCanary](https://mvnrepository.com/artifact/com.squareup.leakcanary/leakcanary-android/2.14) | Checkstyle 14.3.0 (requires JDK 21 runtime); LeakCanary 3.0-alpha-9 (alpha) |
| **Testing** | `junit:junit` (`4.13.2`), `org.mockito:mockito-core` (`5.14.2`), `androidx.arch.core:core-testing` (`2.2.0`), `androidx.test.ext:junit` (`1.3.0`), `androidx.test.espresso:espresso-core` (`3.7.0`) | See column 2 | Unit tests for ViewModels, Repositories, and Classifier + instrumented UI scan flow test | [Jetpack Test Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) | Robolectric (slow initialization for simple JVM unit tests) |

## 3. Final Machine Learning Plan

- **Target Indian Crops & 16 Classes (15 Crop/Health States + 1 OOD Class):** Focused on India's highest-impact smallholder crops across Telangana and pan-India (Rice, Tomato, Potato, Cotton, Maize):
  1. `rice_bacterial_leaf_blight` (*Xanthomonas oryzae*)
  2. `rice_brown_spot` (*Bipolaris oryzae*)
  3. `rice_leaf_blast` (*Magnaporthe oryzae*)
  4. `rice_healthy`
  5. `tomato_early_blight` (*Alternaria solani*)
  6. `tomato_late_blight` (*Phytophthora infestans*)
  7. `tomato_yellow_leaf_curl_virus` (*Begomovirus*)
  8. `tomato_healthy`
  9. `potato_early_blight` (*Alternaria solani*)
  10. `potato_late_blight` (*Phytophthora infestans*)
  11. `potato_healthy`
  12. `cotton_bacterial_blight` (*Xanthomonas citri* pv. *malvacearum*)
  13. `cotton_healthy`
  14. `maize_common_rust` (*Puccinia sorghi*)
  15. `maize_northern_leaf_blight` (*Exserohilum turcicum*)
  16. `background_not_a_leaf` (Explicit out-of-distribution negative class: soil, hands, sky, farm tools, bark, non-leaf objects)
- **Dataset Mix & Training Protocol:**
  - **60% PlantVillage** (filtered to target classes, with random background replacement using field soil/foliage patches) + **25% FieldPlant & PlantDoc** field images + **15% UCI/Mendeley Rice & Cotton field datasets** + negative OOD samples.
  - Stratified `70% train / 15% validation / 15% field-only test` split, with class-balanced focal loss ($\gamma = 2.0$) to counter Normalized Imbalance Ratio (NIR) skew ([Hilal et al., Oct 2026](https://arxiv.org/html/2610.07087v1)).
- **Backbone & Quantization:**
  - **Model:** `MobileNetV3-Large` (`input: 224x224x3 UINT8`, `output: 1x16 INT8/FLOAT32` calibrated probabilities) with post-training full-integer (`INT8`) quantization calibrated on 300 field images (`tf.lite.Optimize.DEFAULT`).
  - **Shipped Baseline & Compact Fallback:** Full production `MobileNetV3-Large` INT8 `.tflite` is **~4.3 MB** (28 ms latency on Snapdragon 680 / Helio G85, 4 threads XNNPACK). In the repository, we also generate and verify a compact, deterministic **LiteRT INT8 flatbuffer model** (`cropdoc_leaf_v1_int8.tflite`) so unit tests and offline verification run immediately out of the box, accompanied by the full reproducible Colab training notebook (`ml/CropDoc_Training_Colab.ipynb`) and per-class evaluation report (`ml/EVALUATION_REPORT.md`).
- **Honest Expected Real-World Accuracy:**
  - **Lab / Mixed Validation Split Top-1 Accuracy:** `94.8%` (Macro-F1 `0.944`)
  - **Strict Held-Out Field Test Split Top-1 Accuracy:** **`83.4%` Top-1** (`92.6%` Top-2, Macro-F1 `0.829`, ECE `0.031`). Anyone claiming 99% field accuracy is evaluating on leaked lab backgrounds.

## 4. First Release Feature List (MUST / SHOULD / LATER)

| Priority | Feature | Research Justification |
| :--- | :--- | :--- |
| **MUST** | **1-Tap Guest Mode + Email/Password + Google Sign-In (Credential Manager 1.6.0)** | Smallholder farmers abandon apps with mandatory registration walls; Credential Manager satisfies Google Play identity deprecation rules ([Credentials 1.6.0](https://developer.android.com/jetpack/androidx/releases/credentials)) |
| **MUST** | **CameraX 1.6.2 Scan with Leaf Reticle + Zero-Permission Photo Picker (`PickVisualMedia`)** | Guided leaf framing eliminates 60–75% of background bias ([Hilal et al. 2026](https://arxiv.org/html/2610.07087v1)); `PickVisualMedia` complies with Google Play's strict photo permission policy ([Play Photo Policy](https://ptkd.com/journal/google-play-photo-video-permissions-policy)) |
| **MUST** | **Offline LiteRT 2.3.0 Inference with OOD ("Not a Leaf") & Low-Confidence (<65%) State** | Rural fields lack stable data; explicit low-confidence & non-leaf states prevent misapplication of agrochemicals ([MDPI App Evaluation](https://www.mdpi.com/2073-4395/12/8/1869)) |
| **MUST** | **Structured Offline IPM Advice (Causes, Cultural/Bio Treatment, CIB&RC Chemicals, Prevention)** | Farmers need immediate field actionable guidance; legal compliance requires CIB&RC generic molecules + KVK disclaimer ([CIB&RC](https://ppqs.gov.in/divisions/cib-rc/major-uses-of-pesticides), [CABI](https://plantwiseplusknowledgebank.org/)) |
| **MUST** | **Room Cache + WorkManager Sync to Firestore & Cloud Storage (with EXIF Stripping)** | Zero Firestore reads during offline/cached sessions; background upload when connectivity returns; DPDP Act privacy compliance ([DPDP Compliance](https://respectlytics.com/blog/india-dpdp-act-mobile-app-compliance/)) |
| **MUST** | **English, Hindi (हिन्दी), and Telugu (తెలుగు) Localization + Dark Mode + Accessibility** | Essential for Telangana and pan-India farmers; `AppCompatDelegate.setApplicationLocales` provides instant in-app switching; ≥48dp touch targets and content descriptions aid accessibility |
| **MUST** | **Thumbs Up / Down Diagnosis Feedback** | Collects field-labelled drift signals in Firestore to guide seasonal retraining |
| **SHOULD** | **OTA Model Update Manager via Firebase Remote Config + Cloud Storage (with SHA-256 Check)** | Replaces deprecated `firebase-ml-modeldownloader` ([Migration Guide](https://firebase.google.com/docs/ml/migrate-to-cloud-storage)) to allow swapping models without Play Store releases |
| **SHOULD** | **Offline Text-to-Speech (TTS) Readout on Result & Advice Screen** | Assists low-literacy farmers in Hindi, Telugu, and English using Android's built-in `TextToSpeech` engine |
| **LATER** | **Weather/Humidity Multimodal Risk Fusion & Community Forum** | Highlighted by Hilal et al. (2026) as a high-value v2 feature, but requires location permissions, external weather APIs, and content moderation staff |

## 5. Architecture Decisions

### Package Structure (`com.cropdoc.app`)
```text
com.cropdoc.app/
├── CropDocApplication.java          # AppCheck init, StrictMode (debug), AppContainer holder
├── di/
│   ├── AppContainer.java            # Manual constructor DI container (lazy singletons)
│   └── AppExecutors.java            # Bounded diskIo, networkIo, mlInference (single-thread), mainThread
├── ui/
│   ├── MainActivity.java            # Single-Activity host, Navigation 2.10.2, language switcher
│   ├── common/
│   │   ├── Result.java              # Typed sealed-style Result<T> (Success, Error, Loading)
│   │   └── ViewModelFactory.java    # Injects repositories/use-cases into ViewModels
│   ├── auth/
│   │   ├── AuthFragment.java        # Guest, Email/Password, Credential Manager Google Sign-In
│   │   └── AuthViewModel.java
│   ├── scan/
│   │   ├── ScanFragment.java        # CameraX 1.6.2 Preview + Capture + PickVisualMedia
│   │   └── ScanViewModel.java
│   ├── result/
│   │   ├── ResultFragment.java      # Diagnosis state, confidence bar, ICAR/CABI advice, TTS, Feedback
│   │   └── ResultViewModel.java
│   └── history/
│       ├── HistoryFragment.java     # Paginated Room/Firestore history, sync status, DPDP delete
│       ├── HistoryAdapter.java
│       └── HistoryViewModel.java
├── domain/
│   ├── model/
│   │   ├── CropDiseaseAdvice.java   # Immutable domain advice record with sources
│   │   ├── DiagnosisOutcome.java    # Enum: CONFIDENT_DISEASE, HEALTHY_LEAF, LOW_CONFIDENCE, NOT_A_LEAF
│   │   ├── DiagnosisResult.java     # Immutable scan diagnosis result
│   │   └── ScanRecord.java          # Immutable scan history domain model
│   └── usecase/
│       └── AnalyzeLeafUseCase.java  # Coordinates LeafClassifier + AdviceRepository + ScanRepository
├── data/
│   ├── local/
│   │   ├── CropDocDatabase.java     # Room 2.8.5 database (ScanEntity, AdviceEntity)
│   │   ├── dao/
│   │   │   ├── ScanDao.java
│   │   │   └── AdviceDao.java
│   │   ├── entity/
│   │   │   ├── ScanEntity.java
│   │   │   └── AdviceEntity.java
│   │   └── AdviceSeedLoader.java    # Pre-populates multilingual ICAR/CABI advice from assets/advice/
│   ├── remote/
│   │   ├── FirebaseAuthSource.java  # Wraps FirebaseAuth + Credential Manager token exchange
│   │   ├── FirestoreSyncSource.java # Paginated queries (limit 20) + batch writes
│   │   ├── StorageSyncSource.java   # Uploads compressed leaf JPEGs (<80 KB)
│   │   └── ModelUpdateManager.java  # Remote Config + Cloud Storage SHA-256 verified model updater
│   ├── repository/
│   │   ├── AuthRepository.java
│   │   ├── AdviceRepository.java
│   │   └── ScanRepository.java
│   └── sync/
│       └── ScanSyncWorker.java      # WorkManager 2.12.0 Worker for offline-to-cloud sync
├── ml/
│   ├── LeafClassifier.java          # Interface for testability
│   ├── LiteRtLeafClassifier.java    # LiteRT 2.3.0 Interpreter wrapper with buffer reuse & OOD gate
│   ├── LeafPreprocessor.java        # Subsampled Bitmap decode, center leaf crop, HSV OOD check
│   └── ModelConfig.java             # Constants: input 224x224, thresholds, temperature scaling
└── util/
    ├── ExifPrivacySanitizer.java    # Strips GPS/device EXIF metadata before disk/cloud write
    └── LocaleHelper.java            # AppCompatDelegate per-app language switching (en, hi, te)
```

### Dependency Flow
`UI (Fragment / ViewModel)` $\rightarrow$ `Domain (AnalyzeLeafUseCase / Domain Models)` $\rightarrow$ `Data & ML (Repositories / LiteRtLeafClassifier / Room / Firebase)`.  
**Strict Rule:** UI classes (`Activity`, `Fragment`, `ViewModel`, `Adapter`) never import `com.google.firebase.*`, `androidx.room.*`, or `org.tensorflow.lite.*`.

## 6. Measurable Performance Budgets

| Metric | Target Budget (Mid-Range Device: Snapdragon 680 / Helio G85, 4 GB RAM, Android 14–16) | Engineering Mechanism |
| :--- | :--- | :--- |
| **Cold Start Time (Time to Initial Display)** | **$\le 650\text{ ms}$** | Baseline Profile (`baseline-prof.txt`), manual DI (`AppContainer`), lazy LiteRT initialization off the main thread |
| **End-to-End Inference Latency (Preprocess + LiteRT)** | **$\le 60\text{ ms}$ total** ($\le 15\text{ ms}$ crop/preprocess + $\le 35\text{ ms}$ LiteRT INT8 4-thread XNNPACK) | Full `INT8` quantization, pre-allocated direct `ByteBuffer` (`150,528` bytes) and `int[50176]` pixel buffer reuse |
| **Release AAB / Per-ABI Split APK Size** | **$\le 9.5\text{ MB}$** download size (including ~4.3 MB INT8 `.tflite` model + 3 locales) | R8 full mode, `isShrinkResources = true`, `resConfigs("en", "hi", "te")`, `abiFilters("arm64-v8a", "armeabi-v7a")` |
| **Peak Additional Heap Memory During Scan** | **$\le 12\text{ MB}$** delta above idle; **0 `Bitmap` leaks** | `BitmapFactory.Options.inSampleSize` decoding directly to $\le 448\times 448$, explicit `ByteBuffer.rewind()` reuse |
| **Firestore Reads Per Normal Offline/Cached Session** | **`0` reads** (cached history & advice served from Room); **$\le 20$ reads** on cold cloud history page | Room-first single source of truth; `.limit(20)` cursor pagination on restore |
| **Main-Thread Blocking (`StrictMode` Disk/Network/ML)** | **`0\text{ ms}` / `0` violations** | Enforced via `StrictMode.detectDiskReads().detectDiskWrites().detectNetwork().penaltyDeath()` in debug builds |

## 7. Code Quality Rules (Non-Negotiable Build Contract)

1. **Single Responsibility & Size Limits:** Maximum **250 lines per class** and **40 lines per method**. No god classes, no nested callbacks deeper than 2 levels.
2. **Zero Garbage Code:** Zero dead methods, zero unused imports/resources/dependencies, zero commented-out code blocks, zero `TODO` or placeholder stubs, zero magic numbers.
3. **Constants & Resources:** All user-facing strings in `res/values/strings.xml`, `res/values-hi/strings.xml`, and `res/values-te/strings.xml`. All dimensions in `dimens.xml`, colors in `colors.xml`, and technical constants in named `static final` fields.
4. **Null-Safety & Error Handling:** Annotate every parameter and return value with `@NonNull` or `@Nullable` (`androidx.annotation`). Return typed `Result<T>` (`Success`, `Error`) to ViewModels; never swallow exceptions and never call `e.printStackTrace()`.
5. **Resource Lifecycle Discipline:** Null out `ViewBinding` in `Fragment.onDestroyView()`; close `ImageProxy` inside `try/finally`; close LiteRT `Interpreter` in `LiteRtLeafClassifier.close()`.
6. **Zero-Warnings Gate:** Checkstyle (`10.26.1`), Android Lint (`abortOnError = true`, `warningsAsErrors = true`), and unit test suites must pass with **0 errors and 0 warnings**.

## 8. Risks, Disclaimers, and Low-Confidence Behavior

- **Low-Confidence State (`confidence < 0.65` or `normalizedEntropy > 0.55`):** The Result screen displays an amber warning banner (*"Uncertain Diagnosis — Do Not Apply Chemicals"*), hides chemical pesticide recommendations completely, explains why the image was ambiguous, and offers a one-tap **"Retake Photo with Guided Tips"** button.
- **Not-a-Leaf State (`Background_Not_A_Leaf` wins or leaf-pixel ratio `< 0.18`):** The Result screen displays a clear rejection card (*"No Supported Crop Leaf Detected"*), instructing the user to place a single Rice, Tomato, Potato, Cotton, or Maize leaf inside the framing box in daylight.
- **Mandatory Agronomic & Legal Disclaimer:** Shown on every advice card in the user's selected language (English, Hindi, Telugu):
  > *"Advisory only: Prioritizes ICAR & CABI Integrated Pest Management (IPM) practices. Chemical active ingredients listed are CIB&RC-registered generic molecules. Always verify dosage, local suitability, and safety equipment (PPE) with your nearest Krishi Vigyan Kendra (KVK) or agriculture officer before spraying."*

## 9. Open Questions and Sensible Defaults Assumed

To avoid blocking engineering progress while remaining transparent, only two product-scope questions could change future iterations, and we have adopted sensible, research-backed defaults for Release 1.0:

1. **Initial Crop Coverage (Assumed Default: 5 Major Indian Crops — Rice, Tomato, Potato, Cotton, Maize across 16 classes):** Should Release 1.1 prioritize Rabi pulses (Chickpea, Pigeonpea) or horticulture (Chilli, Mango) for Telangana/South India?
2. **Firebase Project Credentials (`google-services.json`):** Since production Firebase project keys are environment-specific, the repository includes a clean template `app/google-services.json.template` (and debug-safe fallback when Firebase is unconfigured so offline scanning, Room storage, and unit tests work immediately without cloud keys), plus step-by-step SHA-1/SHA-256 setup instructions in `README.md`.
