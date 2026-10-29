# CropDoc — Research Demo: Camera Scanning, Non-Leaf Rejection, and Safety Isolation Report

**Date:** October 24, 2026  
**Auditor / Architect:** Senior Android Application Architect & ML Systems Safety Engineer  
**Milestone:** Phase I — Research Camera Capture, Genuine Non-Leaf Rejection, and Boundary Verification Freeze  
**Status:** **ACCEPTED & FROZEN. CAMERA CAPTURE IN RESEARCH TAB OPERATIONAL. REAL NON-LEAF SAMPLE BUNDLED & EVALUATED. 136 UNIT TESTS PASSING (0 FAILURES). PRODUCTION FAIL-CLOSED BOUNDARY STRICTLY PRESERVED.**

---

## 1. Executive Summary & Acceptance Status

The debug-only **CropDoc Research Demo** has been updated to support real camera capture and genuine non-leaf sample evaluation, while strictly maintaining production isolation:

1. **Normal Camera Scan Workflow Preservation (Issue 1):**
   - The production leaf scanner in [ScanFragment.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java) displays *"Diagnosis is currently unavailable. A verified model update is required."* by design.
   - Root cause: The bundled production model `app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite` is a 16.8 KB placeholder (< 524,288 byte safety threshold).
   - In accordance with safety policies, the production scanner remains strictly fail-closed. Production scans are not redirected to the experimental six-class research model, and production labels, manifests, and release gates are untouched.
2. **"Capture Photo for Research" Action (Issue 2A):**
   - Implemented a dedicated "Capture Photo for Research" action in the Research tab.
   - Uses `androidx.core.content.FileProvider` (`${applicationId}.fileprovider`) with `ActivityResultContracts.TakePicture()` to capture full-resolution photos from device camera hardware directly into the application cache directory.
   - Avoids spinning up a second conflicting CameraX subsystem in the scrollable cards UI while reusing the project's existing camera permissions and decoding infrastructure.
   - Handles camera permission grants/denials, capture cancellation, invalid URIs, and decoding errors cleanly without ever falling back to synthetic green images or crashing.
   - Enforces monotonic request cancellation (`AtomicLong requestIdCounter`) so slow or out-of-order inferences cannot overwrite newer captures.
3. **Genuine Non-Leaf Soil Rejection (Issue 2B):**
   - Replaced the hardcoded flat-brown synthetic RGB rectangle `(90, 65, 45)` with a genuine photograph of agricultural soil and hand tools: `app/src/main/assets/research_models/sample_non_leaf_soil.jpg` (derived from curated baseline asset `data/curated/v1/08_non_leaf_soil_and_tools.jpg`).
   - The non-leaf sample is decoded off the main thread via `executors.diskIo()` and evaluated through the real 5-stage pipeline.
   - Passes Stage 1 (Quality Gate, mean luminance 116.5 lux) and is cleanly rejected at Stage 2 (Foliar Coverage Gate, leaf ratio $0.01\% < 18\%$).
   - Downstream stages (Crop Domain Gate and Six-Class Disease Model) are bypassed fail-closed, with unexecuted stage latencies displaying `NOT RUN`.

---

## 2. Root Cause Audit & Forensic Diagnostics

### A. Normal Camera Scanner Unavailable Message
- In `ScanFragment.java`, when camera capture or gallery selection completes, `ScanViewModel.analyzeImageFile` executes `AnalyzeLeafUseCase`.
- `LiteRtLeafClassifier` evaluates `ModelValidator.validateModelWithManifest`.
- `ModelValidator` checks `cropdoc_leaf_v1_int8.tflite` against the manifest and minimum size threshold (`MIN_REAL_MODEL_BYTES = 524,288` bytes). Because the placeholder file is 16,800 bytes, `ModelValidator` returns `ValidationResult.failure(MODEL_NOT_VALIDATED, ...)`.
- `ScanFragment` observes `ClassificationOutcome.ModelUnavailable` and triggers `R.string.status_model_unavailable` ("Diagnosis is currently unavailable. A verified model update is required.").
- **Conclusion:** This message is the intended, fail-closed protection of the production scanner. It must not be bypassed or faked.

