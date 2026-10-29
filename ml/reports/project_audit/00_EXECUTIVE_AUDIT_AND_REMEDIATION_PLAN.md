# CropDoc — Executive Audit and Prioritized Remediation Plan

**Audit Completed:** 2026-10-24T23:58:00+05:30  
**Project Root:** `C:\Users\ojas\OneDrive\Desktop\MajorProjectMAD\CropDoc`  
**Auditor:** Senior Android Architect, Core Java Systems Engineer, ML Safety Auditor  
**Audit Scope:** End-to-End Repository, Codebase, Architecture, ML Assets, Packaging, Quality Gates  

---

## 1. Overall Health Assessment

The CropDoc project exhibits **exceptional software engineering discipline, strict architectural separation, and robust fail-closed safety guards**:
- **Application Core:** The Java architecture, manual dependency injection (`AppContainer`), bounded threading (`AppExecutors`), and Room database persistence are clean, memory-safe, and free of concurrency defects.
- **Controlled Demo Readiness:** **100% READY.** The debug APK compiles cleanly (`app-debug.apk`, 51.4 MB) and provides a fully functional demonstration of the single-activity Material 3 UX, trilingual switching (English, Hindi, Telugu), and the 5-stage research inference pipeline.
- **Production Readiness:** **BLOCKED (P0).** The production classification model (`cropdoc_leaf_v1_int8.tflite`) is a 16.8 KB placeholder. The custom release gate `validateBundledModelForRelease` intentionally aborts release builds to protect farmers from fabricated diagnostic advice.

---

## 2. Core Strengths Verified

1. **Fail-Closed Agronomic Safety:** When an unverified model is detected, the app deliberately halts without writing to the database or emitting false diagnoses, displaying *"Diagnosis is currently unavailable. A verified model update is required."*
2. **Defensive Research Isolation:** Research inference runs through an isolated harness (`ResearchInferenceHarness`) barred from writing to Room history or fetching treatment advice, guarded by 5 independent architectural layers.
3. **Trilingual Localization Parity:** Exactly 139 user-facing translatable strings with 100% parity across English, Hindi, and Telugu, backed by automated unit tests.
4. **Privacy by Design:** Strips GPS coordinates and hardware serial numbers from EXIF tags before image persistence; provides one-click account and data erasure compliant with the Indian Digital Personal Data Protection (DPDP) Act.
5. **Memory & Thread Safety:** Preallocated direct ByteBuffers eliminate GC churn; bounded executor thread pools ensure zero disk, network, or ML inference operations ever block the UI thread.
6. **Request-ID Concurrency Guard:** Atomic request counters prevent asynchronous camera captures and gallery picks from overwriting newer UI states.

---

## 3. Prioritized Audit Findings Matrix

| Finding ID | Severity | Category | Location | Summary of Finding | Consequence | Recommended Remediation |
| :--- | :---: | :--- | :--- | :--- | :--- | :--- |
| **P0-01** | **P0** | ML Safety / Release | `app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite` | Production classifier model is a 16.8 KB placeholder. | Release build intentionally blocked (`validateBundledModelForRelease`). Normal scanner cannot provide diagnoses. | Train and package real validated INT8 model ($\ge 512$ KB) and update manifest. |
| **P1-01** | **P1** | Packaging / Isolation | `app/src/main/assets/research_models/` | Research `.tflite` models (~12.4 MB) stored in `src/main/` instead of `src/debug/`. | Bundled into release APKs as dead storage. | Move to `src/debug/assets/research_models/`. |
| **P1-02** | **P1** | ML Safety | `app/src/main/java/com/cropdoc/app/ml/DefaultLeafGate.java` | Semantic LeafGate is unvalidated; returns `uncertain()`. | Classifier fails-closed on semantic gate check. | Train and integrate lightweight leaf/non-leaf neural classifier. |
| **P2-01** | **P2** | Maintainability | `LiteRtLeafClassifier.java`, `ModelValidator.java`, `InferenceDecisionPolicy.java`, `EndToEndSafetyPipeline.java` | 4 production classes exceed Checkstyle max length (250 lines). | `./gradlew.bat checkstyle` fails with 7 legacy violations. | Surgically extract helper delegate classes to bring each file under 250 lines. |
| **P2-02** | **P2** | Packaging | `app/build.gradle` (packaging) | Desktop ByteBuddy Windows DLLs (`attach_hotspot_windows.dll`, ~550 KB) packaged in APK. | Unnecessary desktop binary bloat in Android APK. | Add `packaging { resources { excludes += ['win32-x86*/**', '**/attach_hotspot*'] } }`. |
| **P2-03** | **P2** | Dependencies | `app/build.gradle` (line 144) | Full Guava 33.3.1 library (3.0 MB) included for a single `ListenableFuture` import. | Unnecessary DEX method count and APK size increase. | Replace `libs.guava` with `com.google.guava:listenablefuture:1.0`. |
| **P2-04** | **P2** | Security | `app/google-services.json` | Firebase configuration committed to Git repository. | Untrusted forks could consume Firebase quotas if API keys are unrestricted. | Enforce Google Cloud Console package & SHA-1 signing restrictions. Add to `.gitignore`. |
| **P3-01** | **P3** | UX / Storage | `ScanFragment.java` (line 162) | Temporary files `raw_<timestamp>.jpg` not deleted if CameraX throws before decode. | Transient cache clutter until OS reclamation. | Add explicit `temp.delete()` in CameraX error callbacks. |
| **P3-02** | **P3** | Accessibility | `ResultFragment.java` (line 75) | TextToSpeech language return code not checked for missing voice data. | Voice readout may fail silently on devices missing Hindi/Telugu voice packs. | Check `LANG_MISSING_DATA` and prompt user to download voice data. |
| **P3-03** | **P3** | UI | `activity_main.xml` (line 35) | Language toggle button checked state tint may have low contrast on older OEM ROMs. | Visual ambiguity of active language on older devices. | Add explicit `app:checkedButton` style attributes. |

