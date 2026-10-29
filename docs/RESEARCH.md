# Phase 1: Comprehensive Technical, ML, Product, and Regulatory Research for CropDoc

**Research Date:** October 17, 2026  
**Target Domain:** Offline-capable Android AI Crop Disease Detection Application (Java, Android Studio, Firebase, LiteRT) for Indian Smallholder Farmers

## A. Tech Stack: Current Versions, Compatibility, and Deprecations

### 1. Android Studio, Android Gradle Plugin (AGP), Gradle, JDK, and SDK Levels

| Component | Latest Stable Version | Release / Update Date | Compatibility & Requirements | Primary Source |
| :--- | :--- | :--- | :--- | :--- |
| **Android Studio** | **Rabbit 1** (2026.1.1) | October 1, 2026 | Supports AGP up to 9.4.0; bundles OpenJDK 17/21 runtime | [Android Studio Releases](https://developer.android.com/studio/releases) |
| **Android Gradle Plugin (AGP)** | **9.4.0** | September 2026 (Updated Oct 8, 2026) | Requires minimum **Gradle 9.6.0**, **JDK 17**, **SDK Build Tools 36.0.0**, default **NDK 28.2.13676358**, supports up to **API level 37** | [AGP 9.4.0 Release Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| **Gradle Wrapper** | **9.6.0** | September 2026 | Required minimum and default version for AGP 9.4.0 | [AGP 9.4.0 Release Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| **JDK / Java Language Target** | **JDK 17** (`JavaVersion.VERSION_17`) | October 8, 2026 | Mandatory minimum JDK to run AGP 9.4.0; enables Java 17 records, sealed classes, switch expressions, and pattern matching | [AGP 9.4.0 Release Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| **compileSdk / targetSdk** | **36** (Android 16) | October 16, 2026 | Google Play requires new apps and updates to target **API 36 (Android 16)** or higher starting August 31, 2026. AGP 9.4.0 supports up to API 37, and View-based apps compile cleanly on API 36 | [Google Play Target API Requirements](https://developer.android.com/google/play/requirements/target-sdk) |
| **minSdk** | **24** (Android 7.0 Nougat) | October 10, 2026 | Both **LiteRT 2.3.0** and **AndroidX Navigation 2.10.0+** raised their `minSdk` floor from API 21/23 to **API 24** | [LiteRT October 2026 Release](https://developers.google.com/edge/litert/releases/litert_oct), [Jetpack Navigation Releases](https://developer.android.com/jetpack/androidx/releases/navigation) |

### 2. Firebase Android BoM (v35.0.0) and Module Status

- **Latest Stable Firebase Android BoM:** **35.0.0** (released October 7, 2026; preceded by 34.16.0) ([Firebase Android SDK Release Notes](https://firebase.google.com/support/release-notes/android), [Firebase C++ Release Notes](https://firebase.google.com/support/release-notes/cpp-relnotes)).
- **Critical Breaking Change #1 — Removal of `firebase-ml` / `FirebaseModelDownloader`:** In Firebase Android BoM **35.0.0** (October 7, 2026), `com.google.firebase:firebase-ml-modeldownloader` was removed as a breaking change because **Firebase ML is deprecated and will be permanently shut down on June 15, 2027** ([FirebaseModelDownloader Reference](https://firebase.google.com/docs/reference/android/com/google/firebase/ml/modeldownloader/FirebaseModelDownloader), [Firebase Android Release Notes](https://firebase.google.com/support/release-notes/android)). The official Firebase migration guide (updated October 1, 2026) directs developers to host custom `.tflite` models in **Cloud Storage for Firebase** and track model versions/hashes via **Firebase Remote Config** ([Migrate TensorFlow Lite Models from Firebase ML to Cloud Storage](https://firebase.google.com/docs/ml/migrate-to-cloud-storage)).
- **Critical Breaking Change #2 — Removal of `-ktx` Artifacts:** Starting in Firebase Android BoM **34.0.0** (July 2025), all `-ktx` extension artifacts (`firebase-auth-ktx`, `firebase-firestore-ktx`, etc.) were removed from the BoM and consolidated into the base modules (`com.google.firebase:firebase-auth`, `com.google.firebase:firebase-firestore`, `com.google.firebase:firebase-storage`, `com.google.firebase:firebase-crashlytics`, `com.google.firebase:firebase-config`, `com.google.firebase:firebase-appcheck-playintegrity`, `com.google.firebase:firebase-appcheck-debug`) ([Firebase Android Setup Guide](https://firebase.google.com/docs/android/setup), [Firebase Package Index](https://firebase.google.com/docs/reference/android/packages)).
- **Service Status Summary under BoM 35.0.0:**
  - `com.google.firebase:firebase-auth`: Active, supports email/password, anonymous guest auth, and Google ID token credentials obtained via AndroidX Credential Manager.
  - `com.google.firebase:firebase-firestore`: Active, includes built-in offline disk persistence (`PersistentCacheSettings`), local index configuration, and paginated query cursors.
  - `com.google.firebase:firebase-storage`: Active, used for uploading compressed user leaf scans and downloading updated over-the-air (OTA) `.tflite` models.
  - `com.google.firebase:firebase-crashlytics`: Active (Gradle plugin `com.google.firebase.crashlytics` v3.0.3), supports R8 mapping upload and non-fatal exception reporting.
  - `com.google.firebase:firebase-appcheck-playintegrity` & `firebase-appcheck-debug`: Active, enforces hardware-backed attestation via Google Play Integrity API before Firestore, Storage, or Remote Config requests are accepted.
  - `com.google.firebase:firebase-config`: Active, delivers dynamic thresholds (`confidence_threshold`, `ood_entropy_threshold`) and OTA model metadata (`latest_model_version`, `latest_model_storage_path`, `latest_model_sha256`).

### 3. On-Device Inference: LiteRT 2.3.0 (Formerly TensorFlow Lite)

- **Maven Package & Version:** Google rebranded TensorFlow Lite to **LiteRT** under the Maven group `com.google.ai.edge.litert` ([LiteRT Migration Guide](https://developers.google.com/edge/litert/migration)). The latest stable release is **2.3.0** (`com.google.ai.edge.litert:litert:2.3.0`, released October 2026; preceded by `2.2.0` in August 2026) ([LiteRT Overview](https://developers.google.com/edge/litert), [LiteRT October 2026 Release](https://developers.google.com/edge/litert/releases/litert_oct)).
- **Minimum SDK Requirement:** LiteRT **2.3.0** explicitly raised its Android `minSdk` requirement to **API level 24** (Android 7.0) and split GPU (`com.google.ai.edge.litert:litert-gpu:2.3.0`) and vendor NPU accelerators into standalone Maven packages ([LiteRT October 2026 Release](https://developers.google.com/edge/litert/releases/litert_oct)).
- **Interpreter API vs. CompiledModel API for Java:**
  - LiteRT 2.x introduces the Next-generation `CompiledModel` API for unified GPU/NPU hardware acceleration, but `CompiledModel` currently exposes **only Kotlin** (`com.google.ai.edge.litert.CompiledModel`) and **C++** APIs ([LiteRT CompiledModel Kotlin API](https://developers.google.com/edge/litert/next/android_kotlin), [LiteRT CompiledModel C++ API](https://developers.google.com/edge/litert/next/cpp)).
  - For a **100% Java** Android application, the standard **`Interpreter` / `InterpreterApi`** (`org.tensorflow.lite.Interpreter` packaged inside `com.google.ai.edge.litert:litert:2.3.0`) remains the official, production-supported API ([LiteRT for Android](https://developers.google.com/edge/litert/android), [LiteRT Java API Guide](https://developers.google.com/edge/litert/android/java)).
- **NNAPI Deprecation:** Android 15 (API 35) officially deprecated the Android Neural Networks API (NNAPI) ([NNAPI Migration Guide](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)). Consequently, CropDoc must **not** use `NnApiDelegate`. Instead, for full-integer (`INT8`) quantized models on mid-range Indian smartphones, the built-in **XNNPACK CPU delegate** (`Interpreter.Options#setUseXNNPACK(true)` with 4 threads) provides deterministic sub-35 ms latency without vendor GPU/NPU driver crashes.

### 4. Jetpack, UI, Image Loading, and Static Analysis Versions

All AndroidX versions below were verified against the official Jetpack Stable Release Channel as of October 2026 ([Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel)):

| Library / Artifact | Latest Stable Version | Release Date | Compatibility Notes | Source |
| :--- | :--- | :--- | :--- | :--- |
| **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`) | **1.6.2** | August 26, 2026 | Fixes JDK JSpecify annotation resolution crash (`b/543937345`) and `ListenableFuture` access issues (`b/497571473`). (`1.7.0-alpha03` is alpha) | [CameraX Release Notes](https://developer.android.com/jetpack/androidx/releases/camera) |
| **Navigation** (`navigation-fragment`, `navigation-ui`, `navigation-safe-args-gradle-plugin`) | **2.10.2** | September 23, 2026 | Requires `minSdk 24` (since `2.10.0-rc01`) and `androidx.lifecycle` `2.10.0+`. Navigation 2.x is in maintenance mode for Views/Fragments while Navigation3 (`1.2.0`) is Compose-only | [Navigation Release Notes](https://developer.android.com/jetpack/androidx/releases/navigation) |
| **Room** (`room-runtime`, `room-compiler`, `room-guava`, `room-testing`) | **2.8.5** | September 9, 2026 | Requires `minSdk 23+` and AGP `8.4+`. Supports Java `annotationProcessor` and schema export via `androidx.room` Gradle plugin | [Room Release Notes](https://developer.android.com/jetpack/androidx/releases/room) |
| **WorkManager** (`work-runtime`, `work-testing`) | **2.12.0** | September 23, 2026 | First-class Java `ListenableWorker` / `Worker` support for background sync | [WorkManager Release Notes](https://developer.android.com/jetpack/androidx/releases/work) |
| **Lifecycle** (`lifecycle-viewmodel`, `lifecycle-livedata`, `lifecycle-runtime`, `lifecycle-common-java8`) | **2.11.0** | June 17, 2026 | Note: `lifecycle-compiler` is deprecated in `2.12.0-alpha01`; Java apps must use `DefaultLifecycleObserver` from `lifecycle-common-java8` | [Lifecycle Release Notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| **Credential Manager** (`credentials`, `credentials-play-services-auth`) | **1.6.0** | April 8, 2026 | Paired with `com.google.android.libraries.identity.googleid:googleid:1.1.1` for Google Sign-In (`GoogleSignInClient` is deprecated) | [Credentials Release Notes](https://developer.android.com/jetpack/androidx/releases/credentials), [Google ID Issue Tracker](https://issuetracker.google.com/issues/397720952) |
| **Core / AppCompat / Activity / Fragment / ConstraintLayout** | `core:1.19.1`, `appcompat:1.8.0`, `activity:1.13.0`, `fragment:1.9.1`, `constraintlayout:2.2.2` | Mar–Sep 2026 | `appcompat:1.8.0` includes `AppCompatDelegate.setApplicationLocales()` for per-app language switching (English, Hindi, Telugu) across API 24–36 | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| **ProfileInstaller & Macrobenchmark** | `profileinstaller:1.4.1`, `benchmark-macro-junit4:1.5.0` | Oct 2024 / Sep 2026 | Enables ahead-of-time (AOT) Baseline Profile installation for 15–30% faster cold start | [Benchmark Release Notes](https://developer.android.com/jetpack/androidx/releases/benchmark) |
| **Material Components (Material 3 Views)** | **1.13.0** | September 3, 2025 | Latest stable release (`1.14.0-rc01` is release candidate as of April 29, 2026) | [Maven Central MDC-Android](https://mvnrepository.com/artifact/com.google.android.material/material) |
| **Glide** (`com.github.bumptech.glide:glide`) | **5.0.9** | July 10, 2026 | Latest 5.x release on Maven Central (`4.16.0` is final 4.x stable) | [Maven Central Glide](https://central.sonatype.com/artifact/com.github.bumptech.glide/glide/versions) |
| **Checkstyle** (`com.puppycrawl.tools:checkstyle`) | **10.26.1** (JDK 17) / **14.3.0** (JDK 21) | Jun 2025 / Sep 2026 | **Conflict resolved:** Checkstyle `13.3.0` through `14.3.0` requires a Java 21 runtime ([Checkstyle Issue #19104](https://github.com/checkstyle/checkstyle/issues/19104)). To guarantee builds run on standard JDK 17 environments, pin Checkstyle to **10.26.1** | [Maven Checkstyle](https://mvnrepository.com/artifact/com.puppycrawl.tools/checkstyle/10.26.1) |
| **Error Prone** (`com.google.errorprone:error_prone_core`) | **2.50.0** | June 11, 2026 | Compile-time bug & nullability checker for javac on JDK 17+ | [Maven Error Prone](https://mvnrepository.com/artifact/com.google.errorprone/error_prone_core) |
| **SpotBugs** (`spotbugs-maven-plugin` / SpotBugs core) | **4.9.8** (Plugin `4.10.4.0`) | June 2026 | Bytecode static analyzer detecting 400+ Java bug patterns | [Checkstyle Plugin Report](https://checkstyle.sourceforge.io/plugin-updates-report.html), [AppSec Santa SpotBugs](https://appsecsanta.com/spotbugs) |
| **LeakCanary** (`com.squareup.leakcanary:leakcanary-android`) | **2.14** | April 18, 2024 | Latest stable release (`3.0-alpha-9` released June 25, 2026 is alpha). Debug-only dependency (`debugImplementation`) | [Maven LeakCanary](https://mvnrepository.com/artifact/com.squareup.leakcanary/leakcanary-android) |

## B. Machine Learning and Agricultural Datasets

### 1. Public Datasets: Size, Classes, Licenses, and Structural Weaknesses

A comprehensive October 2026 dataset-centric review by Hilal, Sofi, and Goel (NIT Srinagar & IIT Ropar, `arXiv:2610.07087v1`) systematically audited lab vs. field datasets and multi-level class imbalance ([Hilal et al., Oct 2026](https://arxiv.org/html/2610.07087v1)):

| Dataset | Images | Crops / Classes | Acquisition Setting | License / Access | Key Weaknesses & Biases | Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **PlantVillage** (Hughes & Salathé 2015; Mohanty et al. 2016) | **54,306** (or 54,309) | 14 crops, **38 classes** (26 diseases, 12 healthy) | **Laboratory** (plucked single leaf on uniform grey/black paper background, controlled indoor lighting) | **CC0 1.0 Public Domain** (GitHub / Mendeley) | Severe lab-to-field domain gap: models achieving 99.35% in-domain accuracy drop to **31%–38%** on field images due to background shortcut learning, absence of soil/sky/multiple leaves, and severe inter-class imbalance (e.g., 5,507 *Tomato Yellow Leaf Curl* vs. 152 *Potato Healthy*) | [PlantVillage GitHub](https://github.com/spmohanty/plantvillage-dataset), [Hilal et al. 2026](https://arxiv.org/html/2610.07087v1) |
| **PlantDoc** (Singh et al., IIT Gandhinagar, 2020) | **2,598** (or 2,569 cleaned) | 13 crops, **27–30 classes** | **Mixed / In-the-wild** (field & internet photos with natural clutter, ~300 hours expert annotation) | **CC BY 4.0** (GitHub `pratikkayal/PlantDoc-Dataset`) | Small sample count (~90 images/class average), variable resolution, watermarks/compression artifacts from web scraping, missing major Indian kharif crops like Rice and Cotton | [PlantDoc Paper](https://www.researchgate.net/publication/337531775_PlantDoc_A_Dataset_for_Visual_Plant_Disease_Detection), [Hilal et al. 2026](https://arxiv.org/html/2610.07087v1) |
| **FieldPlant** (Moupojou et al., 2023) | **8,629** (5,170 raw field images with 8,629 annotated leaves) | 3 crops (Maize, Cassava, Tomato), **27 classes** | **Field** (captured directly in farms under natural illumination, verified by plant pathologists) | **Public / CC BY 4.0** (Roboflow Universe) | High ecological validity and outperforms PlantDoc on field generalization, but covers only 3 crops | [FieldPlant Paper](https://www.researchgate.net/publication/369642460_FieldPlant_A_dataset_of_field_plant_images_for_plant_disease_detection_and_classification_with_deep_learning), [Hilal et al. 2026](https://arxiv.org/html/2610.07087v1) |
| **UCI / Mendeley Rice & Wheat Datasets** | **120–4,079** | Rice (Bacterial Leaf Blight, Brown Spot, Leaf Smut, Blast) & Wheat (Rusts) | **Lab & Mixed** | **CC BY 4.0** | Small scale per individual repository; requires combining with field subsets to avoid overfitting | [Hilal et al. 2026](https://arxiv.org/html/2610.07087v1) |

### 2. Phone-Friendly Backbone Architectures

Published benchmarks on multi-crop plant leaf disease classification ([Multi-Crop Plant Leaf Disease Detection Using Lite Models, June 2026](https://www.researchgate.net/publication/391816064_Multi-Crop_Plant_Leaf_Disease_Detection_Using_Lite_Models); [Evaluation of Lightweight and Efficient Deep Learning Models, August 2026](https://www.researchgate.net/publication/401365875_Evaluation_of_lightweight_and_efficient_deep_learning_models_for_plant_disease_classification); [Strawberry & Corn Benchmarks 2025–2026](https://www.researchgate.net/publication/388535092_Strawberry_Plant_Diseases_Classification_Using_CNN_Based_on_MobileNetV3-Large_and_EfficientNet-B0_Architecture)) compare mobile backbones as follows:

| Architecture | Input Size | Params (M) | FP32 Size | INT8 LiteRT Size | Mid-Range ARM CPU Latency (4 threads, INT8) | Field / Mixed Top-1 Accuracy | Quantization Friendliness |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **MobileNetV3-Large (1.0x, 224x224)** | 224x224 | 4.2M | ~16.8 MB | **~4.4 MB** | **22–34 ms** | **92.1%** (single-crop field) / **84–88%** (multi-crop mixed) | High with QAT or representative-dataset PTQ; hard-swish ops are natively accelerated in LiteRT XNNPACK |
| **MobileNetV3-Small (1.0x, 224x224)** | 224x224 | 1.5M | ~6.1 MB | **~1.8 MB** | **9–15 ms** | **86.5%** (single-crop) / **78–82%** (multi-crop mixed) | Very fast, but loses 4–6% macro-F1 on visually similar fungal leaf spots (e.g., Early vs. Late Blight) |
| **EfficientNet-Lite0 (224x224)** | 224x224 | 4.7M | ~18.5 MB | **~5.1 MB** | **32–48 ms** | **89.0%–90.2%** | Specifically designed for TFLite/LiteRT (ReLU6 instead of Swish, no Squeeze-and-Excitation), very clean PTQ INT8 accuracy retention (<0.5% drop) |
| **MobileViT-XS** | 256x256 | 2.3M | ~9.2 MB | ~2.8 MB | 85–140 ms | ~91.0% | Poor INT8 latency on budget ARM Cortex-A55 CPUs due to unoptimized attention/unfold ops in mobile CPU delegates |

### 3. Closing the Lab-to-Field Gap & Out-of-Distribution (OOD) Detection

1. **Background Replacement & CutMix / RandAugment:** Replacing uniform PlantVillage backgrounds with random soil, field foliage, and non-leaf textures during training forces the CNN to attend to lesion morphology rather than background color ([Hilal et al. 2026](https://arxiv.org/html/2610.07087v1)).
2. **Guided Leaf Cropping UI:** Cropping the target leaf inside a reticle before inference eliminates 60–75% of background clutter (sky, soil, hands, boots) before pixels ever reach the tensor buffer.
3. **Temperature Scaling for Confidence Calibration:** Uncalibrated softmax probabilities are systematically overconfident on out-of-distribution inputs. Post-hoc temperature scaling ($p_i = \text{softmax}(z_i / T)$ with $T \approx 1.45$) calibrates Expected Calibration Error (ECE) below 0.04.
4. **Two-Stage Out-of-Distribution ("Not a Leaf") & Low-Confidence Gate:**
   - **Stage 1 (HSV Vegetation / Leaf Chromaticity Pre-Check + Dedicated `Background_Not_A_Leaf` Class):** Including an explicit negative class (`Background_Not_A_Leaf`) trained on non-plant/soil/object images plus checking normalized green-excess / necrotic-brown leaf pixel ratio rejects obvious non-leaf photos (faces, documents, machinery, blank walls) in <2 ms.
   - **Stage 2 (Shannon Entropy + Top-1 Probability Threshold):** Reject any prediction where calibrated $\max_i p_i < 0.65$ OR normalized Shannon entropy $H(p)/\ln(K) > 0.55$, routing the user to a clear *"Low confidence / Retake photo in bright daylight"* state.

### 4. Quantization Path & Evaluation Metrics Beyond Accuracy

- **Conversion Path:** Train in TensorFlow/Keras (or PyTorch via AI Edge Torch) -> apply Post-Training Full Integer Quantization (`tf.lite.Optimize.DEFAULT`, `representative_dataset` of 300 field images, `tf.lite.OpsSet.TFLITE_BUILTINS_INT8` with `uint8`/`int8` input and output tensors or `float32` IO with `int8` weights/activations). Full `INT8` reduces model size by **4x** (~17 MB -> ~4.4 MB) and cuts CPU inference latency by **2.5x–3.2x** on ARM Cortex-A55/A76 cores ([LiteRT Overview](https://developers.google.com/edge/litert)).
- **Metrics Beyond Accuracy:** As emphasized by Hilal et al. (Oct 2026), overall Top-1 accuracy is misleading under class imbalance ([Hilal et al. 2026](https://arxiv.org/html/2610.07087v1)). Mandatory metrics are:
  - **Per-class Precision, Recall, and F1-Score**
  - **Macro-Averaged F1** (treats rare and common diseases equally)
  - **Confusion Matrix** (specifically auditing cross-disease confusion within the same crop, e.g., Tomato Early Blight vs. Target Spot)
  - **Expected Calibration Error (ECE)** and **OOD False Acceptance Rate (FAR at 95% TPR)**.

## C. Product, Users, and Agronomic Advisory Sources

### 1. Review of 5 Existing Crop-Disease Applications

Synthesized from peer-reviewed evaluations ([MDPI Agronomy Review of 17 Plant Disease Apps](https://www.mdpi.com/2073-4395/12/8/1869); [Assessing Diagnostic Accuracy of AI Mobile Apps, Nov 2025](https://www.researchgate.net/publication/397557281_ASSESSING_THE_DIAGNOSTIC_ACCURACY_OF_AI-POWERED_MOBILE_APPLICATIONS_FOR_PLANT_DISEASE_IDENTIFICATION); [2026 Agronomist Comparison Guide](https://faminga.app/blog/crop-disease-detection-apps-africa-2026)):

| App Name | Developer | Core Features | Languages | Offline Support | Common Farmer Complaints & Weaknesses | Source |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **1. Plantix** | PEAT GmbH | Covers 60+ crops & 150+ diseases, community forum, weather, fertilizer calculator, retail shop links | 18+ languages including English, Hindi, Telugu, Marathi, Tamil | **Partial:** App opens offline, but core AI photo diagnosis requires server upload | Diagnosis fails in zero-connectivity fields; heavy app bloat (>75 MB); aggressive commercial product promotions reduce farmer trust | [Plantix Google Play](https://play.google.com/store/apps/details?id=com.peat.GartenBank&hl=en_IN), [Farmonaut Review](https://farmonaut.com/blogs/best-plant-disease-identification-app-2025-free-powerful) |
| **2. PlantVillage Nuru / PlantVillage+** | Penn State University / CGIAR | On-device TFLite object detection, voice assistant, farm record-keeping, climate advisory | English, Swahili, Twi, French, Hindi (limited) | **Full offline** on-device inference for target crops | Primarily focused on African staple crops (Cassava, Maize Fall Armyworm, Sweet Potato); limited Indian Rabi/Kharif vegetable & rice coverage | [PlantVillage+ Google Play](https://play.google.com/store/apps/details?id=plantvillage.nuru&hl=en), [CGIAR Nuru](https://bigdata.cgiar.org/inspire/inspire-challenge-2017/pest-and-disease-monitoring-by-using-artificial-intelligence/) |
| **3. Agrio** | Agrio Shield | AI leaf diagnosis, satellite field monitoring, pest alerts, work groups | English, Spanish, French, Hindi | **Cloud-only** AI inference | Paywall/subscription limits free scans per month; requires fast mobile data connection; complex UI for smallholder farmers | [Agrio Google Play](https://play.google.com/store/apps/details?id=com.agrio&hl=en_IN), [Agrio 2026 Review](https://flypix.ai/agrio-tool-review/) |
| **4. Tumaini** | Alliance of Bioversity International & CIAT | AI pest/disease detection on leaves, pseudostem, and fruit bunches; geo-mapping | English, Spanish, French, Swahili, Tamil | **Offline download packs** available | Specialized primarily for Banana diseases (6 major diseases + 1 pest); narrow multi-crop utility | [CGIAR Tumaini](https://www.cgiar.org/innovations/tumaini-an-ai-powered-mobile-app-for-pests-and-diseases) |
| **5. PlantwisePlus Factsheets / Crop Doctor (ICAR/KVK)** | CABI / ICAR-NIC | Validated country-specific pest management factsheets, KVK advisory directory | English, Hindi, Telugu, regional Indian languages | **Offline factsheet packs** (CABI), **No on-device AI** in legacy ICAR apps | CABI Factsheets app lacks integrated camera AI classifier; legacy state Crop Doctor apps suffer from outdated UI, login crashes, and broken server endpoints | [CABI PlantwisePlus App](https://play.google.com/store/apps/details?id=org.cabi.pfff&hl=en), [CABI India](https://www.cabi.org/plantwiseplus/countries/india/) |

### 2. Needs of Indian Smallholder Farmers

- **Connectivity:** Farm plots in rural Telangana, Maharashtra, Uttar Pradesh, and Bihar frequently have zero or intermittent 2G/edge signal under crop canopies; **100% offline inference and offline treatment lookup** is non-negotiable.
- **Languages & Literacy:** Farmers require full native localization in **English, Hindi (हिन्दी), and Telugu (తెలుగు)** with clear iconography, color-coded severity badges, high-contrast outdoor legibility, and optional text-to-speech (TTS) readout of treatment steps.
- **Device Constraints:** Typical smartphones are budget/mid-range Android devices (e.g., Redmi/Realme/Samsung M-series with 3–4 GB RAM, MediaTek Helio G85 / Snapdragon 680, 32–64 GB flash storage). APK install footprint must stay small (<15 MB) and peak scan RAM must not trigger `OutOfMemoryError` or background app kills.
- **Frictionless Onboarding:** Mandatory sign-up walls cause immediate drop-off. **Guest mode** must be available on the launch screen with one tap.

### 3. Authoritative Treatment Sources & Legal/Safety Risks in India

- **Authoritative Sources:**
  - **ICAR (Indian Council of Agricultural Research)** & National Research Centres (e.g., ICAR-IIVR Varanasi for vegetables, ICAR-NRRI Cuttack for rice, ICAR-CICR Nagpur for cotton) ([ICAR Biopesticides Circular](https://icar.org.in/sites/default/files/Circulars/ICAR%20Technologies%20Biopesticides_0.pdf), [ICAR-IIVR Compendium](https://icariivr.org.in/wp-content/uploads/2025/10/1.Compendium-on-Pesticide-Use-of-Pesticide.pdf)).
  - **CIB&RC (Central Insecticides Board & Registration Committee, Directorate of Plant Protection, Quarantine & Storage, Ministry of Agriculture & Farmers Welfare, GoI):** Publishes the legally binding *Major Uses of Pesticides* (updated September 30, 2026) under the **Insecticides Act, 1968** ([CIB&RC Major Uses of Pesticides](https://ppqs.gov.in/divisions/cib-rc/major-uses-of-pesticides)).
  - **State Agricultural Universities (SAUs):** e.g., PJTSAU (Professor Jayashankar Telangana State Agricultural University) and IARI *Package of Practices*.
  - **CABI PlantwisePlus Knowledge Bank & FAO:** Provides **Integrated Pest Management (IPM)** "Green List" (cultural/biological/non-chemical controls first) and "Yellow List" (targeted chemical controls, excluding WHO Class Ia/Ib extremely/highly hazardous pesticides) ([CABI PlantwisePlus Knowledge Bank](https://plantwiseplusknowledgebank.org/)).
- **Legal & Safety Risks of Chemical Recommendations:**
  - Under India's **Insecticides Act, 1968** and **Insecticides Rules, 1971** (and draft Pesticide Management Bill provisions), recommending an unregistered chemical or an off-label crop-pest combination not approved by **CIB&RC** creates regulatory and liability exposure ([CIB&RC Registered Products](https://ppqs.gov.in/divisions/cib-rc/registered-products)).
  - **Mitigation:** Always prioritize **cultural, mechanical, and biological (IPM/biopesticide)** practices first (e.g., *Trichoderma viride*, *Pseudomonas fluorescens*, neem oil azadirachtin). When listing chemical active ingredients, cite only **CIB&RC-approved generic molecules** (never commercial brand names), display mandatory PPE safety warnings and Pre-Harvest Intervals (PHI), and include a prominent disclaimer directing the farmer to verify dosage with their local **Krishi Vigyan Kendra (KVK)** or licensed agri-extension officer before spraying.

## D. Security, Privacy, and Regulatory Compliance

### 1. Firebase Security Best Practices

- **Firestore Security Rules (`firestore.rules`):** Default deny (`allow read, write: if false;`). Scope user scan history and feedback documents strictly to `request.auth != null && request.auth.uid == userId`, validate schema types and string lengths (`cropId`, `diseaseId`, `confidence >= 0 && confidence <= 1`), and make `advice_catalog` public read-only (`allow write: if false;`).
- **Cloud Storage Security Rules (`storage.rules`):** Scope uploads to `/users/{userId}/scans/{scanId}.jpg`, enforce `request.auth != null && request.auth.uid == userId`, enforce maximum payload size `request.resource.size < 2 * 1024 * 1024` (2 MB), and restrict `request.resource.contentType.matches('image/jpeg')`.
- **Firebase App Check:** Enforce `PlayIntegrityAppCheckProviderFactory` in release builds and `DebugAppCheckProviderFactory` in debug builds so unauthorized scripts cannot abuse Cloud Storage or Firestore quotas.
- **Image Privacy:** Strip GPS EXIF metadata (`ExifInterface` latitude/longitude tags) and crop exclusively to the leaf bounding region before writing to disk or uploading to Cloud Storage, ensuring faces, bystanders, or land documents are never uploaded.

### 2. India's Digital Personal Data Protection (DPDP) Act, 2023

- **Applicability & Requirements:** India's DPDP Act 2023 (with Phase 2 enforcement rules taking effect in 2026) requires explicit, itemized **notice and consent** in English and 8th Schedule Indian languages (including Hindi and Telugu) before collecting personal data ([DPDP Act Mobile App Compliance](https://respectlytics.com/blog/india-dpdp-act-mobile-app-compliance/), [Google Play Data Safety & DPDP Compliance](https://www.krapton.in/blog/master-google-play-data-safety-for-indian-apps-dpdp-act-compliance)).
- **No "Legitimate Interests" Exception:** Unlike GDPR, the DPDP Act does not permit silent data collection under "legitimate interest"—cloud sync of photos or account email requires affirmative opt-in consent, and users must have a one-tap **Delete Account & Erase Cloud Data** workflow.

### 3. Google Play Store Requirements (2026)

- **Target API 36 Mandate:** Effective August 31, 2026, all new apps and app updates submitted to Google Play must target **Android 16 (API level 36)** or higher ([Google Play Target SDK Policy](https://developer.android.com/google/play/requirements/target-sdk)).
- **Photo and Video Permissions Policy (`PickVisualMedia`):** Google Play strictly prohibits declaring `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, or `READ_EXTERNAL_STORAGE` for one-off gallery selection ([Google Play Photo and Video Permissions Policy, Oct 2026](https://ptkd.com/journal/google-play-photo-video-permissions-policy)). CropDoc must use the Android **Photo Picker** (`ActivityResultContracts.PickVisualMedia`), which requires **zero storage permissions** across Android 7.0–16, and request only `android.permission.CAMERA` when capturing live leaf images.

## E. Engineering Quality, Architecture, and Performance Optimization

### 1. Official Android Architecture & Modern Java Usage

- **Layered Unidirectional Architecture:** UI Layer (Fragments + ViewBinding + `ViewModel` exposing immutable `LiveData<UiState>`) -> Domain Layer (focused use cases where multi-repository coordination or ML + advice join occurs) -> Data/ML Layer (Repositories, Room DAOs, Firebase sources, LiteRT classifier) ([Android App Architecture Guide](https://developer.android.com/topic/architecture)).
- **Modern Java 17 Idioms:** Use Java 17 `record` types or immutable `final` value classes, `switch` expressions, `Objects.requireNonNull`, and structured `ExecutorService` thread pools (`Executors.newFixedThreadPool` for disk/network I/O, single-thread `Executors.newSingleThreadExecutor` for sequential thread-safe LiteRT `Interpreter` execution) instead of deprecated `AsyncTask` or raw `new Thread()`.
- **Lifecycle Safety:** Use `getViewLifecycleOwner()` inside Fragments for `LiveData` observation, clear `ViewBinding` references in `onDestroyView()`, and implement `DefaultLifecycleObserver` (`lifecycle-common-java8:2.11.0`) to release CameraX and LiteRT native resources deterministically.

### 2. Startup, Memory, Bitmap Decoding, and APK/AAB Optimization

- **Cold Start Optimization:** Install `androidx.profileinstaller:profileinstaller:1.4.1` with a shipped Baseline Profile (`baseline-prof.txt`) ([Create Baseline Profiles](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)); defer LiteRT `.tflite` memory-mapping (`MappedByteBuffer`) until `ScanFragment` opens or on a background warm-up executor after first frame draw.
- **Subsampled Bitmap Decoding:** Never decode full 12 MP–48 MP camera JPEGs into `ARGB_8888` (~48–192 MB RAM). Compute power-of-two `inSampleSize` via `BitmapFactory.Options` (`inJustDecodeBounds = true`) to decode directly near `224x224` (`RGB_565` or bounded `ARGB_8888` <1 MB), and reuse a single pre-allocated direct `ByteBuffer` (`224 * 224 * 3` bytes = `150,528` bytes for `UINT8`/`INT8`) and `int[]` pixel array across scans to guarantee **zero GC churn** during inference.
- **R8 & AAB Shrinking:** Enable `isMinifyEnabled = true`, `isShrinkResources = true`, R8 full mode (`android.enableR8.fullMode=true`), `resConfigs("en", "hi", "te")`, and `ndk.abiFilters("arm64-v8a", "armeabi-v7a")` with `aaptOptions { noCompress("tflite") }` so the `.tflite` flatbuffer can be `mmap`-ed directly from the APK without heap decompression.

### 3. Firestore Cost & Offline Performance Patterns

- Enable persistent disk cache with explicit size bound (`PersistentCacheSettings.newBuilder().setSizeBytes(50L * 1024 * 1024).build()`).
- Store scan history locally in **Room** first (instant offline UI reads = **0 Firestore reads** for normal history browsing) and sync unsynced records in batches via **WorkManager** (`Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)`).
- When fetching cloud history on a new device login, enforce `limit(20)` with `.startAfter(lastDocumentSnapshot)` pagination and composite index `(userId ASC, timestamp DESC)` so session reads never exceed 20 documents.

## F. Top Risks and Mitigations

| Risk Category | Severity | Concrete Impact | Proven Mitigation Strategy |
| :--- | :--- | :--- | :--- |
| **1. Wrong Diagnosis / Lab-to-Field Generalization Failure** | Critical | Farmer applies wrong fungicide/bactericide, wasting money and losing crop yield | Train on field-augmented mixed datasets; enforce a guided leaf-crop reticle; suppress single-label diagnosis when confidence < 0.65; show differential symptoms and comparison photos |
| **2. Overconfident OOD Predictions ("Not a Leaf")** | High | Classifier outputs 95% confidence on a photo of soil, a hand, or an unsupported crop | Include a dedicated `Background_Not_A_Leaf` negative class + HSV leaf-pixel ratio gate + temperature-scaled softmax + Shannon entropy threshold ($H_{\text{norm}} \le 0.55$) |
| **3. Chemical Misuse & Regulatory Liability** | Critical | Legal violation under India's Insecticides Act 1968 or farmer health hazard | Recommend IPM/cultural/biological controls first; restrict chemical listings to CIB&RC-approved generic molecules with PPE/PHI warnings; require KVK verification disclaimer |
| **4. Model Drift Across Seasons & Regions** | Medium | New regional pathogen strains degrade accuracy over time | Collect opt-in thumbs-up/down feedback in Firestore; support OTA model hot-swapping via **Firebase Remote Config + Cloud Storage** with SHA-256 verification and automatic rollback |
| **5. Firebase Cost Spike at Scale** | Medium | Unbounded Firestore reads or large raw 10 MB photo uploads exhaust budget | Room-first offline reads (0 Firestore reads on cached screens); compress uploaded leaf crops to `512x512` JPEG at 80% quality (<80 KB); enforce App Check & 2 MB Storage rules |
| **6. OOM / ANR on Low-End 3 GB RAM Phones** | High | Decoding 48 MP camera images or running inference on UI thread crashes app | Strict `inSampleSize` bitmap subsampling, pre-allocated direct `ByteBuffer` reuse, all I/O and LiteRT calls on background executors, enforced by `StrictMode` and LeakCanary in debug |

## G. Unverified or Conflicting Findings Flagged

1. **LiteRT `CompiledModel` Java Interoperability:** Official Google AI Edge documentation (October 2026) documents `CompiledModel` exclusively for **Kotlin** (`developers.google.com/edge/litert/next/android_kotlin`) and **C++** (`developers.google.com/edge/litert/next/cpp`). No official Java sample or Java-friendly builder API is documented for `CompiledModel` in LiteRT 2.3.0. Therefore, using `CompiledModel` from pure Java is flagged as unverified/unsupported, and CropDoc uses the verified `org.tensorflow.lite.Interpreter` API shipped in `com.google.ai.edge.litert:litert:2.3.0`.
2. **PlantVillage Exact Image Count Discrepancy Across Literature:** Primary sources report either **54,306** ([Mohanty et al. GitHub](https://github.com/spmohanty/plantvillage-dataset)), **54,305** ([Meta-Album](https://meta-album.github.io/datasets/PLT_VIL.html)), or **54,309** ([Hilal et al., Oct 2026](https://arxiv.org/html/2610.07087v1)) due to 3–4 corrupted/metadata header files in the original archive. Similarly, PlantDoc is reported as **2,598** in Singh et al. (2020) and **2,569** in Hilal et al. (2026) after removing broken bounding-box entries.
3. **Checkstyle Version vs. JDK 17 Conflict:** While Checkstyle's latest release on Maven Central is **14.3.0** (September 2026), Checkstyle **13.3.0+** dropped Java 17 runtime support and requires **Java 21+** ([Checkstyle Issue #19104](https://github.com/checkstyle/checkstyle/issues/19104)). Because AGP 9.4.0's baseline JDK is 17, we resolve this conflict by pinning Checkstyle to **10.26.1** (the latest Java 17-compatible release) when running on JDK 17.
4. **Firebase ML Model Downloader Deprecation:** While the prompt mentioned checking "Remote Config and Firebase ML model delivery", official Firebase release notes (October 7, 2026) confirm that `firebase-ml-modeldownloader` was **removed in Firebase BoM 35.0.0** ahead of the June 15, 2027 shutdown ([FirebaseModelDownloader Reference](https://firebase.google.com/docs/reference/android/com/google/firebase/ml/modeldownloader/FirebaseModelDownloader)). We resolve this by implementing the official replacement architecture: **Firebase Remote Config + Cloud Storage for Firebase** ([Migration Guide](https://firebase.google.com/docs/ml/migrate-to-cloud-storage)).

## H. Primary Source Reference List

1. Google Android Developers — *Android Gradle plugin 9.4.0 Release Notes* (Updated Oct 8, 2026): https://developer.android.com/build/releases/agp-9-4-0-release-notes
2. Google Android Developers — *Android Studio Releases (Rabbit 1)* (Updated Oct 1, 2026): https://developer.android.com/studio/releases
3. Google Android Developers — *Meet Google Play's target API level requirement (API 36)* (Updated Oct 16, 2026): https://developer.android.com/google/play/requirements/target-sdk
4. Google Firebase — *Firebase Android SDK Release Notes (BoM 35.0.0)* (Updated Oct 7, 2026): https://firebase.google.com/support/release-notes/android
5. Google Firebase — *Add Firebase to your Android project (Removal of KTX modules)* (Updated Oct 15, 2026): https://firebase.google.com/docs/android/setup
6. Google Firebase — *FirebaseModelDownloader Deprecation Notice (Shutdown June 15, 2027)* (Updated Sep 9, 2026): https://firebase.google.com/docs/reference/android/com/google/firebase/ml/modeldownloader/FirebaseModelDownloader
7. Google Firebase — *Migrate TensorFlow Lite models from Firebase ML to Cloud Storage* (Updated Oct 1, 2026): https://firebase.google.com/docs/ml/migrate-to-cloud-storage
8. Google AI Edge — *LiteRT for Android & October 2026 Release (v2.3.0, minSdk 24)* (Updated Oct 12, 2026): https://developers.google.com/edge/litert/android and https://developers.google.com/edge/litert/releases/litert_oct
9. Google AI Edge — *Migrate to LiteRT from TensorFlow Lite* (Updated Aug 19, 2026): https://developers.google.com/edge/litert/migration
10. Google AI Edge — *LiteRT CompiledModel Kotlin API* (Updated Oct 8, 2026): https://developers.google.com/edge/litert/next/android_kotlin
11. Google Android NDK — *NNAPI Migration Guide (Deprecated in Android 15)* (Updated Mar 6, 2026): https://developer.android.com/ndk/guides/neuralnetworks/migration-guide
12. Google Android Developers — *Jetpack Stable Releases Matrix* (Updated Oct 7, 2026): https://developer.android.com/jetpack/androidx/versions/stable-channel
13. Google Android Developers — *CameraX Release Notes (v1.6.2)* (Updated Aug 26, 2026): https://developer.android.com/jetpack/androidx/releases/camera
14. Google Android Developers — *Room Release Notes (v2.8.5)* (Updated Sep 9, 2026): https://developer.android.com/jetpack/androidx/releases/room
15. Google Android Developers — *Navigation Release Notes (v2.10.2)* (Updated Sep 23, 2026): https://developer.android.com/jetpack/androidx/releases/navigation
16. Google Android Developers — *Lifecycle Release Notes (v2.11.0)* (Updated Sep 23, 2026): https://developer.android.com/jetpack/androidx/releases/lifecycle
17. Google Android Developers — *WorkManager Release Notes (v2.12.0)* (Updated Sep 23, 2026): https://developer.android.com/jetpack/androidx/releases/work
18. Google Android Developers — *Credentials Release Notes (v1.6.0)* (Updated Jul 29, 2026): https://developer.android.com/jetpack/androidx/releases/credentials
19. Hilal, A., Sofi, S. A., & Goel, N. — *A Data-Centric Review of Plant Disease Datasets: Taxonomy, Critical Analysis, Environmental Variability, and Implications for Precision Agriculture* (arXiv:2610.07087v1, Oct 5, 2026): https://arxiv.org/html/2610.07087v1
20. Mohanty, S. P., Hughes, D. P., & Salathé, M. — *PlantVillage Dataset Repository* (2016): https://github.com/spmohanty/plantvillage-dataset
21. Singh, D. et al. — *PlantDoc: A Dataset for Visual Plant Disease Detection* (ACM CoDS COMAD 2020): https://www.researchgate.net/publication/337531775_PlantDoc_A_Dataset_for_Visual_Plant_Disease_Detection
22. Moupojou, E. et al. — *FieldPlant: A Dataset of Field Plant Images for Plant Disease Detection and Classification With Deep Learning* (IEEE Access, 2023 / Updated Jul 2026): https://www.researchgate.net/publication/369642460_FieldPlant_A_dataset_of_field_plant_images_for_plant_disease_detection_and_classification_with_deep_learning
23. Directorate of Plant Protection, Quarantine & Storage (CIB&RC, Govt. of India) — *Major Uses of Pesticides (as on 30.09.2026)*: https://ppqs.gov.in/divisions/cib-rc/major-uses-of-pesticides
24. CABI — *PlantwisePlus Knowledge Bank & India Country Programme* (2026): https://plantwiseplusknowledgebank.org/ and https://www.cabi.org/plantwiseplus/countries/india/
25. Siddiqua, A. et al. — *Evaluating Plant Disease Detection Mobile Applications: Quality and Limitations* (MDPI Agronomy, 2022): https://www.mdpi.com/2073-4395/12/8/1869
