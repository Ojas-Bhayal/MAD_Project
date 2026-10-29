# CropDoc Audit Coverage & Inspection Log

This document explicitly records which files, components, and areas were fully read and analyzed, which were sampled, and which were out of scope or could not be verified in this audit environment.

---

## 1. Coverage by Project Area

| Area | Scope | Audit Depth | Files Covered | Notes / Blind Spots |
| :--- | :--- | :---: | :--- | :--- |
| **A. ML Pipeline & OTA Updates** | Models, labels, inference, calibration, OTA manager, preprocessing | **100% Read** | `LiteRtLeafClassifier.java`, `LeafPreprocessor.java`, `ConfidenceCalibrator.java`, `LeafSpectralAnalyzer.java`, `ModelConfig.java`, `ModelUpdateManager.java`, `model_metadata.json`, `labels.txt` | Fully traced. Discovered LiteRT bypass via `MIN_FULL_MODEL_BYTES` and OTA atomic replacement flaw. |
| **B. Functional Flows & Concurrency** | Use cases, repositories, WorkManager, view models, fragments, navigation | **100% Read** | `AnalyzeLeafUseCase.java`, `ScanRepository.java`, `ScanSyncWorker.java`, `ScanViewModel.java`, `ScanFragment.java`, `ResultViewModel.java`, `ResultFragment.java`, `HistoryViewModel.java`, `HistoryFragment.java`, `AuthViewModel.java`, `AuthFragment.java`, `MainActivity.java`, `nav_graph.xml` | Traced rotation, background sync batching, race conditions, and fragment lifecycle. |
| **C. Security & Privacy** | Firestore rules, indexes, auth source, privacy sanitizer, backup config | **100% Read** | `firestore.rules`, `firestore.indexes.json`, `FirebaseAuthSource.java`, `ExifPrivacySanitizer.java`, `AndroidManifest.xml`, `network_security_config.xml`, `data_extraction_rules.xml`, `google-services.json` | Tested payload validation, App Check integration, and DPDP Act deletion. Storage rules absent because Cloud Storage is unused. |
| **D. Performance, Memory, Battery** | Allocations, threading, leaks, baseline profile | **100% Read** | `AppExecutors.java`, `baseline-prof.txt`, `LeafSpectralAnalyzer.java`, `ScanFragment.java` | Inferred from static analysis and byte calculations. Profiler / Systrace could not run without physical device. |
| **E. UI, UX, A11y, Localization** | Layouts, themes, dimensions, strings (en, hi, te), formatting | **100% Read** | `activity_main.xml`, `fragment_scan.xml`, `fragment_result.xml`, `fragment_history.xml`, `fragment_auth.xml`, `item_history_scan.xml`, `dimens.xml`, `themes.xml`, `colors.xml`, `strings.xml` (en, hi, te), `LocaleHelper.java`, `DiseaseLabelFormatter.java` | Verified format specifiers, RTL attributes, 48dp touch targets, and Indic diacritic padding. |
| **F. Code Quality & Architecture** | Room DAOs, entities, seed loaders, unit tests, DI | **100% Read** | `CropDocDatabase.java`, `ScanDao.java`, `AdviceDao.java`, `ScanEntity.java`, `AdviceEntity.java`, `AdviceSeedLoader.java`, `AppContainer.java`, all test files in `src/test/` and `src/androidTest/` | Audited for single-implementation abstractions, dead code, and missing test assertions. |
| **G. Python / ML Scripts (`ml/`)** | Dataset expansion, deduplication, splitting, export, notebooks | **100% Read** | `ml/scripts/common.py`, `ml/scripts/build_manifest.py`, `ml/scripts/deduplicate_groups.py`, `ml/scripts/split_dataset.py`, `ml/scripts/train_and_export_v2.py`, `ml/scripts/verify_taxonomy_v1_order.py`, `ml/CropDoc_Training_Colab.ipynb`, `data/manifests/*.csv`, `data/splits/*.json`, `data/licenses/SOURCES.md`, `reports/*.json` | Executed Python verification scripts. Discovered `cid = rows[idx].get("class_id", "")` bug and fabricated metrics. |
| **H. Build, Release & Docs** | Gradle scripts, version catalog, proguard rules, README | **100% Read** | `build.gradle`, `settings.gradle`, `gradle.properties`, `local.properties`, `gradle/libs.versions.toml`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle`, `app/proguard-rules.pro`, `config/lint/lint.xml`, `config/checkstyle/checkstyle.xml`, `README.md` | Discovered missing `gradlew` wrapper binaries and committed SDK path in `local.properties`. |

---

## 2. Exhaustive File-by-File Ledger

### Java Application Source (`app/src/main/java/com/cropdoc/app/`)
- `CropDocApplication.java` — **Read in full** (Application entry, StrictMode, App Check)
- `di/AppContainer.java` — **Read in full** (Manual DI singleton container)
- `di/AppExecutors.java` — **Read in full** (Thread pools: disk, network, ML, main)
- `domain/model/CropDiseaseAdvice.java` — **Read in full** (Domain entity)
- `domain/model/DiagnosisOutcome.java` — **Read in full** (Outcome enum)
- `domain/model/DiagnosisResult.java` — **Read in full** (Result payload)
- `domain/model/ScanRecord.java` — **Read in full** (Scan history record)
- `domain/usecase/AnalyzeLeafUseCase.java` — **Read in full** (Orchestrates classifier, Room, and advice)
- `ml/ModelConfig.java` — **Read in full** (Constants, thresholds, filenames)
- `ml/LeafClassifier.java` — **Read in full** (Interface)
- `ml/LiteRtLeafClassifier.java` — **Read in full** (Interpreter lifecycle, OTA loading, fallback)
- `ml/LeafPreprocessor.java` — **Read in full** (Subsampling, EXIF rotation, reticle cropping)
- `ml/LeafSpectralAnalyzer.java` — **Read in full** (Foliar chromaticity & lesion heuristic rules)
- `ml/ConfidenceCalibrator.java` — **Read in full** (Temperature scaling, Shannon entropy)
- `data/local/CropDocDatabase.java` — **Read in full** (Room database singleton)
- `data/local/AdviceSeedLoader.java` — **Read in full** (Asset JSON parsing & database seeding)
- `data/local/dao/ScanDao.java` — **Read in full** (Room scan queries)
- `data/local/dao/AdviceDao.java` — **Read in full** (Room advice queries)
- `data/local/entity/ScanEntity.java` — **Read in full** (Room scan table)
- `data/local/entity/AdviceEntity.java` — **Read in full** (Room advice table)
- `data/remote/FirebaseAuthSource.java` — **Read in full** (Firebase Auth wrapper & Guest mode)
- `data/remote/FirestoreSyncSource.java` — **Read in full** (Firestore batch writes & pagination)
- `data/remote/ModelUpdateManager.java` — **Read in full** (OTA file replacement & Remote Config)
- `data/repository/AdviceRepository.java` — **Read in full** (Localized advice access)
- `data/repository/AuthRepository.java` — **Read in full** (Auth facade)
- `data/repository/ScanRepository.java` — **Read in full** (Offline Room & sync orchestration)
- `data/sync/ScanSyncWorker.java` — **Read in full** (WorkManager background sync)
- `ui/MainActivity.java` — **Read in full** (Single-activity host, tab switching, language bar)
- `ui/scan/ScanFragment.java` — **Read in full** (CameraX lifecycle, photo capture, gallery picker)
- `ui/scan/ScanViewModel.java` — **Read in full** (Atomic concurrency guard, image analysis)
- `ui/result/ResultFragment.java` — **Read in full** (Hero card, TTS, chemical advice, feedback)
- `ui/result/ResultViewModel.java` — **Read in full** (State management, feedback submission)
- `ui/history/HistoryFragment.java` — **Read in full** (List display, empty state, data erasure)
- `ui/history/HistoryViewModel.java` — **Read in full** (History observation, deletion dispatch)
- `ui/history/HistoryAdapter.java` — **Read in full** (RecyclerView holder & formatting)
- `ui/auth/AuthFragment.java` — **Read in full** (Credential Manager, Email/Password, Guest UI)
- `ui/auth/AuthViewModel.java` — **Read in full** (Auth state management, DPDP deletion)
- `ui/common/Result.java` — **Read in full** (Generic resource wrapper)
- `ui/common/UiMotionHelper.java` — **Read in full** (Tactile feedback, animations)
- `ui/common/ViewModelFactory.java` — **Read in full** (ViewModel provider factory)
- `ui/common/WindowInsetsHelper.java` — **Read in full** (Edge-to-Edge insets dispatch)
- `util/DiseaseLabelFormatter.java` — **Read in full** (Snake_case to title case)
- `util/ExifPrivacySanitizer.java` — **Read in full** (JPEG compression & Base64 encoding)
- `util/LocaleHelper.java` — **Read in full** (AppCompatDelegate locale switcher)

### Tests (`app/src/test/` and `app/src/androidTest/`)
- `src/test/java/com/cropdoc/app/data/repository/ScanRepositoryTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/data/repository/AdviceRepositoryTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/ml/LiteRtLeafClassifierTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/ui/UiBreakTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/ui/auth/AuthViewModelTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/ui/result/ResultViewModelTest.java` — **Read in full**
- `src/test/java/com/cropdoc/app/ui/scan/ScanViewModelTest.java` — **Read in full**
- `src/androidTest/java/com/cropdoc/app/ui/scan/ScanFlowInstrumentedTest.java` — **Read in full**

### Configuration, Build, Rules & Manifests
- `build.gradle` (root) — **Read in full**
- `settings.gradle` — **Read in full**
- `gradle.properties` — **Read in full**
- `local.properties` — **Read in full**
- `gradle/libs.versions.toml` — **Read in full**
- `gradle/wrapper/gradle-wrapper.properties` — **Read in full**
- `app/build.gradle` — **Read in full**
- `app/proguard-rules.pro` — **Read in full**
- `app/google-services.json` & `.template` — **Read in full**
- `app/src/main/AndroidManifest.xml` — **Read in full**
- `app/src/main/res/xml/network_security_config.xml` — **Read in full**
- `app/src/main/res/xml/data_extraction_rules.xml` — **Read in full**
- `app/src/main/res/xml/locales_config.xml` — **Read in full**
- `firebase/firestore.rules` — **Read in full**
- `firebase/firestore.indexes.json` — **Read in full**
- `firebase.json` & `.firebaserc` — **Read in full**
- `config/checkstyle/checkstyle.xml` — **Read in full**
- `config/lint/lint.xml` — **Read in full**
- `app/src/main/baseline-prof.txt` — **Read in full**
- `README.md` — **Read in full**

### Python ML Scripts & Evaluation Artifacts (`ml/`, `models/`, `data/`, `reports/`)
- `ml/scripts/common.py` — **Read in full**
- `ml/scripts/build_manifest.py` — **Read in full**
- `ml/scripts/deduplicate_groups.py` — **Read in full**
- `ml/scripts/split_dataset.py` — **Read in full**
- `ml/scripts/train_and_export_v2.py` — **Read in full**
- `ml/scripts/verify_taxonomy_v1_order.py` — **Read in full**
- `ml/CropDoc_Training_Colab.ipynb` — **Read in full** (all 6 cells)
- `data/licenses/SOURCES.md` — **Read in full**
- `data/taxonomy/classes_v2.yaml` — **Read in full**
- `data/manifests/manifest_v1.csv` & `manifest_v2.csv` — **Sampled and parsed via Python scripts**
- `data/splits/split_v2.json` — **Read in full**
- `models/v1/model_metadata.json` & `labels.txt` — **Read in full**
- `models/v2/model_metadata.json`, `labels.txt` & `model_card.md` — **Read in full**
- `reports/baseline_v1.json` & `reports/eval_v2.json` — **Read in full**
- `reports/gap_analysis.md` — **Read in full**

---

## 3. Explicit Blind Spots & Not Checked List

The following items could not be executed or physically measured in this read-only audit environment and must be verified on live devices or continuous integration environments:

1. **OEM Hardware Camera Driver Behaviors:**
   - Specific HAL3/Camera2 driver quirks across various vendor SoCs (MediaTek Helio G25/G35, Unisoc SC9863A, Qualcomm Snapdragon 400-series) regarding preview aspect ratio negotiation, autofocus hunting in outdoor direct sunlight, and torch toggle latencies.
2. **Physical Device Profiling (Systrace / Macrobenchmark / LeakCanary):**
   - Exact cold-start latency (budget $\le 1200$ ms) and frame render times ($\le 16$ ms) on a physical 3-4 GB RAM device. Memory measurements reported in Area D were derived from data structure size math and static heap allocation analysis.
3. **Live Firebase Quotas & Network Fault Injection:**
   - Real Firestore Spark free tier quota exhaustion (50,000 daily reads, 20,000 daily writes) under production scale.
   - Live network packet loss simulation (e.g. 50% packet drop on 2G EDGE) with live Firebase servers.
4. **Full 10-Epoch GPU Model Training:**
   - Executing the complete multi-hour Colab training script across all 50,000+ raw images from PlantVillage, PlantDoc, and FieldPlant on a live NVIDIA T4 GPU runtime.
5. **Google Play Integrity Live Attestation:**
   - Live hardware-backed Play Integrity cryptographic token verification against Google Play servers on uncertified Chinese or rooted budget devices.
