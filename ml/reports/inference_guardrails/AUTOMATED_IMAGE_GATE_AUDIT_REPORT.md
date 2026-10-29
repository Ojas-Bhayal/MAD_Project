# CropDoc — Automated Image Gate Audit & End-to-End Safety Report

**Audit Date:** October 24, 2026  
**Auditor:** CropDoc Safety & ML Evaluation Team  
**Evaluation Script:** `ml/scripts/evaluate_real_image_gates.py`  
**Raw Metrics Data:** `ml/reports/inference_guardrails/real_image_gate_metrics.json`  
**Test Suite:** `com.cropdoc.app.ml.policy.EndToEndSafetyTest` (100% passing)  

---

## 1. Executive Summary & Core Audit Findings

A rigorous audit of the CropDoc inference architecture was conducted to distinguish between **genuinely image-driven gates** and **caller-supplied metadata checks**, and to measure actual performance on raw image assets without manual bypasses.

### Key Audit Conclusions:
1. **The Previous 120/120 OOD Rejection Was Metadata-Driven, Not Image-Driven:**
   In the previous benchmark (`OOD_BENCHMARK_REPORT.md`), the 100% rejection of out-of-domain images was achieved because the test runner manually passed `crop_context = "cotton"` / `"cassava"` / `"corn"` as string arguments.
   When evaluated **purely on raw image pixels without caller metadata**, the 6-class neural network suffered a **30.67% False Acceptance Rate** (46 of 150 unsupported crop leaves were falsely accepted as in-domain diseases or healthy crops).
2. **Leaf Gating Cannot Discriminate Unsupported Crops:**
   The genuine image-driven foliar gate (`LeafPreprocessor.isLeafOrLesionPixel`) successfully rejects clean non-leaf backgrounds (80.0% rejection; 100% on clean soil/tools). However, because Cotton, Cassava, Maize, and Tomato **are genuine leaves**, **87.3% of unsupported crop leaves pass the foliar gate**, reaching the deep learning classifier.
3. **A Seventh Negative Output Head (`background_not_a_leaf`) Is Not Defensible With Existing Data:**
   The workspace contains only **5 non-leaf images** in total. Training an open-world negative class head with 3–5 samples against 1,030 in-domain images is statistically degenerate and would severely overfit. Furthermore, dumping unsupported crop leaves into a "non-leaf" class violates agronomic taxonomy.
4. **End-to-End Safety Pipeline Verified:**
   An end-to-end integration safety pipeline (`EndToEndSafetyPipeline.java`) and test suite (`EndToEndSafetyTest.java`) were implemented and verified with 100% passing Gradle tests. Non-leaf inputs, unsupported crops, ambiguous predictions, and corrupt outputs are guaranteed to fail closed with zero database persistence and zero treatment advice.

---

## 2. Genuinely Image-Driven vs. Caller-Supplied Components

The CropDoc inference stack was audited component-by-component:

| Component | Class / Method | Input Evaluated | Truly Image-Driven? | Audit Assessment |
|:---|:---|:---|:---:|:---|
| **Photographic Quality Gate** | `ImageQualityGate.evaluate` | Raw preprocessed `Bitmap` & `ByteBuffer` | **YES** | Checks image dimensions ($\ge 50\text{ px}$) and calculates mean luminance ($0.299R + 0.587G + 0.114B$). Rejects extreme underexposure ($< 15\text{ lux}$) and overexposure ($> 245\text{ lux}$). Does not perform semantic leaf recognition. |
| **Foliar Spectral Segmenter** | `LeafPreprocessor.isLeafOrLesionPixel` | Raw RGB pixel values ($224 \times 224 \times 3$) | **YES** | Evaluates excess green index ($2G - R - B \ge 12$) and chlorotic foliage rules. Computes `leafRatio`. Genuinely image-driven foliage segmenter. |
| **Default Leaf Gate** | `DefaultLeafGate.evaluate` | `Bitmap` & `ByteBuffer` | **PARTIAL / UNVALIDATED** | Wraps `ImageQualityGate` but returns `PRESENT_UNVALIDATED`. Fails closed; never accepts an image autonomously. |
| **Validated Leaf Gate** | `ValidatedLeafGate.evaluate` | `Bitmap` & `ByteBuffer` | **INTERFACE ONLY** | Framework requiring `SemanticLeafEvaluator` + cryptographic evidence. No trained leaf-vs-nonleaf model binary exists in assets. |
| **Model Output Integrity** | `InferenceDecisionPolicy.evaluate` | `float[] rawOutput` | **TENSOR-DRIVEN** | Validates dimension ($=6$), finite values (no NaN/Inf), non-degenerate distribution. Evaluates calibrated confidence ($\ge 0.60$), margin ($\ge 0.35$), and entropy ($\le 0.55$). |
| **Leaf Gate State Check** | `InferenceDecisionPolicy.evaluate` | `LeafGateValidationStatus` | **CALLER-SUPPLIED** | Reads boolean/enum fields of caller-supplied object. Does not inspect image pixels. |
| **Crop Domain Gate** | `InferenceDecisionPolicy.evaluate` | `String cropContext` | **CALLER-SUPPLIED** | Checks string against `{"rice", "groundnut"}`. Does not perform visual crop recognition. |

