# CropDoc — Out-of-Domain Benchmark & Calibration Report

**Evaluation Date:** October 24, 2026  
**Auditor / Evaluator:** CropDoc Safety & ML Evaluation Team  
**Model Architecture:** MobileNetV3-Small (`ml/experiments/unified_six_class_pilot/best_model.pth`)  
**Evaluation Script:** `ml/scripts/calibrate_and_benchmark_policy.py`  
**Metrics Output:** `ml/reports/inference_guardrails/ood_benchmark_metrics.json`  

---

## 1. Executive Summary Table

| Metric | Target | Validation Set (Tuning) | Held-Out Test Set (256 items) | OOD Stress Set (120 items) |
|:---|:---:|:---:|:---:|:---:|
| **Sample Count** | — | 216 canonical specimens | 256 canonical specimens | 120 unsupported field images |
| **Accepted Coverage** | $\ge 95.0\%$ | **97.69%** (211 / 216) | **98.83%** (253 / 256) | **0.00%** (0 / 120) |
| **False Rejection Rate (FRR)** | $\le 5.0\%$ | **2.31%** (5 / 216) | **1.17%** (3 / 256) | — |
| **Accuracy Among Accepted** | $\ge 98.0\%$ | **99.05%** (209 / 211) | **98.81%** (250 / 253) | — |
| **False Acceptance Rate (FAR)** | $\le 1.0\%$ | — | — | **0.00%** (0 / 120) [Guarded] |
| **OOD Rejection Rate** | $\ge 99.0\%$ | — | — | **100.00%** (120 / 120) [Guarded] |
| **Unguarded OOD Leakage** | Baseline | — | — | **39.17%** (47 / 120 leaks without guard) |

---

## 2. Threshold Selection Rationale (Validation Data Tuning)

### Strict Independence Protocol
In compliance with the CropDoc ML Integrity Protocol:
- Thresholds were tuned **strictly on the 216 validation specimens** across the six supported classes.
- The held-out test partition (256 specimens) and OOD stress dataset (120 images) were **completely untouched** during threshold tuning.

### Validation Grid Search
We evaluated candidate threshold combinations across:
- Confidence threshold $\tau_{\text{conf}} \in [0.60, 0.90]$
- Top-2 Margin threshold $\tau_{\text{margin}} \in [0.15, 0.50]$
- Normalized Shannon Entropy threshold $\tau_{\text{entropy}} \in [0.35, 0.55]$

#### Key Grid Configurations Evaluated on Validation Data:

| $\tau_{\text{conf}}$ | $\tau_{\text{margin}}$ | $\tau_{\text{entropy}}$ | Val Coverage | Val Accuracy (Accepted) | Val FRR | Selected? |
|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 0.60 | 0.15 | 0.55 | 98.61% | 98.12% | 1.39% | No (margin too narrow) |
| 0.60 | 0.25 | 0.45 | 97.69% | 98.58% | 2.31% | No (sub-optimal accuracy) |
| **0.60** | **0.35** | **0.55** | **97.69%** | **99.05%** | **2.31%** | **YES (OPTIMAL BALANCE)** |
| 0.70 | 0.35 | 0.45 | 96.30% | 99.04% | 3.70% | More conservative |
| 0.80 | 0.40 | 0.40 | 95.37% | 99.03% | 4.63% | Elevated false rejection |
| 0.85 | 0.50 | 0.35 | 92.59% | 99.00% | 7.41% | Coverage dropped < 95% |

### Selection Rationale:
Configuration $\tau_{\text{conf}} \ge 0.60$, $\tau_{\text{margin}} \ge 0.35$, $\tau_{\text{entropy}} \le 0.55$ achieved:
1. **Accuracy Enhancement:** Raised validation accuracy among accepted cases from baseline 97.22% to **99.05%** by filtering out 4 erroneous borderline predictions.
2. **High Diagnostic Coverage:** Maintained **97.69% coverage** (only 5 validation specimens rejected as inconclusive).
3. **Resilient Margin Barrier:** Requiring $\Delta \ge 0.35$ eliminates top-2 ambiguous flips between closely related symptoms (such as Rice Brown Spot vs Rice Blast).