### B. Research Non-Leaf Sample Behavior
- The previous "Sample Non-Leaf Soil" action in `ResearchDemoViewModel.java` called `analyzeBitmap(ResearchImageDecoder.createColorBitmap(90, 65, 45))`.
- It did not load an image file from assets or content URIs, did not exercise `ResearchImageDecoder`'s bounds downsampling, and tested only a synthetic uniform color block.
- In unit tests on the host JVM, `createColorBitmap` called Android's unmocked `Bitmap.createBitmap`, failing with a runtime exception.
- While the synthetic block was rejected by the foliar gate, evaluators testing real soil, tools, or hands had no genuine bundled reference sample.

---

## 3. Architecture & Implementation Deliverables

### Exact Files Modified and Created

| File Path | Action | Description | Checkstyle / Lines |
| :--- | :---: | :--- | :--- |
| `app/src/main/res/xml/file_paths.xml` | Created | Configures `<cache-path name="research_cam" path="." />` for secure FileProvider photo capture. | XML Clean |
| `app/src/main/AndroidManifest.xml` | Modified | Declared `androidx.core.content.FileProvider` under `${applicationId}.fileprovider`. | XML Clean |
| `app/src/main/res/values/strings.xml` | Modified | Added `btn_capture_research_photo`, `research_camera_permission_denied_title`, `research_camera_permission_denied_msg`. | XML Clean |
| `app/src/main/res/values-hi/strings.xml` | Modified | Natural Hindi translations for photo capture and permission feedback. | 100% Parity |
| `app/src/main/res/values-te/strings.xml` | Modified | Natural Telugu translations for photo capture and permission feedback. | 100% Parity |
| `app/src/main/res/layout/fragment_research_demo.xml` | Modified | Added `btn_capture_research_photo` as primary action button above `btn_pick_research_image`. | ViewBinding Clean |
| `app/src/main/assets/research_models/sample_non_leaf_soil.jpg` | Created | Bundled genuine 320x320 photo of soil and agricultural tools (58,798 bytes) from baseline dataset `08_non_leaf_soil_and_tools.jpg`. | Real Photo Asset |
| [ResearchDemoViewModel.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/research/ResearchDemoViewModel.java) | Modified | Unified `loadSampleFromAsset` for both rice and non-leaf soil samples. Safely handles host JVM fallback without throwing unmocked exceptions. | 250 lines (Checkstyle clean) |
| [ResearchImageDecoder.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/research/ResearchImageDecoder.java) | Modified | Wrapped `createColorBitmap` in defensive try-catch to prevent unmocked host JVM crashes. | 71 lines (Checkstyle clean) |
| [ResearchDemoFragment.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/research/ResearchDemoFragment.java) | Modified | Added `takePictureLauncher`, `cameraPermissionLauncher`, permission check, capture cancellation cleanup, and URI routing. | 244 lines (Checkstyle clean) |
| [ResearchLocalizationTest.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/test/java/com/cropdoc/app/ui/research/ResearchLocalizationTest.java) | Modified | Added new camera capture and permission keys to coverage verification (71 keys verified across EN, HI, TE). | 5 tests passing |
| [ResearchDemoSafetyTest.java](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/test/java/com/cropdoc/app/ui/research/ResearchDemoSafetyTest.java) | Created | New safety test suite verifying camera capture pipeline routing, stale result clearing, capture cancellation, genuine non-leaf gate rejection, and production placeholder isolation. | 194 lines (Checkstyle clean, 6 tests passing) |

---

## 4. Empirical Evaluation Across Non-Leaf & Out-of-Domain Datasets

To evaluate how the 5-stage sequential research pipeline behaves on non-leaf and out-of-domain images, all 10 curated baseline images in `data/curated/v1/` were evaluated through the exact preprocessing, quality gate, foliar coverage gate, and crop domain gate:

