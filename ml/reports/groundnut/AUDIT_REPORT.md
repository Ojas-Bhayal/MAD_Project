# Mendeley Groundnut Leaf Image Dataset (v3) Audit Report

**Date of Audit:** October 24, 2026  
**Auditor:** CropDoc Safety & ML Audit Team  
**Dataset Identifier:** `mendeley_groundnut_v3`  
**Dataset Title:** *Dataset of groundnut plant leaf images for classification and detection*  
**Authors:** Aishwarya M.P., A. Padmanabha Reddy (Vijayanagara Sri Krishnadevaraya University, Bellary, Karnataka, India)  
**Publication:** *Data in Brief*, Volume 48 (2023), 109185. DOI: [10.1016/j.dib.2023.109185](https://doi.org/10.1016/j.dib.2023.109185)  
**Repository Source:** [Mendeley Data DOI: 10.17632/22p2vcbxfk.3](https://data.mendeley.com/datasets/22p2vcbxfk/3)  
**Declared Licence:** Creative Commons Attribution 4.0 International (**CC BY 4.0**) across both Mendeley Data and Elsevier Data in Brief  
**Overall Training Eligibility:** **APPROVED FOR GROUP-LOCKED RESEARCH PILOT (Production Release BLOCKED)**  

---

## 1. Executive Summary

A comprehensive forensic audit of the **Mendeley Groundnut Leaf Image Dataset (Version 3)** was conducted to evaluate its viability for two specific CropDoc classes:
- `groundnut_early_leaf_spot` (CropDoc index 16, causal pathogen: *Cercospora arachidicola*)
- `groundnut_healthy` (CropDoc index 17, clinically asymptomatic *Arachis hypogaea*)

### Key Audit Findings:
1. **Download & Archive Integrity:** The official archive (`Groundnut_Leaf_dataset.zip`, 117.03 MB, SHA-256: `b15a69b3ce431ae239e96d94fd2d68b02f6a4fb95e70c7c419c8860e42b387c3`) was verified and extracted into `data/raw/mendeley_groundnut/`. All 10,361 declared image files were verified with 0 corrupted files.
2. **Taxonomy & Etiological Alignment:**
   - `early_leaf_spot_1` specifically matches *Cercospora arachidicola* (early leaf spot, distinguished by dark brown to black circular lesions surrounded by prominent yellow chlorotic halos).
   - Late leaf spot (*Cercosporidium personatum*), rust (*Puccinia arachidis*), early rust, and nutritional deficiencies were explicitly segregated into separate folders by the authors and kept strictly isolated from this evaluation.
   - Taxonomy alignment is **EXACT_MATCH** for both target classes.
3. **Forensic Discovery of Publisher Augmentation & Split Leakage:**
   - 1,621 of 1,731 files in `early_leaf_spot_1` and 1,503 of 1,871 files in `healthy_leaf_1` are pre-augmented derivatives generated via rotation/scaling (prefixed with `dr_<specimen_id>_<aug_id>.jpg`).
   - The publisher's pre-packaged `test/` folder contained massive data leakage: **all 409 test images in `early_leaf_spot_1` were augmented derivatives of just 6 physical specimens present in the `train/` folder**. Evaluating on the publisher's split yields artificially inflated, invalid performance.
4. **Duplicate & Cross-Class Contamination Checks:**
   - 1 exact SHA-256 duplicate pair within `healthy_leaf_1` (`90.jpg` and `91_3.jpg`).
   - **8 exact SHA-256 duplicate images in `healthy_leaf_1` conflict with `nutrition_deficiency_1`** (`IMG_00686.jpg`, `IMG_0685.jpg`, `IMG_0686.jpg`, `IMG_0688.jpg`, `IMG_0963.jpg`, `IMG_0965.jpg`, `IMG_0966.jpg`, `IMG_0969.jpg`). These 8 images are permanently quarantined.
   - 0 overlap with existing CropDoc datasets (Cotton, Rice Sethy, Rice Hasan, FieldPlant).
5. **Group-Aware Leakage Remediated Split:**
   - Grouping all derivatives by physical specimen and perceptual similarity ($d_H \le 2$) resolved 193 specimen clusters in early leaf spot and 631 specimen clusters in healthy leaf.
   - Fixed seed (42) partitioning achieved a clean ~70/15/15 split: 2,517 train, 538 val, 539 test with **0 cross-split specimen or perceptual near-duplicate leakage**.
6. **Binary Pilot Experiment:**
   - MobileNetV3-Small achieved **97.59% accuracy** and **97.58% macro F1** on 539 held-out test images.
   - Early leaf spot sensitivity: **95.38%** (248/260); Healthy specificity: **99.64%** (278/279).

---

## 2. Dataset Provenance & Archive Details

| Attribute | Verified Value |
| :--- | :--- |
| **Dataset Title** | *Dataset of groundnut plant leaf images for classification and detection* |
| **Source Platform** | Mendeley Data (Version 3) |
| **Source URL** | https://data.mendeley.com/datasets/22p2vcbxfk/3 |
| **Data in Brief DOI** | [10.1016/j.dib.2023.109185](https://doi.org/10.1016/j.dib.2023.109185) |
| **Collection Location** | Koppal, Karnataka, India (Field plantations with pathologist verification) |
| **Archive File** | `Groundnut_Leaf_dataset.zip` |
| **Archive Size** | 117,033,315 bytes (111.61 MB) |
| **Archive SHA-256** | `b15a69b3ce431ae239e96d94fd2d68b02f6a4fb95e70c7c419c8860e42b387c3` |
| **Staging Path** | `data/raw/mendeley_groundnut/` |
| **Stated Licence** | **CC BY 4.0** (Open Access, commercial/derivative rights permitted) |
| **Image Resolution** | Uniform $256 \times 256$ pixels, RGB 8-bit JPEG |

---

## 3. Class Inventory & Target Scope

The raw dataset contains 10,361 digital photographs partitioned by the authors across six folders:

| Folder Name | Scientific Condition / Pathogen | Total Files | Target Evaluation Scope |
| :--- | :--- | :---: | :---: |
| `early_leaf_spot_1` | *Cercospora arachidicola* (Early Leaf Spot) | 1,731 | **INCLUDED (Target Class 16)** |
| `healthy_leaf_1` | Asymptomatic *Arachis hypogaea* | 1,871 | **INCLUDED (Target Class 17)** |
| `late_leaf_spot_1` | *Cercosporidium personatum* (Late Leaf Spot) | 1,896 | **EXCLUDED (Quarantined)** |
| `early_rust_1` | Early pustular *Puccinia arachidis* | 1,474 | **EXCLUDED (Quarantined)** |
| `rust_1` | Advanced *Puccinia arachidis* (Rust) | 1,724 | **EXCLUDED (Quarantined)** |
| `nutrition_deficiency_1` | Abiotic mineral chlorosis/stunting | 1,665 | **EXCLUDED (Quarantined)** |
| **Total** | | **10,361** | **Target Scope: 3,602 images** |

---

## 4. Forensic Investigation of Publisher Augmentation & Leakage

### Discovery of `dr_` Augmentation Multipliers
Inspection of image filenames revealed two distinct naming conventions:
1. **Original Captures (non-dr):** Standard camera filenames (`IMG_03469.jpg`, `152_1.jpg`, `153.jpg`).
2. **Augmented Derivatives (`dr_`):** Filenames following the format `dr_<specimen_id>_<aug_id>.jpg` (e.g. `dr_102_4553.jpg`, `dr_102_9944.jpg`).

| Target Class | Total Files | Original Captures | Augmented (`dr_`) Copies | Underlying Physical Specimens in `dr_` | Average Multiplier |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `early_leaf_spot_1` | 1,731 | 110 | 1,621 | 105 | $\sim 15.4\times$ |
| `healthy_leaf_1` | 1,871 | 368 | 1,503 | 383 | $\sim 3.9\times$ |

### Publisher Split Leakage Audit
When evaluating the publisher's pre-packaged `train/` and `test/` folders, catastrophic train-test leakage was discovered:
- **`early_leaf_spot_1`:** All 409 files in the publisher's `test/` folder were augmented derivatives of just 6 physical specimen groups (`dr_1`, `dr_2`, `dr_3`, `dr_4`, `dr_7`, and `IMG` captures), all of which were simultaneously present in the `train/` folder.
- **`healthy_leaf_1`:** 328 of 409 test files leaked across train and test partitions (e.g. `41.jpg` in test while `dr_41_4018.jpg` was in train).

**Conclusion:** The publisher's split cannot be used for valid evaluation. A strict group-locked re-partitioning is required.

---

## 5. Duplicate, Conflict & Overlap Analysis

1. **Exact Duplicates:**
   - 1 exact SHA-256 duplicate pair within `healthy_leaf_1`: `90.jpg` and `91_3.jpg` (SHA: `e84e717522240972...`). `90.jpg` was designated the canonical representative; `91_3.jpg` was marked as duplicate.
2. **Cross-Class Conflicting Labels:**
   - **8 exact SHA-256 matches** were discovered between `healthy_leaf_1` and `nutrition_deficiency_1`:
     - `IMG_00686.jpg`, `IMG_0685.jpg`, `IMG_0686.jpg`, `IMG_0688.jpg`, `IMG_0963.jpg`, `IMG_0965.jpg`, `IMG_0966.jpg`, `IMG_0969.jpg`
   - These 8 images represent ambiguous or mislabeled leaves (nutritional deficiency vs healthy). All 8 are flagged as `has_label_conflict: True`, assigned to split `quarantined_conflict`, and disqualified from training.
3. **Cross-Dataset Overlap:**
   - Zero cryptographic or perceptual hash overlap was found with existing CropDoc staged datasets (`mendeley_cotton_2025`, `rice_mendeley_sethy`, `rice_mendeley_healthy_candidate`, or `fieldplant`).

---

## 6. Group-Aware Split Specification

All original captures and augmented derivatives belonging to the same physical specimen (along with perceptual near-duplicates at $d_H \le 2$) were merged into unified specimen clusters using connected-components clustering:
- **`groundnut_early_leaf_spot`:** 193 independent clusters (1,731 images)
- **`groundnut_healthy`:** 631 independent clean clusters (1,863 clean images)

Using fixed random seed 42, clusters were partitioned to achieve a ~70/15/15 target:

| Split | `groundnut_early_leaf_spot` | `groundnut_healthy` | Total Images | Total Specimen Groups | Percentage |
| :--- | :---: | :---: | :---: | :---: | :---: |
| **Train** | 1,212 | 1,305 | 2,517 | **555** (128 ELS + 427 Healthy) | 70.0% |
| **Validation** | 259 | 279 | 538 | **114** (34 ELS + 80 Healthy) | 15.0% |
| **Test (Held-Out)** | 260 | 279 | 539 | **155** (31 ELS + 124 Healthy) | 15.0% |
| **Quarantined** | 0 | 8 | 8 | **8** (8 Healthy conflicts) | — |
| **Total Target** | **1,731** | **1,871** | **3,602** | **832** (193 ELS + 639 Healthy) | **100.0%** |

**Independence Verification:** Cryptographic check confirms **zero cross-split leakage at $d_H = 0$, $d_H = 1$, and $d_H = 2$**.
- Total clean canonical physical specimens: **824** (193 early leaf spot, 631 healthy).
- Full derivative test evaluation: **97.59%** (526/539 images).
- Pure canonical test evaluation (1 image per physical specimen group): **98.71%** (153/155 specimens; 30/31 early leaf spot, 123/124 healthy).

---

## 7. Binary Research Pilot Results

A transfer learning experiment was conducted using MobileNetV3-Small over 6 epochs on the group-locked split:

- **Held-Out Test Set:** 539 images (260 early leaf spot, 279 healthy)
- **Overall Accuracy:** **97.59%** (526 of 539 correct)
- **Macro F1-Score:** **97.58%**
- **Per-Class Metrics:**
  - `groundnut_early_leaf_spot`: Precision = **99.60%**, Recall = **95.38%**, F1 = **97.45%** (248 TP, 12 FN, 1 FP)
  - `groundnut_healthy`: Precision = **95.86%**, Recall = **99.64%**, F1 = **97.72%** (278 TP, 1 FN, 12 FP)
- **Generalization Gap:** Train accuracy (99.72%) vs Test accuracy (97.59%) shows a tight 2.13% gap, demonstrating strong generalization without physical specimen memorization.

---

## 8. Verified Deliverables

1. **Audit Manifest:** `data/manifests/manifest_groundnut.csv` (3,602 rows documenting path, SHA-256, pHash, group ID, duplicate status, usability, and split).
2. **Split Manifest:** `data/splits/split_groundnut.json` (Group-locked train/val/test file paths and per-class counts).
3. **Training Script:** `ml/scripts/train_groundnut_binary_pilot.py`
4. **Experiment Artifacts:** `ml/experiments/groundnut_binary_pilot/` (`best_model.pth`, `metrics.json`, `training_curves.png`, `confusion_matrix.png`, `test_predictions.csv`, `EVALUATION_REPORT.md`).
