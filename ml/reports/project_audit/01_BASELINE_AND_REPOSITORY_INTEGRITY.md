# Phase A: Baseline and Repository Integrity Report

**Audit Timestamp:** 2026-10-24T23:25:00+05:30  
**Project Root:** `C:\Users\ojas\OneDrive\Desktop\MajorProjectMAD\CropDoc`  
**Auditor Role:** Senior Android Architect, Java Systems Engineer, ML Safety Auditor  
**Audit Status:** Baseline Integrity Established (Zero destructive actions taken)

---

## 1. Git State and Workspace Metadata

| Property | Value / Evidence |
| :--- | :--- |
| **Active Branch** | `ml-safety-phase-g` |
| **HEAD Commit** | `f18be8e Phase G: Close final ML safety gaps (exhaustive gate decision switch, evidence-based gate validation, tensor dtype/dimension graph inspection, packaging evidence reconciliation)` |
| **Upstream / Tracking** | Local tracking branch with 5 local safety phase branches (`main`, `ml-safety-phase-e`, `ml-safety-phase-f`, `ml-safety-phase-g`, `ml-safety-repair`) |
| **Tracked Modifications** | 34 files modified (+788 lines, -401 lines across Java, XML, Gradle, test files) |
| **Tracked Deletions** | 1 file (`ml/scripts/__pycache__/common.cpython-311.pyc`) |
| **Untracked Directories** | Research app source, resources, test suites, datasets, splits, scripts, and reports |
| **Stash State** | Clean (no stashes popped or modified) |

---

## 2. Environment and Tooling Matrix

| Tool / Runtime | Version | Status / Notes |
| :--- | :--- | :--- |
| **Gradle** | `9.6.0` | Active wrapper in repo (`gradlew.bat`), build time 2026-06-18 |
| **Launcher JVM** | Eclipse Adoptium OpenJDK `17.0.20.1+1` | Aligns with `JavaVersion.VERSION_17` target in `app/build.gradle` |
| **System Java** | Java 27 (`build 27+35-2325`) | HotSpot 64-Bit Server VM |
| **Android SDK Platforms** | `android-34`, `android-36`, `android-37.0`, `android-37.2` | Path: `C:\Users\ojas\AppData\Local\Android\Sdk\platforms` |
| **Compile SDK / Target SDK** | `37` / `37` | Configured in `app/build.gradle` |
| **Minimum SDK** | `24` (Android 7.0 Nougat) | Configured in `app/build.gradle` |
| **Connected Devices** | `0` (None attached) | Verified via `adb devices -l` (No physical device or active emulator) |
| **Python** | 3.11+ | Available for ML offline evaluation and TFLite graph inspection |

---

## 3. Inventory of Tracked Modifications

The 34 tracked modified files represent cumulative safety hardening across Phases 0–G and UI research camera/localization integration:

### A. Core Build and Configuration
1. `app/build.gradle`: Adds `checkstyle` config, `validateBundledModelForRelease` release-gate task, and dependency definitions.
2. `app/src/main/AndroidManifest.xml`: Declares `FileProvider` authority `${applicationId}.fileprovider` with cache mapping for camera capture.
3. `config/lint/lint.xml`: Custom lint severity rules with baseline error containment.
4. `data/licenses/SOURCES.md`: License attribution for curated image sources and datasets.

### B. Machine Learning Safety Subsystem
5. `app/src/main/java/com/cropdoc/app/ml/LiteRtLeafClassifier.java`: Comprehensive fail-closed runtime checks, dynamic class expansion handling, and error containment.
6. `app/src/main/java/com/cropdoc/app/ml/ModelValidator.java`: Multi-layer validation (magic bytes `TFL3`, min byte length 512KB, SHA-256 digest, tensor shapes, and labels CRC32).
7. `app/src/main/java/com/cropdoc/app/ml/ConfidenceCalibrator.java`: Decision thresholding, margin-of-confidence evaluation, and non-diagnostic class filtering.
8. `app/src/main/java/com/cropdoc/app/ml/GateValidationEvidence.java`: Evidence-based cryptographic and structural model graph verification.
9. `app/src/main/java/com/cropdoc/app/ml/ImageQualityGate.java`: Exposure/luminance checks (10 to 245 lux bounds).
10. `app/src/main/java/com/cropdoc/app/ml/LeafGate.java`: Foliar vegetation index coverage check ($2G - R - B \ge 12$).
11. `app/src/main/java/com/cropdoc/app/ml/ClassificationOutcome.java`: Exhaustive diagnostic outcome enum.
12. `app/src/main/java/com/cropdoc/app/ml/ModelManifest.java`: Schema-validated metadata descriptor.

### C. Application UI and Presentation
13. `app/src/main/java/com/cropdoc/app/ui/MainActivity.java`: Bottom navigation and language selector lifecycle bindings.
14. `app/src/main/java/com/cropdoc/app/ui/auth/AuthFragment.java`: Authentication UI state handling.
15. `app/src/main/java/com/cropdoc/app/ui/auth/AuthViewModel.java`: Auth state coordination.
16. `app/src/main/java/com/cropdoc/app/ui/history/HistoryAdapter.java`: History item binding.
17. `app/src/main/java/com/cropdoc/app/ui/result/ResultFragment.java`: Diagnostic result rendering with safety banners.
18. `app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java`: Production scanner lifecycle and model-availability checks.
19. `app/src/main/java/com/cropdoc/app/di/AppExecutors.java`: Background executor pools.
20. `app/src/main/java/com/cropdoc/app/data/local/CropDocDatabase.java`: Room database definition.
21. `app/src/main/java/com/cropdoc/app/data/local/dao/ScanDao.java`: Persistence DAO.

