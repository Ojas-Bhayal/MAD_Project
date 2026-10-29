# CropDoc Group-Aware Evaluation Protocol & Data Split Specification

**Document:** `docs/ml-repair/EVALUATION_PROTOCOL.md`  
**Authors:** ML Engineering & Agronomy Quality Team  
**Scope:** Strict group-aware partitioning, data leakage prevention, and model evaluation standards for CropDoc on-device LiteRT models.

---

## 1. Problem Statement: The Hidden Data Leakage Risk

In plant pathology image classification, multiple photographs are often captured of:
1. The **same lesion or leaf blade** under different camera angles, zooms, and lighting conditions.
2. The **same individual plant** exhibiting systemic foliage symptoms.
3. The **same agricultural field / plot** sharing identical soil composition, background weed species, and atmospheric conditions.

### Random Split Failure Scenario
If a naive random split (e.g. standard 80/10/10 train/val/test) is used:
- Cropped views of the exact same leaf blade appear simultaneously in both `train` and `test`.
- The neural network memorizes background soil texture, specific vein patterns, and lighting quirks rather than pathogenic foliar symptoms.
- Validation accuracy reports a synthetic 98%, but field accuracy on Indian smallholder farms collapses to < 50%.

---

## 2. Mandatory Invariant: Group-Aware Partitioning

> [!IMPORTANT]
> **Cardinal Invariant:** All photographs, crops, augmentations, and re-scans belonging to the same plant, leaf, or field collection group **MUST** be assigned to exactly one split (`train`, `val`, OR `test`). Under no circumstance may members of the same cluster straddle split boundaries.

```text
[Field Collection Session]
          |
   (Group ID: v2_telangana_rice_plot04)
   [Photo A1]  [Photo A2]  [Crop A3]
          \        |        /
           v       v       v
         +-------------------+
         | Assigned to TEST  |  <--- 100% of cluster stays in TEST
         +-------------------+
                  |
        Zero images in TRAIN
```

### Partitioning Rules
1. **Per-Plant / Per-Field Group IDs:** Every raw image must be tagged at acquisition with a deterministic `group_id` (`{source}_{location}_{plot_id}_{plant_id}`).
2. **Perceptual Hash Clustering:** For unannotated datasets (e.g., PlantDoc, Mendeley), 64-bit radial perceptual hashing (`pHash`) with multi-index 8-bit chunking is executed within each class (`class_name`). Images with Hamming distance $\le 6$ are unioned into a single connected component cluster.
3. **Cluster-Preserving Split Assignment:**
   - **Train:** ~70% of independent plant clusters.
   - **Validation:** ~15% of independent plant clusters.
   - **Test (Sacred Benchmark):** ~15% of independent plant clusters (never seen during hyperparameter tuning or architecture selection).

---

## 3. Class Balance and Geographic Stratification

Each split must preserve representative proportions across:
- **Taxonomy:** All 22 supported crop disease / healthy classes.
- **Illumination Condition:** Natural daylight (overcast, harsh direct noon sun, dusk) and camera flash.
- **Foliar Maturity:** Early onset lesions, moderate defoliation, and late necrotic blight.
- **Hardware Diversity:** Samples collected across low-end budget sensors (3–4 GB RAM target phones: MediaTek Helio G35, Snapdragon 680, Unisoc T606).

---

## 4. Evaluation Metrics & Pass/Fail Acceptance Gates

All production candidate models must be evaluated using `ml/eval/evaluate_model.py` and pass the following acceptance gates:

| Metric | Target Threshold | Rationale |
| :--- | :--- | :--- |
| **Top-1 Accuracy** | $\ge 85.0\%$ | High confidence diagnosis required before presenting advice |
| **Top-3 Accuracy** | $\ge 95.0\%$ | Top candidate list must encompass true disease for low-confidence fallback |
| **Macro F1-Score** | $\ge 0.8000$ | Prevents minority classes (e.g. `groundnut_early_leaf_spot`) from being drowned out |
| **Expected Calibration Error (ECE)** | $\le 0.1000$ | Softmax probabilities must reliably reflect empirical accuracy |
| **P95 Latency (Budget Hardware)** | $\le 80.0\text{ ms}$ | Inference must not freeze CameraX UI or cause thermal throttling |
| **Flatbuffer Model Size** | $\le 4.0\text{ MB}$ | Fits comfortably within memory budget on 3 GB RAM devices |

---

## 5. Script Verification & Deduplication Fix

In `ml/scripts/deduplicate_groups.py`, the bucket key was corrected from the nonexistent column `class_id` to `class_name` (`rows[idx].get("class_name") or rows[idx].get("class_id") or ""`). This ensures clustering is performed strictly within individual pathology classes, preventing unrelated crops from being merged into degenerate clusters.