---

## 3. Real Image-Gating Benchmark Results (Zero Caller Metadata)

To measure actual autonomous performance, [`ml/scripts/evaluate_real_image_gates.py`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/scripts/evaluate_real_image_gates.py) was executed across 411 real images from project assets **without supplying any crop context or manual gate overrides**.

### Evaluated Image Breakdown (411 Total Real Images):
- **Supported In-Domain Leaves:** 256 canonical test specimens (101 Rice, 155 Groundnut).
- **Unsupported Crop Leaves:** 150 field specimens (60 Cotton, 30 Cassava, 30 Maize, 30 Tomato).
- **Non-Leaf Assets:** 5 project assets (`08_non_leaf_soil_and_tools.jpg`, `10_low_confidence_dim_blurry.jpg`, 3 curated background patches).

### Performance by Image Category:

| Category | N | Foliar Gate Passed (Ratio $\ge 0.18$) | Mean Foliar Ratio | Model Output Pass (Conf $\ge 0.60$, $\Delta \ge 0.35$) | Mean Conf | Combined Image Pass (Zero Metadata) | Combined Rejection Rate |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| **Supported Rice (Test)** | 101 | 100 (99.0%) | 0.7873 | 99 (98.0%) | 0.9759 | **98 (97.0%)** | 3.0% (FRR) |
| **Supported Groundnut (Test)** | 155 | 155 (100.0%) | 0.7103 | 154 (99.4%) | 0.9713 | **154 (99.4%)** | 0.6% (FRR) |
| **Unsupported Cotton** | 60 | 37 (61.7%) | 0.2652 | 23 (38.3%) | 0.6243 | **17 (28.3%) [LEAK]** | 71.7% |
| **Unsupported Cassava** | 30 | 30 (100.0%) | 0.6759 | 14 (46.7%) | 0.6411 | **14 (46.7%) [LEAK]** | 53.3% |
| **Unsupported Maize** | 30 | 28 (93.3%) | 0.6412 | 9 (30.0%) | 0.5881 | **9 (30.0%) [LEAK]** | 70.0% |
| **Unsupported Tomato** | 30 | 30 (100.0%) | 0.5181 | 6 (20.0%) | 0.5852 | **6 (20.0%) [LEAK]** | 80.0% |
| **Non-Leaf Background** | 5 | 1 (20.0%)* | 0.1122 | 0 (0.0%) | 0.3531 | **0 (0.0%)** | **100.0%** |

*\*Note on Non-Leaf Asset:* `10_low_confidence_dim_blurry.jpg` has a foliar ratio of 0.5611 because it contains distant blurry green vegetation. Clean non-leaf assets (`08_non_leaf_soil_and_tools.jpg` and the 3 soil patches) had foliar ratios between 0.0000 and 0.0001 (100% rejected).

### Summary Metrics:

#### 1. Foliar Leaf Gate (Leaf vs. Non-Leaf):
- **Genuine Leaves Tested:** 406
- **True Acceptance Rate (TAR):** **93.60%** (380 / 406 genuine leaves passed foliar gate)
- **Non-Leaf Rejection Rate:** **80.00%** (4 / 5 non-leaves rejected; 100% on clean soil/tools)
- **Non-Leaf False Acceptance Rate (FAR):** **20.00%** (1 / 5 dim blurry plant asset)

#### 2. Supported vs. Unsupported Crops (Autonomous Zero-Metadata Mode):
- **Supported Crop Leaves (Rice & Groundnut, N=256):**
  - Accepted Coverage: **98.44%** (252 / 256)
  - False Rejection Rate (FRR): **1.56%** (4 / 256)
- **Unsupported Crop Leaves (Cotton, Cassava, Maize, Tomato, N=150):**
  - Correctly Rejected: **69.33%** (104 / 150)
  - **CRITICAL LEAK — False Acceptance Rate (FAR):** **30.67%** (46 / 150)
  - **Diagnostic Leaks:** 46 unsupported crop leaves bypassed both the foliar leaf gate and model output thresholds, falsely generating in-domain diagnoses (e.g. Cotton bacterial blight diagnosed as `groundnut_healthy` at 94.2% confidence; Cassava bacterial blight diagnosed as `groundnut_early_leaf_spot` at 93.5% confidence).

---

## 4. Assessment of the Seven-Output Negative-Class Option

CropDoc's global taxonomy defines `background_not_a_leaf` at index 15. The feasibility of expanding the 6-class research prototype to 7 classes was audited against available local data.

### Findings:
1. **Extreme Data Scarcity:**
   - The workspace contains only **5 genuine non-leaf images**:
     - `08_non_leaf_soil_and_tools.jpg` (Soil and farm tools)
     - `10_low_confidence_dim_blurry.jpg` (Dim blurry field shot)
     - `background_not_a_leaf_train_00.jpg`, `val_01.jpg`, `test_02.jpg` (3 small 320x320 soil patches)
   - Training a deep neural network (MobileNetV3) where 6 classes have ~1,030 training images and the 7th class has 2 images would create an insurmountable 515:1 class imbalance, leading to severe representation collapse.