---

## 4. Controlled Demo vs. Production Release Comparison

| Capability / Metric | Controlled Technical Demonstration | Commercial Production Release |
| :--- | :--- | :--- |
| **Build Variant** | `assembleDebug` | `assembleRelease` / `bundleRelease` |
| **Build Status** | **PASSED** (`app-debug.apk`, 51.4 MB) | **BLOCKED** (`validateBundledModelForRelease`) |
| **Production Scan Feature** | Fail-Closed Warning Displayed | Diagnostic Predictions Required |
| **Research Tab Feature** | **Fully Functional** (5-Stage Gated Pipeline) | **Disabled & Excluded by Design** |
| **Trilingual Language Selector**| **Fully Functional** (Instant Offline Switching) | **Fully Functional** |
| **Room Database & History** | **Fully Functional** (Local Offline Persistence) | **Fully Functional** |
| **Offline Treatment Advice** | **Fully Functional** (ICAR/CABI Local Catalog) | **Fully Functional** |
| **Device Verification** | Host JVM / Android Emulator | Physical Field-Testing Required |
| **Verdict** | **APPROVED FOR TECHNICAL DEMO** | **STRICTLY BLOCKED FROM SHIPPING** |

---

## 5. File-Removal Candidate Inventory (Awaiting Explicit User Approval)

In strict compliance with audit safety rules, **zero files have been deleted**. All candidates remain in `PENDING_APPROVAL` status:

| Candidate ID | Relative Path | Size | Recommendation | Approval Status |
| :---: | :--- | :---: | :--- | :---: |
| **RM-038** | `ml/scripts/get_config_client.py` | 658 B | **PROPOSE REMOVAL** (Obsolete scratch scrape script) | `PENDING_APPROVAL` |
| **RM-039** | `ml/scripts/inspect_exact_and_phash.py` | 1,845 B | **PROPOSE REMOVAL** (Superseded scratch script) | `PENDING_APPROVAL` |
| **RM-040** | `ml/scripts/inspect_groundnut_duplicates.py` | 2,027 B | **PROPOSE REMOVAL** (Superseded diagnostic script) | `PENDING_APPROVAL` |
| **RM-041** | `ml/scripts/inspect_remaining_cross.py` | 5,405 B | **PROPOSE REMOVAL** (Superseded diagnostic script) | `PENDING_APPROVAL` |
| **RM-042** | `ml/scripts/analyze_hamming_distances.py` | 1,293 B | **PROPOSE REMOVAL** (One-off exploratory script) | `PENDING_APPROVAL` |
| **RM-043** | `ml/scripts/test_268_split_audit.py` | 5,381 B | **PROPOSE REMOVAL** (Superseded split audit test) | `PENDING_APPROVAL` |
| **RM-044** | `ml/scripts/test_clustering_criteria.py` | 4,349 B | **PROPOSE REMOVAL** (Superseded threshold test) | `PENDING_APPROVAL` |
| **RM-045** | `ml/scripts/test_final_clustering_rule.py`| 2,292 B | **PROPOSE REMOVAL** (Superseded cluster test) | `PENDING_APPROVAL` |
| **RM-046** | `ml/scripts/test_refined_rule.py` | 2,256 B | **PROPOSE REMOVAL** (Scratch refinement test) | `PENDING_APPROVAL` |
| **RM-047** | `ml/scripts/test_rule_with_dh3.py` | 2,292 B | **PROPOSE REMOVAL** (Scratch threshold test) | `PENDING_APPROVAL` |
| **RM-048** | `ml/scripts/test_specimen_grouping.py` | 2,680 B | **PROPOSE REMOVAL** (Superseded grouping test) | `PENDING_APPROVAL` |
| **RM-049** | `ml/scripts/test_phash_merging.py` | 3,110 B | **PROPOSE REMOVAL** (Superseded pHash test) | `PENDING_APPROVAL` |
| **RM-050** | `ml/scripts/test_split_regeneration.py` | 5,977 B | **KEEP** (Valuable for split reproducibility) | `PENDING_APPROVAL` |

---

## 6. Recommended Next Actions in Dependency Order

1. **Step 1 (Cleanup Approval):** Review and approve the proposed file removals (RM-038 through RM-049).
2. **Step 2 (Packaging Cleanliness):** Add packaging exclusions in `app/build.gradle` for desktop DLLs (`win32-x86*/**`) and move `src/main/assets/research_models/` to `src/debug/assets/research_models/`.
3. **Step 3 (Dependency Leanliness):** Replace `libs.guava` with `com.google.guava:listenablefuture:1.0`.
4. **Step 4 (Checkstyle Refactoring):** Surgically extract helper classes for the 4 legacy production files to bring all classes under 250 lines and achieve 100% clean Checkstyle reports.
5. **Step 5 (Production ML Training):** In an isolated ML development environment, train, evaluate, and quantize the real MobileNetV3 production model ($\ge 512$ KB) and generate the production manifest.