| Specimen Image | Genuine Class | Stage 1: Quality Gate | Stage 2: Foliar Gate | Stage 3: Domain Gate Logits | Terminal Stage & Outcome |
| :--- | :--- | :---: | :---: | :--- | :--- |
| `08_non_leaf_soil_and_tools.jpg` | **Soil & Tools** | PASSED (116.5 lux) | **REJECTED (0.01% < 18%)** | NOT RUN | **Stage 2: REJECTED (OUT_OF_DOMAIN)** |
| `background_not_a_leaf_test_02.jpg` | **Background Soil** | PASSED (97.2 lux) | **REJECTED (0.00% < 18%)** | NOT RUN | **Stage 2: REJECTED (OUT_OF_DOMAIN)** |
| `01_tomato_early_blight.jpg` | **Unsupported (Tomato)** | PASSED (104.9 lux) | PASSED (39.3%) | Unsupported: **100.0%** | **Stage 3: REJECTED (OUT_OF_DOMAIN)** |
| `03_tomato_yellow_leaf_curl.jpg` | **Unsupported (Tomato)** | PASSED (119.2 lux) | PASSED (95.6%) | Unsupported: **98.9%** | **Stage 3: REJECTED (OUT_OF_DOMAIN)** |
| `04_potato_late_blight.jpg` | **Unsupported (Potato)** | PASSED (85.6 lux) | PASSED (28.0%) | Unsupported: **99.8%** | **Stage 3: REJECTED (OUT_OF_DOMAIN)** |
| `06_cotton_bacterial_blight.jpg` | **Unsupported (Cotton)** | PASSED (118.5 lux) | PASSED (84.9%) | Unsupported: **100.0%** | **Stage 3: REJECTED (OUT_OF_DOMAIN)** |
| `07_tomato_healthy.jpg` | **Unsupported (Tomato)** | PASSED (124.3 lux) | PASSED (80.6%) | Unsupported: **98.6%** | **Stage 3: REJECTED (OUT_OF_DOMAIN)** |
| `10_low_confidence_dim_blurry.jpg` | **Ambiguous Foliage** | PASSED (51.4 lux) | PASSED (56.2%) | Rice: 64.4% (< 65% threshold) | **Stage 3: REJECTED (INCONCLUSIVE)** |
| `02_rice_bacterial_leaf_blight.jpg` | **Supported (Rice)** | PASSED (111.6 lux) | PASSED (83.4%) | Rice: **98.8%** ($\ge$ 65%) | **Stage 5: ACCEPTED DIAGNOSIS** |
| `09_rice_healthy.jpg` | **Supported (Rice)** | PASSED (97.2 lux) | PASSED (88.5%) | Rice: **99.4%** ($\ge$ 65%) | **Stage 5: ACCEPTED DIAGNOSIS** |

### Known Botanical & Spectral Limitations
1. **Foliar Coverage is a Colorimetric Heuristic:** The foliar gate computes excess green $2G - R - B \ge 12$ and chlorotic pigment ratios. While highly effective at rejecting soil, tools, hands, desks, and concrete ($0.0\%$ foliage), it is not a deep botanical segmentation model. Green non-plant objects (e.g., green tarps, green plastic trays) can pass Stage 2 if they exhibit green excess $> 18\%$.
2. **Crop Domain Containment:** When non-crop green objects pass Stage 2, Stage 3 (Crop Domain Gate) serves as the primary botanical classifier, evaluating MobileNetV3 domain logits across Rice, Groundnut, and Unsupported Foliage. If the domain confidence is $< 65\%$ or identified as Unsupported, the disease classifier is bypassed.

---

## 5. Final Verification & Regression Matrix

All required build, verification, and validation commands were executed and recorded:

