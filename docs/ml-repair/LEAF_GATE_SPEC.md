# CropDoc LeafGate: Image-Validation & Non-Leaf Gating Specification

**Document:** `docs/ml-repair/LEAF_GATE_SPEC.md`  
**Authors:** ML Engineering & Android Architecture Team  
**Status:** **PHASE 1 IMPLEMENTED (Quality Gate) / PHASE 2 SPECIFIED (Semantic Plug-In Ready)**

---

## 1. Executive Summary & Pipeline Topology

The CropDoc on-device ML pipeline is architected in four strictly sequential stages:

```text
[CameraX / Gallery Frame]
          |
          v
  [1. Preprocessing]          --> 224x224 RGB, isotropic square reticle crop, EXIF orient
          |
          v
  [2. Image Quality Gate]     --> Cheap sanity check: under/over-exposure, resolution
          |
          v
  [3. Semantic LeafGate]      --> [NOT VALIDATED — PLUG-IN READY] Leaf vs Non-Leaf Gate
          |
          v
  [4. Disease Classifier]     --> MobileNetV3 INT8 (LiteRT, 22 classes, guarded by ModelValidator)
          |
          v
  [5. Calibration & Outcome]  --> Temperature scaling (T=1.45), Shannon entropy -> ClassificationOutcome
```

### The "No False Claims" Containment Policy
Historically, CropDoc attempted to detect non-leaves using crude green-pixel chromaticity heuristics (`isLeafOrLesionPixel`) and unverified out-of-distribution logit overrides. As proven in Phase 0 reproduction tests, this accepted green cotton shirts (RGB 50, 130, 45) and diagnosed them as `tomato_healthy` with >85% confidence.

In this repair:
1. **The default gate (`DefaultLeafGate.java`) performs ONLY verified image-quality sanity checks** (extreme underexposure < 15 lux, extreme overexposure > 245, minimum dimensions).
2. **Semantic non-leaf discrimination is explicitly declared `NOT VALIDATED — PLUG-IN READY`**.
3. It **refuses to synthesize fake heuristic rejections** that give farmers false confidence.
4. The classifier's unverified `background_not_a_leaf` logit is excluded from user-facing rejection logic until an independently trained model is plugged in.

---

## 2. Phase 2 Semantic LeafGate Architectural Design

### Candidate Approaches Evaluated

| Architecture | Description | Pros | Cons | Recommendation |
| :--- | :--- | :--- | :--- | :--- |
| **Option A: Dedicated Binary Classifier** | Lightweight MobileNetV3-Small / SqueezeNet binary model (`leaf` vs `non_leaf`) | Clean separation of concerns; trained on massive negative distribution | Adds ~800 KB to APK; adds separate inference pass | **RECOMMENDED (Primary)** |
| **Option B: One-Class / OOD Density Estimator** | Mahalanobis distance / Energy score on penultimate feature embeddings of disease classifier | Zero extra model weights; single forward pass | Sensitive to domain shift; difficult to calibrate across phone sensors | Secondary (Research) |
| **Option C: Hybrid (Binary Gate + Quality Heuristic)** | Stage 1 cheap luminance/blur filter $\rightarrow$ Stage 2 lightweight binary gate $\rightarrow$ Disease classifier | Drops invalid frames in < 2 ms without invoking neural network | Requires dual threshold tuning | **RECOMMENDED (Target)** |

### Performance Budget (Low-End Target Phone)
- **Model Size:** $\le 1.0\text{ MB}$ (INT8 quantized flatbuffer).
- **Inference Latency:** $\le 15.0\text{ ms}$ on 4-thread CPU via LiteRT XNNPACK delegate.
- **Peak Memory:** $\le 500\text{ KB}$ runtime RAM allocation.

---

## 3. Negative Dataset Requirements for Gate Training

To train the Phase 2 binary classifier, a dedicated negative distribution ($N \ge 10,000$ images) must be gathered across Indian agricultural contexts:

| Category | Real-World Failure Scenario | Examples to Gather | Required Image Count |
| :--- | :--- | :--- | :--- |
| **Human Skin & Faces** | Farmer holding phone or pointing finger at plant | Hands holding stems, skin tones under sun, faces | 2,000 |
| **Clothing & Fabrics** | Farmer wearing green or printed clothing behind leaf | Green cotton shirts, floral sarees, blue denim, towels | 2,500 |
| **Soil & Agricultural Tools** | Dropping phone, scanning ground, framing spade | Red laterite soil, black cotton soil, spades, hoses | 2,000 |
| **Non-Crop Foliage & Weeds** | Scanning uncultivated roadside weeds | Parthenium hysterophorus, bermuda grass, tree bark | 2,000 |
| **Indoor & Artificial Scenes** | Testing app indoors, on paper or screens | Green walls, linoleum tiles, printed photos, monitors | 1,500 |

---

## 4. Negative Test Suite Catalog (Acceptance Benchmark)

Before the Phase 2 semantic gate may be enabled in production, it must achieve **$\ge 99.0\%$ True Negative Rate** across the following benchmark suite:

```text
neg_suite_01_green_cotton_shirt.jpg    --> MUST REJECT (NON_LEAF_DETECTED)
neg_suite_02_farmer_hand_skin.jpg       --> MUST REJECT (NON_LEAF_DETECTED)
neg_suite_03_soil_and_spade.jpg         --> MUST REJECT (NON_LEAF_DETECTED)
neg_suite_04_parthenium_weed.jpg        --> MUST REJECT (NON_LEAF_DETECTED)
neg_suite_05_concrete_floor.jpg         --> MUST REJECT (NON_LEAF_DETECTED)
neg_suite_06_pitch_black_pocket.jpg     --> MUST REJECT (UNDER_EXPOSED)
neg_suite_07_sun_glare_washout.jpg      --> MUST REJECT (OVER_EXPOSED)
neg_suite_08_out_of_focus_blur.jpg      --> MUST REJECT (BLURRY)
```