### D. Layout and Navigation Resources
22. `app/src/main/res/layout/activity_main.xml`
23. `app/src/main/res/layout/fragment_auth.xml`
24. `app/src/main/res/layout/fragment_history.xml`
25. `app/src/main/res/layout/fragment_result.xml`
26. `app/src/main/res/layout/fragment_scan.xml`
27. `app/src/main/res/navigation/nav_graph.xml`

### E. Localization Strings
28. `app/src/main/res/values/strings.xml`: English strings (added camera, research, and safety strings).
29. `app/src/main/res/values-hi/strings.xml`: Complete Hindi translation parity.
30. `app/src/main/res/values-te/strings.xml`: Complete Telugu translation parity.

### F. Unit and Safety Test Suites
31. `app/src/test/java/com/cropdoc/app/ml/ContainmentSafetyTestSuite.java`: 1,071 lines verifying fail-closed containment across all failure vectors.
32. `app/src/test/java/com/cropdoc/app/ml/LiteRtLeafClassifierTest.java`: Dynamic class expansion and evaluation tests.
33. `app/src/test/java/com/cropdoc/app/ml/MlSafetyReproductionTest.java`: Regression tests for safety gaps.
34. `app/src/test/java/com/cropdoc/app/ml/PhaseEReproductionTest.java`: Phase E verification.

---

## 4. Inventory of Untracked Workspace Assets

1. **Research Source & Tests (`app/src/`):**
   - `app/src/main/java/com/cropdoc/app/ui/research/`: `ResearchDemoFragment.java`, `ResearchDemoViewModel.java`, `ResearchDemoState.java`, `ResearchImageDecoder.java`, `ResearchUiFormatter.java`.
   - `app/src/main/java/com/cropdoc/app/ml/research/`: `LiteRtResearchModelExecutor.java`, `ResearchInferenceHarness.java`, `ResearchHarnessOutcome.java`, `SixClassResearchMapping.java`.
   - `app/src/main/java/com/cropdoc/app/ml/policy/`: `CropDomainGate.java`, `InferenceDecisionPolicy.java`, `EndToEndSafetyPipeline.java`.
   - `app/src/main/assets/research_models/`: `crop_domain_gate_v1_int8.tflite`, `unified_six_class_v1_int8.tflite`, `sample_non_leaf_soil.jpg`.
   - `app/src/main/res/layout/fragment_research_demo.xml`, `app/src/main/res/xml/file_paths.xml`, `app/src/main/res/drawable/ic_science_research.xml`.
   - `app/src/test/java/com/cropdoc/app/ui/research/`: `ResearchDemoSafetyTest.java`, `ResearchLocalizationTest.java`.
   - `app/src/test/java/com/cropdoc/app/ml/research/`, `app/src/test/java/com/cropdoc/app/ml/policy/`.
2. **Datasets and Splits (`data/`):**
   - Manifests: `manifest_cotton_staged.csv`, `manifest_crop_domain_gate.csv`, `manifest_fieldplant.csv`, `manifest_groundnut.csv`, `manifest_mendeley_cotton_2025.csv`, `manifest_rice_four_class.csv`, `manifest_rice_mendeley_healthy_candidate.csv`, `manifest_rice_mendeley_sethy.csv`, `manifest_unified_six_class.csv`.
   - Splits: JSON split files ensuring zero specimen or publisher leakage.
   - Staged images in `data/staged/`.
3. **Machine Learning Scripts & Reports (`ml/`):**
   - `ml/scripts/`: Training, conversion, graph inspection, dataset auditing, perceptual hashing, and evaluation scripts.
   - `ml/experiments/`: Checkpoints and training run logs.
   - `ml/reports/`: Historical audit reports and benchmarks.

---

## 5. Verification Baseline

Before performing the deep audit, non-destructive build tasks were executed to confirm the starting health of the repository:

1. **Unit Test Suite:**
   ```powershell
   ./gradlew.bat testDebugUnitTest
   ```
   - **Result:** `BUILD SUCCESSFUL in 4s`.
   - **Metrics:** 136 tests executed across 19 test suites, 0 failures, 0 errors, 0 skipped.
2. **Checkstyle Baseline:**
   ```powershell
   ./gradlew.bat checkstyle
   ```
   - **Result:** 7 pre-existing baseline `FileLength` violations on large legacy classes (> 250 lines).
   - **Newly Added/Modified Code:** 0 violations (all research files adhere strictly to Checkstyle rules).
3. **Debug APK Build:**
   ```powershell
   ./gradlew.bat assembleDebug
   ```
   - **Result:** `BUILD SUCCESSFUL in 3s`.
   - **Output:** `app/build/outputs/apk/debug/app-debug.apk` (51,405,893 bytes).
4. **Release Safety Gate:**
   ```powershell
   ./gradlew.bat validateBundledModelForRelease
   ```
   - **Result:** `GradleException: Release build aborted: bundled model 'cropdoc_leaf_v1_int8.tflite' is a placeholder (17208 bytes < 524288 bytes threshold)`.
   - **Status:** Fail-closed release protection is 100% active and verified.

---

## 6. Audit Preservation Statement
No files have been modified, overwritten, or deleted during this baseline audit. All existing uncommitted user work is strictly preserved.
