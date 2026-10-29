# CropDoc Model Card — `cropdoc_leaf_v2_int8.tflite` (v2.0.0-int8)

- **Model Version:** `2.0.0-int8` (Additive expansion over frozen `1.0.0-int8`)
- **SHA-256 Digest:** `e409b6f4c470996fa309a4c181161401da5db43a833f0f6aecb12d61ded44f34`
- **Backbone:** `MobileNetV3-Large` (`224x224x3 UINT8` input, `1x22 FLOAT32` calibrated logits, full-integer `INT8` XNNPACK)
- **Class Count:** `22` (`0..15` preserved identically from `v1`; `16..21` appended for Groundnut, Chilli, and Soybean)
- **Approved Data Sources (`data/licenses/SOURCES.md`):**
  - `plantvillage_v1` (CC0 1.0, background-replaced v1 baseline)
  - `plantdoc_v2` (CC BY 4.0, IIT Gandhinagar field/mixed dataset)
  - `fieldplant_v2` (CC BY 4.0, pathologist-verified field dataset)
  - `mendeley_field_4crops` (CC BY 4.0, Groundnut & Soybean field dataset)
  - `mendeley_cotton_2025` (CC BY 4.0, unaugmented field originals only)
  - `own_field_2026` (First-party Telangana/PJTSAU field collection, GPS EXIF stripped, expert double-labelled $\kappa = 0.91$)
  - *Excluded NC/ND sources:* `PlantWild` (CC BY-NC-ND 4.0) and `PlantSeg` (CC BY-NC 4.0) were explicitly excluded.
- **Release Gate Summary (`reports/eval_v2.json`):**
  - **Frozen v1 Test Set Top-1 Accuracy:** `86.48%` (`+0.39%` vs. v1 baseline `86.09%`, `0` class regressions)
  - **Expanded v2 Field Test Set Top-1 Accuracy (22 classes):** `87.35%` (Macro-F1 `0.871`, ECE `0.026`)
  - **Mid-Range Phone Latency (4-thread XNNPACK):** `39.4 ms` (well within `<= 60 ms` Phase 2 budget)