---

## 3. Held-Out Test Set Performance (256 Pure Canonical Specimens)

The calibrated policy was evaluated strictly once against the held-out test partition comprising 256 unaugmented canonical physical specimens:

- **Total Test Specimens:** 256
- **Accepted Predictions:** 253 (**98.83% Coverage**)
- **Inconclusive / Rejected Predictions:** 3 (**1.17% False Rejection Rate**)
- **Correct Predictions Among Accepted:** 250 / 253 (**98.81% Test Accuracy**)

### Confusion Matrix of Accepted Test Cases (253 specimens)

```
Actual \ Predicted                [0] RiceBLB  [1] BrownSpot  [2] Blast  [3] RiceHlth  [4] GnutEarly  [5] GnutHlth
[0] Rice Bacterial Blight (39)         39            0            0           0             0              0
[1] Rice Brown Spot (14)                0           14            0           0             0              0
[2] Rice Leaf Blast (24)                0            2           22           0             0              0
[3] Rice Healthy (22)                   0            0            0          22             0              0
[4] Groundnut Early Spot (31)           0            0            0           0            30              1
[5] Groundnut Healthy (123)             0            0            0           0             0            123
```

### Breakdown of Inconclusive / Rejected Test Specimens (3 cases)
The policy safely intercepted 3 ambiguous test specimens and marked them `INCONCLUSIVE_LOW_CONFIDENCE`:
1. `data/raw/rice_mendeley_sethy/Brownspot/brownspot_orig_015.jpg` (Rice Brown Spot):
   - $p_1 = 0.5743$ ($< \tau_{\text{conf}} = 0.60$), $\Delta = 0.3984$, $H = 0.6660$.
   - Rejection Reason: `CONFIDENCE_BELOW_THRESHOLD`.
2. `data/raw/rice_mendeley_healthy_candidate/Original Images/Healthy Rice Leaf/Healthy_rice_leaf  (57).jpg` (Rice Healthy):
   - $p_1 = 0.5788$, $\Delta = 0.1899$ ($< \tau_{\text{margin}} = 0.35$), $H = 0.4612$.
   - Rejection Reason: `MARGIN_BELOW_THRESHOLD`.
3. `data/raw/mendeley_groundnut/train/healthy_leaf_1/dr_203_2298.jpg` (Groundnut Healthy):
   - $p_1 = 0.6275$, $\Delta = 0.2563$ ($< \tau_{\text{margin}} = 0.35$), $H = 0.3739$.
   - Rejection Reason: `MARGIN_BELOW_THRESHOLD`.

All 3 rejected specimens had borderline confidence or ambiguous margins. Rather than risking an erroneous diagnostic label, the system safely prompted for a rescanning.

---

## 4. Out-of-Domain (OOD) Stress Test Benchmark

### OOD Test Set Composition (120 Images)
- **60 Cotton Leaf Images:** 30 Cotton Bacterial Blight + 30 Cotton Healthy (Mendeley Cotton 2025 staged canonicals).
- **60 FieldPlant Images:** 30 Cassava Bacterial Blight (`manioc`) + 30 Maize Smut (`charbon`) from field captures.

### Benchmark Results: Guarded vs. Unguarded Architecture

| Pipeline Mode | Images Tested | Accepted (False Positives) | False Acceptance Rate | Correctly Rejected | Rejection Rate | Primary Rejection Reason |
|:---|:---:|:---:|:---:|:---:|:---:|:---|
| **Guarded Policy (Fail-Closed)** | 120 | **0** | **0.00%** | **120** | **100.00%** | `UNSUPPORTED_CROP_DOMAIN` (120) |
| **Unguarded Policy (Softmax Only)** | 120 | **47** | **39.17%** | **73** | **60.83%** | `MARGIN_BELOW_THRESHOLD` (57) |

### Why Softmax Confidence Alone Fails Universal Rejection Safety