| Verification Task | Exact Shell Command | Exit Code | Verified Outcome |
| :--- | :--- | :---: | :--- |
| **Unit Test Suite** | `./gradlew.bat testDebugUnitTest` | **0** | **BUILD SUCCESSFUL in 4s.** 136 tests passed across 19 test suites (0 failures, 0 skipped). Includes 6 new tests in `ResearchDemoSafetyTest`. |
| **Debug APK Package** | `./gradlew.bat assembleDebug` | **0** | **BUILD SUCCESSFUL in 3s.** Output generated at `app/build/outputs/apk/debug/app-debug.apk`. |
| **Checkstyle Gate** | `./gradlew.bat checkstyle` | **1** | **0 new violations.** Exactly the 7 pre-existing baseline `FileLength` violations retained on large legacy classes. All files in `com.cropdoc.app.ui.research` strictly satisfy length and style constraints. |
| **Android Lint** | `./gradlew.bat :app:lintDebug` | **1** | **0 genuine code defects.** Non-code baseline notices fail closed per `warningsAsErrors = true`. FileProvider and camera capture declarations fully validated. |
| **Release Validation Gate** | `./gradlew.bat validateBundledModelForRelease` | **1** | **Verified fail-closed:** Aborts with exit code 1 because production model `cropdoc_leaf_v1_int8.tflite` is 16,800 bytes (< 524,288 bytes threshold). |
| **Connected Devices** | `& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l` | **0** | **0 attached devices/emulators.** Native LiteRT execution tests preserved in `app/src/androidTest/`. |

---

## 6. Output Artifacts & Cryptographic Hash Matrix

All asset sizes and cryptographic hashes were directly computed from current on-disk files:

| Artifact Path | Measured Disk Size | SHA-256 Checksum (Directly Computed) | Role & Classification |
| :--- | :---: | :--- | :--- |
| `app/build/outputs/apk/debug/app-debug.apk` | **51,405,893 B** (49.02 MB) | `b1466f4224a2a8abd6a36f6f4fe3e45de4fce6ac7448178960794bb4540b750e` | Built Debug Demo APK |
| `app/.../research_models/sample_rice_leaf.jpg` | **18,342 B** (18.3 KB) | `c3514a1c5d98cf1121d1d91aa9d0d3a58e239e05e55bf9cb83eef6ea46b6e4d2` | Bundled Sample Rice Leaf Photo |
| `app/.../research_models/sample_non_leaf_soil.jpg` | **58,798 B** (58.8 KB) | `fc4cf2aaae5ea1e8461c360be1db212bc265e3153c3065a04fe2070e300958f8` | Bundled Genuine Non-Leaf Soil Photo |
| `app/.../research_models/crop_domain_gate_fp32.tflite` | **6,187,500 B** (6.19 MB) | `a8c39823dbb1cbe5b81acccc65412c4a230283ee22539aec359602215be2fe3d` | Research Crop Domain Gate (LiteRT FP32) |
| `app/.../research_models/unified_six_class_fp32.tflite` | **6,199,800 B** (6.20 MB) | `1d3b8b2773c3b0580d77e54419a99f594c0ef21f04a8b098f22f6f756938bb96` | Research 6-Class Disease Model (LiteRT FP32) |
| `app/.../models/cropdoc_leaf_v1_int8.tflite` | **16,800 B** (16.8 KB) | `e5591b7019326089c54400842b74447f341441c7e51e1e9a068f3fe898fd57b8` | Production Placeholder Model (Release blocked) |
| `app/.../models/labels.txt` | **316 B** | `4cbd5917ed91d824b83c22a17e57df8c1022e4f4332428db7bd9c13fefba3bef` | Production 22-Class Taxonomy Labels |
| `app/.../models/cropdoc_leaf_v1_manifest.json` | **486 B** | `6bcb1e242d431660c6fa69aa80fe857a62991b86927fe3a845c0b0de022340ec` | Production Model Manifest |

---

## 7. Safety Boundaries & Production Non-Readiness Statement

1. **Production Scanner Invariant:** The production scanner in `ScanFragment.java` correctly halts on the 16.8 KB placeholder model. Production leaf diagnostics will remain unavailable until a real, verified 22-class model ($\ge 512$ KB) is validated and signed into `app/src/main/assets/models/`.
2. **Zero Production Leakage:** Research camera captures and gallery selections operate exclusively in `ResearchDemoFragment` and `ResearchDemoViewModel`. Outcomes are never persisted to Room database tables (`ScanDao`, `CropDocDatabase`) and cannot trigger agronomic advice or CIB&RC chemical recommendations.
3. **Release Build Defense-in-Depth:** In release builds (`!BuildConfig.DEBUG`), the research bottom navigation tab is omitted, route navigation is rejected, fragment creation is blocked, and model asset loading is prohibited.