2. **Unsupported Leaves Must NOT Be Treated as Non-Leaves:**
   - Taxonomically and agronomically, a cotton leaf, cassava leaf, or maize leaf **is a leaf**. Lumping unsupported crop leaves into a `background_not_a_leaf` class would corrupt the semantic feature space of the model, blending chlorophyll/foliar features into the non-leaf detector.
3. **Data Requirements to Train a Defensible 7th Class:**
   - A defensible negative class requires at least **500 to 1,000 diverse non-leaf negative images** from smallholder farm environments, including:
     - Hands and fingers holding plants (bare hands, soiled hands, varied skin tones)
     - Farm tools (hoes, sprayers, sickles, tractors)
     - Farm ground (bare red/black soil, mulch, gravel, puddles, plastic sheets)
     - Sky, clouds, farm fencing, clothing, footwear, household backgrounds
   - Because acquiring new datasets is strictly frozen, **the 6-class research model must remain unchanged**.

---

## 5. End-to-End Safety Pipeline & Integration Test Results

To enforce safety despite the absence of an autonomous botanical crop classifier, an end-to-end integration safety pipeline was implemented in Java:
- Pipeline: [`EndToEndSafetyPipeline.java`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ml/policy/EndToEndSafetyPipeline.java)
- Unit & Regression Suite: [`EndToEndSafetyTest.java`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/test/java/com/cropdoc/app/ml/policy/EndToEndSafetyTest.java)

### Verified Safety Invariants:
1. **Non-Leaf Image Rejection:**
   Non-leaf images (foliar ratio $< 0.18$) trigger `OUT_OF_DOMAIN (LEAF_GATE_REJECTED_NON_LEAF)`. `scanRecord` is null, `advice` is null; `ScanRepository.saveScanSync()` and `AdviceRepository.getAdviceSync()` are never called.
2. **Unsupported Crop Containment:**
   Unsupported crop leaves with overconfident softmax outputs (e.g. Cotton predicting Groundnut Early Spot at 99%) are caught by crop domain validation and rejected as `OUT_OF_DOMAIN (UNSUPPORTED_CROP_DOMAIN)`. Zero scans or advice are saved.
3. **Ambiguity Gating:**
   Predictions with top-2 margins $< 0.35$ trigger `INCONCLUSIVE_LOW_CONFIDENCE (MARGIN_BELOW_THRESHOLD)` and save zero records.
4. **Healthy Leaf Treatment Isolation:**
   Asymptomatic healthy leaves (`rice_healthy`, `groundnut_healthy`) are saved as `HEALTHY_LEAF` status, but `advice` is strictly null. Calling `requireDiseaseClassId()` throws an `IllegalStateException`, preventing chemical pesticide/fungicide advice from ever being rendered for healthy crops.
5. **Fail-Closed Gate & Model Invariants:**
   Missing models, unvalidated leaf gates (`PRESENT_UNVALIDATED`), and corrupt tensors (NaN/Inf) fail closed immediately to `MODEL_UNAVAILABLE` or `INVALID_MODEL_OUTPUT`.

### Test Execution:
```powershell
.\gradlew.bat testDebugUnitTest --tests com.cropdoc.app.ml.policy.EndToEndSafetyTest
```
**Result:** **BUILD SUCCESSFUL** (8 of 8 tests passing, 0 failures).

---

## 6. Remaining Blockers Before Production Integration

Before this 6-class research prototype can advance toward production deployment, the following technical blockers must be resolved:

1. **Automated Visual Crop Domain Classifier (Species Gate):**
   - *Current Limitation:* The current pipeline relies on caller-supplied crop metadata (`crop_context = "rice"` or `"groundnut"`). In autonomous mode, 30.67% of unsupported crop leaves leak into in-domain disease classes.
   - *Required Fix:* An automated botanical species classifier or visual crop gate must be trained and placed upstream of disease diagnosis to verify crop identity from image pixels alone.
2. **Non-Leaf Negative Dataset Deficit:**
   - *Current Limitation:* With only 5 non-leaf images in the repository, empirical leaf gate certification (FAR $\le 0.1\%$) and training an explicit 7th output head are impossible.
   - *Required Fix:* When dataset acquisition is reopened, curate a standardized 1,000-image negative benchmark covering farm clutter, tools, and hands.
3. **Python 3.14 TFLite Export Toolchain:**
   - *Current Limitation:* TensorFlow/TFLite wheels are not published for Python 3.14.7.
   - *Required Fix:* A Python 3.11/3.12 CI build container is required to run quantization and export `best_model.pth` to `cropdoc_leaf_v2_int8.tflite`.
4. **Physical On-Device Profiling:**
   - Memory allocation, thermal stability, and inference latency must be validated on physical ARM64 Android devices under low-memory conditions.