When evaluated without crop domain gating (allowing the raw neural network logits to drive classification):
- **47 out of 120 unsupported images (39.17%) were falsely accepted as in-domain crop diseases!**
- Example overconfident false acceptances:
  - `bacterial_(10).jpg` (Cotton Bacterial Blight) predicted as `groundnut_healthy` with **94.20% confidence** and margin 0.9064!
  - `bacterial_(1).jpg` (Cotton Bacterial Blight) predicted as `groundnut_early_leaf_spot` with **84.79% confidence**!
  - `Charbon_de_mais-6-_jpg` (Maize Field Smut) predicted as `rice_leaf_blast` with **95.32% confidence**!
  - `Manioc_Bacteriose-3-_jpg` (Cassava Bacterial Blight) predicted as `groundnut_early_leaf_spot` with **93.52% confidence**!

#### Theoretical Root Cause:
Softmax is a relative exponentiation normalizer:
$$p_i = \frac{e^{z_i}}{\sum_{j=1}^K e^{z_j}}$$
In any closed-world model lacking a negative class or domain gate, out-of-distribution feature vectors that project closer to one class hyperplane than others naturally generate large positive logit differences, forcing high softmax probabilities.

#### Solution Enforced by Fail-Closed Decision Layer:
The `InferenceDecisionPolicy` enforces that **crop domain verification and leaf gate validation must succeed before any output distribution is evaluated**. Because Cotton, Cassava, and Maize are verified as unsupported crop types, all 120 images are rejected immediately as `OUT_OF_DOMAIN` with **0% False Acceptance Rate**.

---

## 5. Treatment of Non-Leaf and Background Inputs

Tested against `data/curated/v1/08_non_leaf_soil_and_tools.jpg` and synthetic background patterns:
- When processed by the semantic leaf gate, non-leaf inputs yield decision `REJECT` (`ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED`).
- The policy intercepts this in Stage 3 and outputs:
  - `outcomeType`: `OUT_OF_DOMAIN`
  - `rejectionDetail`: `LEAF_GATE_REJECTED_NON_LEAF`
  - `isAccepted`: `false`
- When leaf gate evidence is absent or unvalidated, the policy intercepts in Stage 3 and outputs:
  - `outcomeType`: `MODEL_UNAVAILABLE`
  - `rejectionDetail`: `LEAF_GATE_UNVALIDATED`
  - `isAccepted`: `false`
- Zero non-leaf images produced foliar logits, disease diagnosis, or agronomic advice.

---

## 6. Remaining Safety Gaps & Blockers Before Production Integration

While the decision layer successfully achieves fail-closed safety, four technical blockers must be resolved before this 6-class research prototype can replace the Android production classifier:

1. **Python 3.14 TFLite Converter Toolchain:**
   - The current workspace runs Python 3.14.7, for which Google/TensorFlow does not yet publish native TensorFlow or TFLite flatbuffer conversion wheels.
   - The research prototype is currently validated in PyTorch FP32, TorchScript INT8, and TorchScript FP32.
   - A dedicated Python 3.11/3.12 build environment is required to convert `best_model.pth` into `cropdoc_leaf_v2_int8.tflite`.

2. **Automated Visual Crop Domain Classifier / Reticle Gate:**
   - Currently, crop domain verification relies on the farmer's crop selection context (`"rice"`, `"groundnut"`, or reticle metadata).
   - If a farmer accidentally points the camera at an unsupported cotton plant while the app is in Rice mode, the visual features could still leak if visual crop identity is not checked.
   - A dedicated botanical species gate or integrated multi-crop visual reticle is recommended before unguided field deployment.

3. **Explicit Negative Class Head (`background_not_a_leaf`):**
   - CropDoc's production `ModelConfig.java` defines class index 15 as `background_not_a_leaf`.
   - The research prototype has only 6 heads. Adding a 7th explicit negative/background head trained on non-crop foliage and background noise will provide native open-set logit suppression directly inside the neural network.

4. **On-Device Physical Hardware Validation:**
   - Benchmarks were conducted on x86_64 CPU (Intel i7).
   - Once exported to TFLite INT8, on-device latency, thermal throttling, and memory allocation must be profiled on physical ARM64 Android devices using Android Profiler and LiteRT 2.3.0.
