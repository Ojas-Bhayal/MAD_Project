# Phase N: Debug/Release Isolation and Release Readiness Report

**Audit Date:** 2026-10-24T23:50:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Release Engineering Lead, Build & Safety Gate Specialist  

---

## 1. Executive Summary: Release Readiness Scorecard

| Evaluation Dimension | Status | Evidence / Verification Outcome | Release Readiness Verdict |
| :--- | :--- | :--- | :--- |
| **Debug Buildability** | **READY** | `./gradlew.bat assembleDebug` succeeds in 3s (`app-debug.apk`, 51.4 MB). | **SUITABLE FOR CONTROLLED DEMO** |
| **Research Demo Functionality**| **READY** | All 5 pipeline stages, photo capture, gallery picker, genuine soil/rice samples, trilingual localization verified. | **SUITABLE FOR CONTROLLED DEMO** |
| **Local Unit & Safety Tests** | **READY** | 136 tests passed across 19 suites, 0 failures, 0 skipped. | **PASSED** |
| **Static Code Quality** | **CONDITIONAL**| Checkstyle passes all quality checks except 7 legacy `FileLength` notices (> 250 lines). | **REQUIRES BASELINE EXEMPTION OR REFACTORING** |
| **Physical Hardware Verification** | **BLOCKED** | `adb devices -l` reports 0 connected devices. No physical testing performed. | **HARDWARE UNVERIFIED** |
| **Production Release Readiness** | **BLOCKED (P0)**| Production model `cropdoc_leaf_v1_int8.tflite` is a 16.8 KB placeholder. `validateBundledModelForRelease` intentionally aborts release builds. | **STRICTLY BLOCKED FROM RELEASE** |

---

## 2. Debug vs. Release Architectural Isolation Audit

The separation between production workflows and the experimental research demo was audited across 5 independent defense-in-depth layers:

```
[Layer 1: Bottom Navigation Bar]
MainActivity: if (!BuildConfig.DEBUG) { do not add researchDemoFragment tab }

[Layer 2: NavController Router]
MainActivity: if (targetDestId == R.id.researchDemoFragment && !BuildConfig.DEBUG) return false;

[Layer 3: ViewModel Factory Guard]
ResearchDemoViewModel.provideFactory(): if (!BuildConfig.DEBUG) throw new IllegalStateException();

[Layer 4: ViewModel Constructor Guard]
ResearchDemoViewModel(): if (!BuildConfig.DEBUG) throw new IllegalStateException();

[Layer 5: Persistence Boundary Guard]
AnalyzeLeafUseCase: Only ClassificationOutcome.Diagnosis touches Room/Advice. Research outcomes NEVER enter ScanDao.
```

### Residual Isolation Finding:
- **Finding ISO-01 (Severity: Medium):** Research models (`crop_domain_gate_fp32.tflite` and `unified_six_class_fp32.tflite`, ~12.4 MB) are stored in `src/main/assets/research_models/`. While runtime execution is strictly blocked by the 5 layers above, AGP packages these 12.4 MB of assets into release APKs.
- **Remediation:** Move directory to `src/debug/assets/research_models/` so Gradle automatically excludes them from release artifacts.

---

## 3. Release Verification Gate Audit (`validateBundledModelForRelease`)

The custom Gradle release task `validateBundledModelForRelease` is wired directly into `preReleaseBuild`. Any release invocation (`assembleRelease`, `bundleRelease`) automatically triggers this gate:

### 9 Verification Steps Enforced by the Gate:
1. **Asset Existence Check:** Confirms `manifest.json`, `labels.txt`, and `cropdoc_leaf_v1_int8.tflite` exist in `src/main/assets/models/`.
2. **Manifest JSON Parsing:** Validates presence of `sha256` and `labels_checksum`.
3. **Model & Manifest Version Consistency:** Ensures `model_version == "1.0.0-int8"` and `model_id == "cropdoc_leaf_v1_int8"`.
4. **Declared Input & Output Shapes:** Requires `input_shape == [1, 224, 224, 3]` and `output_class_count == 16`.
5. **Model Size Threshold (Placeholder Guard):** Enforces `modelFile.length() >= 512 * 1024` (512 KB).
   - **Current Status:** 16,800 bytes $< 524,288$ bytes $\rightarrow$ **Halts build immediately**.
6. **FlatBuffer Magic Header:** Requires bytes 4–7 to match ASCII `"TFL3"`.
7. **Model SHA-256 Digest Verification:** Computes byte-stream SHA-256 and compares to manifest.
8. **Label Count and CRC32 Checksum:** Validates ordered lines in `labels.txt` match manifest `labels_checksum` (`547ca66a`).
9. **Build-Time Model Graph Examination:** Executes `inspect_tflite_graph.py` to confirm actual input/output tensor shapes and dtypes (`uint8` input, `float32` output).

---

## 4. Release Decision: Controlled Demo vs. Production

### A. Suitability for Controlled Technical Demonstration: YES (APPROVED)
- The debug APK (`app-debug.apk`) can be installed on an Android device or emulator for an end-to-end technical demonstration.
- Demonstrates:
  - Material 3 Edge-to-Edge interface with responsive WindowInsets.
  - Full trilingual switching across English, Hindi, and Telugu without process restart.
  - Isolated multi-stage research evaluation harness with real-time camera capture, gallery picking, and genuine bundled samples.
  - Fail-closed containment on the production scanner displaying *"Diagnosis is currently unavailable. A verified model update is required."*
  - Offline Room database history, local ICAR advice browsing, and DPDP-compliant user data erasure.

### B. Suitability for Commercial Production Release: NO (STRICTLY BLOCKED)
- **Primary Blocker (P0):** The production classifier model is an unpopulated 16.8 KB placeholder. Shipping this to real farmers would violate the core agronomic safety mission.
- **Secondary Blockers:** Checkstyle baseline legacy FileLength notices, research assets packaged in main source set, and lack of physical device fleet validation.
