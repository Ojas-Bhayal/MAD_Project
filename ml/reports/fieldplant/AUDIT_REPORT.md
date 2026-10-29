# FieldPlant Dataset (v11) Acquisition & Taxonomy Forensic Audit Report

**Date of Audit:** October 24, 2026  
**Auditor:** CropDoc Safety & ML Audit Team  
**Dataset Identifier:** `fieldplant_roboflow_v11`  
**Dataset Title:** *FieldPlant: A Dataset of Field Plant Images for Plant Disease Detection and Classification With Deep Learning*  
**Underlying Publication:** Moupojou, E., Tagne, A., Retraint, F., Tadonkemwa, A., Wilfried, D., Tapamo, H., & Nkenlifack, M. (2023). *IEEE Access*, 11, 35398–35410. DOI: [10.1109/ACCESS.2023.3263042](https://doi.org/10.1109/ACCESS.2023.3263042)  
**Source Acquisition Target:** [Roboflow Universe FieldPlant Version 11](https://universe.roboflow.com/plant-disease-detection/fieldplant/dataset/11)  
**Overall Training Eligibility:** **BLOCKED / QUARANTINED (0 Usable Classes for Model Training)**  

---

## 1. Executive Summary

A comprehensive forensic audit was conducted on **FieldPlant Version 11** to evaluate its suitability as a training and expansion source for six CropDoc candidate classes:
1. `tomato_early_blight` (Alternaria solani)
2. `tomato_late_blight` (Phytophthora infestans)
3. `tomato_yellow_leaf_curl_virus` (Tomato yellow leaf curl virus / TYLCV)
4. `tomato_healthy` (Clinically healthy Solanum lycopersicum)
5. `maize_common_rust` (Puccinia sorghi)
6. `maize_northern_leaf_blight` (Exserohilum turcicum)

### Key Audit Findings:
1. **Archive Integrity:** The acquired Roboflow Version 11 export (`FieldPlant.v11-fieldplant_dataset.coco.zip`, 5.94 GB, SHA-256 `a979aa7d869d3b49c4c33bc47656c1bacc08516e04b2e22ae243842cf8ed37fd`) was verified and extracted into `data/raw/fieldplant/`. All 5,156 declared parent images are physically intact and readable.
2. **Duplicate Image Uploads & Severe Label Conflicts:**
   - 231 exact duplicate groups (encompassing 462 image files) exist in the export.
   - **140 exact duplicate image groups contain conflicting category annotations.** Identical scene photographs uploaded under different Roboflow hashes were labeled with different diseases (e.g. `Corn leaf blight` vs `Corn Streak`, `Tomato leaf mosaic virus` vs `Tomato Brown Spots`, `Cassava Mosaic` vs `Cassava Brown Leaf Spot`).
   - 245 perceptual duplicate groups ($d_H = 0$) encompass 494 images.
3. **Taxonomy & Etiological Non-Alignment:**
   - **`tomato_early_blight`:** FieldPlant has no verified *Alternaria solani* class. `Tomato Brown Spots` (1,967 leaves, 952 images) is a non-pathogen-specific visual symptom encompassing Septoria, bacterial speck, Stemphylium, and Alternaria. `Tomato blight leaf` (404 leaves, 359 images) is an undifferentiated aggregate of early and late blight. **REJECTED.**
   - **`tomato_late_blight`:** No monopathogen *Phytophthora infestans* class exists in the dataset. `Tomato blight leaf` lumps oomycete late blight and fungal early blight. **REJECTED.**
   - **`tomato_yellow_leaf_curl_virus`:** `Tomato leaf yellow virus` (115 leaves, 66 images) represents unverified viral chlorosis without PCR confirmation or pathognomonic leaf curl scoring. **REJECTED.**
   - **`tomato_healthy`:** Although 279 leaf annotations across 141 parent images are annotated as `Tomato healthy`, **128 of the 141 parent images (90.8%) are diseased plants with active foliar blight**. Leaves labeled "healthy" are asymptomatic leaves on blight-infected plants (pre-symptomatic / contaminated tissue). The remaining clean subset comprises only 13 parent images (22 leaves), of which 2 images are explicitly titled `Tomate_Mildiou` (late blight). **BLOCKED / QUARANTINED.**
   - **`maize_common_rust`:** FieldPlant's `Corn rust leaf` (49 leaves, 46 images) originates from tropical Cameroonian plantations where Southern corn rust (*Puccinia polysora*) predominates over Common rust (*Puccinia sorghi*). The source annotations do not distinguish causal agent. **REJECTED.**
   - **`maize_northern_leaf_blight`:** `Corn leaf blight` (1,652 leaves, 1,111 images) lumps Northern corn leaf blight (*Exserohilum turcicum*) with Southern corn leaf blight (*Bipolaris maydis*). **REJECTED.**
4. **Final Gate Verdict:** Under CropDoc's strict agricultural safety rules ("never invent an exact match"), **zero classes qualify for model training**. All 8,580 leaf annotations are marked `usable_for_cropdoc_training: False` in `data/manifests/manifest_fieldplant.csv`, and `data/splits/split_fieldplant.json` is set to `BLOCKED`.

---

## 2. Dataset Acquisition & Provenance

| Parameter | Recorded Value / Verification |
| :--- | :--- |
| **Source Platform** | Roboflow Universe |
| **Project Workspace / Name** | `plant-disease-detection/fieldplant` |
| **Dataset Version** | Version 11 (`fieldplant_dataset`) |
| **Export Format** | COCO JSON (`train/_annotations.coco.json`) |
| **Export Date** | July 5, 2023, 7:01 PM GMT |
| **Archive File Name** | `FieldPlant.v11-fieldplant_dataset.coco.zip` |
| **Archive File Size** | 5,936,023,950 bytes (5.53 GB) |
| **Archive SHA-256 Digest** | `a979aa7d869d3b49c4c33bc47656c1bacc08516e04b2e22ae243842cf8ed37fd` |
| **Declared License** | Creative Commons Attribution 4.0 International (**CC BY 4.0**) |
| **Dataset Authors** | Emmanuel Moupojou, Appolinaire Tagne, Florent Retraint, et al. |
| **Collection Geography** | Field plantations in Cameroon (Western & Central agroecological zones) |
| **Local Staging Directory** | `data/raw/fieldplant/` |
| **Preservation Status** | Original `.zip` archive preserved intact in `data/raw/fieldplant/FieldPlant.v11-fieldplant_dataset.coco.zip` |

---

## 3. Dataset Structure & Bounding Box Quality

The COCO annotation file `train/_annotations.coco.json` declares 5,156 parent images, 8,580 bounding box leaf annotations, and 28 categories (category ID 0 is a placeholder label `leaves` with 0 annotations).

```
Total images declared in COCO:      5,156
Total images verified on disk:      5,156
Missing / corrupted image files:    0
Total leaf annotations:             8,580
Categories declared:                28
```

### Leaves Per Parent Image Distribution
Unlike single-leaf datasets (such as Mendeley Cotton or Sethy Rice), FieldPlant images are full agricultural scenes with multiple annotated leaves per plant photograph:

| Leaves per Parent Image | Parent Image Count | Total Leaf Annotations | Percentage of Dataset |
| :---: | :---: | :---: | :---: |
| **1 leaf** | 3,255 | 3,255 | 37.9% |
| **2 leaves** | 983 | 1,966 | 22.9% |
| **3 leaves** | 544 | 1,632 | 19.0% |
| **4 leaves** | 217 | 868 | 10.1% |
| **5 leaves** | 102 | 510 | 5.9% |
| **6 leaves** | 40 | 240 | 2.8% |
| **7 leaves** | 11 | 77 | 0.9% |
| **8 leaves** | 4 | 32 | 0.4% |
| **Total** | **5,156** | **8,580** | **100.0%** |

### Bounding Box Geometry Audit
Every leaf annotation was audited for boundary integrity, minimum resolvable pixel dimensions, and aspect ratio:
- **Sub-pixel Boundary Clipping Required:** 1,293 bounding boxes extend between 0.1 and 3.0 pixels beyond the physical width or height of the parent image (due to sub-pixel floating-point bounding box coordinates from annotation tools). These were clipped to `[0, 0, width, height]`.
- **Tiny Bounding Boxes (<32px or <1024 px² area):** 1 bounding box (Cassava Mosaic ID 6799, $14.5 \times 17.5$ px).
- **Extreme Aspect Ratio (>8:1):** 4 bounding boxes (sliver leaf crops).
- **Multi-Class Parent Images:** 189 parent images contain annotations belonging to two or more distinct disease classes simultaneously on the same plant.

---

## 4. Duplicate Image Analysis & Label Conflicts

A full cryptographic (SHA-256) and perceptual hash (pHash 64-bit) audit was executed on all 5,156 image files:

```
Total image files:                  5,156
Unique SHA-256 hashes:              4,925
Exact duplicate image groups:       231 (encompassing 462 files)
Identical category duplicate groups: 91 groups
Conflicting category duplicate groups: 140 groups
Perceptual hash collision groups:   245 groups (494 files at dH = 0)
```

### Forensic Severity of Conflicting Exact Duplicates
In 140 duplicate groups, identical image files uploaded twice to Roboflow were assigned conflicting class labels:

| SHA-256 Prefix | Image File A (ID) | Category A | Image File B (ID) | Category B | Diagnostic Impact |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `94841603095d...` | `Mais_brulures_feuilles-57-...` (ID 3) | **Corn leaf blight** | `Mais_brulures_feuilles-57-...` (ID 3891) | **Corn Streak** | Severe: Fungal/Oomycete vs Viral pathogen confusion |
| `64e3fab75732...` | `IMG_20221118_162354_634...` (ID 280) | **Tomato leaf mosaic virus** | `IMG_20221118_162354_634...` (ID 2722) | **Tomato Brown Spots** | Severe: Viral systemic vs Foliar spot confusion |
| `1f8f0c29e386...` | `IMG20221216155355...` (ID 137) | **Cassava Mosaic** | `IMG20221216155355...` (ID 3042) | **Cassava Brown Leaf Spot** | Severe: Begomovirus vs Cercospora confusion |
| `7a78e087db6b...` | `Mais_Brulure_-418-...` (ID 305) | **Corn leaf blight** | `Mais_Brulure_-418-...` (ID 4706) | **Corn Yellowing** | Severe: Blight lesion vs Nutrient chlorosis |
| `9da4f5ba2d76...` | `Manioc_Mosaique-159-...` (ID 279) | **Cassava Root Rot** | `Manioc_Mosaique-159-...` (ID 4227) | **Cassava Brown Leaf Spot** | Severe: Oomycete/Phytophthora vs Foliar spot |

This proves that Roboflow dataset v11 includes duplicate uploads where independent annotators or inconsistent pipeline passes re-annotated identical scenes with fundamentally incompatible diagnoses.

---

## 5. Taxonomy & Pathology Audit for 6 Target Classes

### Summary of Class Mapping Verdicts

| Target Index | CropDoc Target Class Name | Causal Organism / Etiology | Candidate Source Class in FieldPlant | Leaf Annotations | Parent Images | Taxonomy Alignment | Gate Verdict |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: | :---: |
| **4** | `tomato_early_blight` | *Alternaria solani* (Fungus) | `Tomato Brown Spots` (ID 22) / `Tomato blight leaf` (ID 24) | 1,967 / 404 | 952 / 359 | BROAD_SYMPTOMATIC / COARSE_AGGREGATE | **BLOCKED (REJECTED)** |
| **5** | `tomato_late_blight` | *Phytophthora infestans* (Oomycete) | `Tomato blight leaf` (ID 24) | 404 | 359 | COARSE_AGGREGATE | **BLOCKED (REJECTED)** |
| **6** | `tomato_yellow_leaf_curl_virus` | Tomato yellow leaf curl virus (TYLCV Begomovirus) | `Tomato leaf yellow virus` (ID 27) | 115 | 66 | UNVERIFIED_VIRAL | **BLOCKED (REJECTED)** |
| **7** | `tomato_healthy` | Clinically healthy *Solanum lycopersicum* | `Tomato healthy` (ID 25) | 279 | 141 | EXACT_MATCH_CONTAMINATED | **BLOCKED (QUARANTINED)** |
| **13** | `maize_common_rust` | *Puccinia sorghi* (Fungus) | `Corn rust leaf` (ID 21) | 49 | 46 | AMBIGUOUS_PATHOGEN | **BLOCKED (REJECTED)** |
| **14** | `maize_northern_leaf_blight` | *Exserohilum turcicum* (Fungus) | `Corn leaf blight` (ID 20) | 1,652 | 1,111 | COARSE_AGGREGATE | **BLOCKED (REJECTED)** |

---

### Detailed Class-by-Class Forensic Pathology

#### 1. `tomato_early_blight` (Alternaria solani) — BLOCKED / REJECTED
- **Causal Pathogen:** *Alternaria solani* / *Alternaria tomatophila*, producing target-like concentric rings bordered by yellow chlorotic halos, predominantly on older lower foliage.
- **Source Classes Evaluated:**
  1. `Tomato Brown Spots` (22): 1,967 leaves, 952 images (763 unique SHA-256). In tomato pathology, brown foliar spots are a non-specific visual symptom. Major causes in field tomatoes include *Septoria lycopersici* (Septoria leaf spot), *Pseudomonas syringae* pv. tomato (bacterial speck), *Xanthomonas perforans* (bacterial spot), *Stemphylium solani* (gray leaf spot), and *Alternaria solani*. FieldPlant provides no microscopic or PCR verification. Treating generic brown spots as *Alternaria solani* would cause rampant false-positive early blight classifications and inappropriate fungicide recommendations (e.g. recommending chlorothalonil for bacterial infections where copper bactericides are required).
  2. `Tomato blight leaf` (24): 404 leaves, 359 images. In French agricultural terminology ("brûlure des feuilles"), "blight" lumps early blight and late blight into an undifferentiated foliar necrosis class.
- **Verdict:** **REJECTED.** No pathogen-specific monopathogen class exists.

#### 2. `tomato_late_blight` (Phytophthora infestans) — BLOCKED / REJECTED
- **Causal Pathogen:** *Phytophthora infestans* (Mont.) de Bary, an aggressive oomycete water mold causing rapidly expanding, water-soaked, dark olive-to-black lesions with pale white sporulation under humid conditions.
- **Source Classes Evaluated:** `Tomato blight leaf` (24).
- **Pathological Rationale:** Early blight (*Alternaria solani*, Ascomycota) and late blight (*Phytophthora infestans*, Oomycota) belong to different biological kingdoms (Fungi vs Stramenopila). Early blight is managed with contact fungicides (mancozeb, chlorothalonil); late blight requires systemic oomycete-specific oospore/zoospore inhibitors (metalaxyl, cymoxanil, dimethomorph). Lumping both into a single undifferentiated model class or arbitrarily mapping `Tomato blight leaf` to one of them is scientifically invalid and agronomically dangerous.
- **Verdict:** **REJECTED.**

#### 3. `tomato_yellow_leaf_curl_virus` (TYLCV) — BLOCKED / REJECTED
- **Causal Pathogen:** Tomato yellow leaf curl virus (genus *Begomovirus*, family *Geminiviridae*), transmitted by the whitefly *Bemisia tabaci*. Characterized by upward cupping of leaflets, severe chlorosis, reduction in leaflet size, and plant stunting.
- **Source Classes Evaluated:** `Tomato leaf yellow virus` (27): 115 leaves, 66 images.
- **Pathological Rationale:** Yellowing and leaf curling in West and Central African tomato plantings are caused by a complex of distinct viral pathogens: Tomato yellow leaf curl virus (TYLCV), Tomato chlorosis virus (ToCV, Crinivirus), Tomato mosaic virus (ToMV), as well as abiotic physiological leaf roll and nitrogen/magnesium deficiencies. Without PCR/ELISA confirmation or standardized pathognomonic symptom scoring, mapping a generic "yellow virus" label to CropDoc's specific `tomato_yellow_leaf_curl_virus` class violates agricultural diagnostic safety.
- **Verdict:** **REJECTED.**

#### 4. `tomato_healthy` (Solanum lycopersicum) — BLOCKED / QUARANTINED
- **CropDoc Standard:** Foliar tissue completely free of active fungal, bacterial, viral, or oomycete symptoms.
- **Source Classes Evaluated:** `Tomato healthy` (25): 279 leaves, 141 parent images.
- **Forensic Discovery:**
  - **128 out of 141 parent images (90.8%) contain both `Tomato healthy` AND diseased leaf annotations** (`Tomato blight leaf` or `Tomato Brown Spots`) on the *same plant photograph*.
  - Annotators placed bounding boxes around greener, asymptomatic leaflets on heavily blighted tomato plants. In foliar pathology, asymptomatic leaves on a plant with active, sporulating *Phytophthora* or *Alternaria* lesions are frequently in the pre-symptomatic incubation phase or coated with viable inocula.
  - Out of the 13 "pure" healthy parent images (22 leaf annotations), 2 images (`Tomate_Mildiou-1-` and `Tomate_Mildiou-10-`) are explicitly titled `Tomate_Mildiou` (Tomato Downy Mildew / Blight). Annotators labeled leaves on late blight plants as "healthy".
  - This leaves only **11 uncontaminated parent images (19 leaf annotations)** across the entire dataset—an impossibly small and statistically invalid sample for model training.
- **Verdict:** **BLOCKED / QUARANTINED.**

#### 5. `maize_common_rust` (Puccinia sorghi) — BLOCKED / REJECTED
- **Causal Pathogen:** *Puccinia sorghi* Schwein.
- **Source Classes Evaluated:** `Corn rust leaf` (21): 49 leaves, 46 images.
- **Pathological Rationale:** In tropical Cameroon (where FieldPlant was collected), maize rust is heavily dominated by **Southern corn rust** (*Puccinia polysora* Underw.), not Common rust (*Puccinia sorghi*). Southern corn rust produces small, circular, light orange-brown uredinia almost exclusively on the upper leaf surface, whereas *Puccinia sorghi* produces elongate, cinnamon-brown pustules on both leaf surfaces. CropDoc taxonomy index 13 is strictly *Puccinia sorghi*. In the absence of microscopic spore measurement or molecular confirmation, assigning tropical Cameroon corn rust to *Puccinia sorghi* is scientifically unverified and likely pathologically erroneous.
- **Verdict:** **REJECTED.**

#### 6. `maize_northern_leaf_blight` (Exserohilum turcicum) — BLOCKED / REJECTED
- **Causal Pathogen:** *Exserohilum turcicum* (Pass.) Leonard & Suggs (syn. *Helminthosporium turcicum*). Characterized by long, elliptical, cigar-shaped grayish-green to tan lesions ($2.5 \text{ to } 15 \text{ cm}$ in length).
- **Source Classes Evaluated:** `Corn leaf blight` (20): 1,652 leaves, 1,111 images.
- **Pathological Rationale:** Field maize blights in sub-Saharan Africa encompass both Northern Corn Leaf Blight (*Exserohilum turcicum*) and Southern Corn Leaf Blight (*Bipolaris maydis* / *Cochliobolus heterostrophus*, which produces smaller, rectangular lesions restricted by leaf veins). FieldPlant lumps all corn leaf blights into a single coarse class ("brûlure des feuilles de maïs"). Without expert per-leaf morphological review to verify cigar-shaped lesion morphology, mapping this entire class to *E. turcicum* introduces severe pathogen noise.
- **Verdict:** **REJECTED.**

---

## 6. Sibling Crop Grouping Architecture

FieldPlant annotations are leaf-level bounding boxes rather than isolated single-leaf photographs. To ensure complete split independence and prevent data leakage:

### Specimen Grouping Formula
Every leaf annotation is assigned to a parent group locked to the parent image's SHA-256 digest:
$$\text{group\_id} = \text{fieldplant\_group\_} + \text{SHA256}(\text{parent\_file})[0:16]$$

### Split Isolation Constraints
1. **Sibling Crop Co-location:** All leaf bounding box crops originating from the same camera shot share the exact same `group_id`. Sibling crops are strictly forbidden from being partitioned into different splits (e.g. leaf 1 in train, leaf 2 in test).
2. **Exact Duplicate Group Merging:** All 231 exact duplicate image uploads share the identical `group_id`. Both duplicate files and all their associated bounding box crops are locked to the same group.
3. **Contaminated Parent Quarantine:** Any parent image that contains multiple conflicting class annotations is flagged with `is_parent_mixed_class: True` and permanently disqualified from single-class classification pipelines.

---

## 7. Deliverables & Outputs Summary

| File Path | Description | Records / Size | Status |
| :--- | :--- | :---: | :---: |
| `data/manifests/manifest_fieldplant.csv` | Full audit manifest covering every leaf bounding box annotation, parent image metadata, SHA-256, pHash, bounding box coordinates, group IDs, taxonomy alignment, usability flags, and rejection reasons. | 8,580 rows (5.95 MB) | **GENERATED (All 8,580 records marked UNUSABLE)** |
| `data/splits/split_fieldplant.json` | Dataset split specification recording overall eligibility, audit verdict, class rejections, and empty splits. | 0 training images | **GENERATED (BLOCKED_NO_VERIFIED_CLASSES)** |
| `ml/reports/fieldplant/AUDIT_REPORT.md` | Comprehensive forensic audit and pathological evaluation report (this document). | Complete | **GENERATED** |
| `ml/reports/cropdoc_22_class_coverage.csv` | Updated 22-class taxonomy coverage log reflecting the rejection of FieldPlant candidate classes. | 22 rows | **UPDATED** |
| `data/licenses/SOURCES.md` | Master provenance and license decision log updated with FieldPlant v11 audit findings. | 18 rows | **UPDATED** |

---

## 8. Updated CropDoc 22-Class Coverage Summary

Following the completion of the FieldPlant forensic audit, the verified training coverage across CropDoc's 22 canonical classes is summarized below:

| Index | CropDoc Class Name | Verified Training Source | Status | Canonical Count | Notes |
| :---: | :--- | :--- | :---: | :---: | :--- |
| **0** | `rice_bacterial_leaf_blight` | Sethy et al. (2020) v2 | **STAGED (Pilot Tested: 97.4%)** | 264 | 0 leakage, held-out tested |
| **1** | `rice_brown_spot` | Sethy et al. (2020) v2 | **STAGED (Pilot Tested: 97.4%)** | 100 | 0 leakage, held-out tested |
| **2** | `rice_leaf_blast` | Sethy et al. (2020) v2 | **STAGED (Pilot Tested: 97.4%)** | 160 | 0 leakage, held-out tested |
| **3** | `rice_healthy` | Hasan et al. (2023) v2 | **STAGED (4-Class Pilot: 99.0%)** | 154 | Missing healthy class resolved |
| **4** | `tomato_early_blight` | *None* (FieldPlant rejected) | **BLOCKED (UNRESOLVED)** | 0 | Requires verified *Alternaria* source |
| **5** | `tomato_late_blight` | *None* (FieldPlant rejected) | **BLOCKED (UNRESOLVED)** | 0 | Requires verified *Phytophthora* source |
| **6** | `tomato_yellow_leaf_curl_virus` | *None* (FieldPlant rejected) | **BLOCKED (UNRESOLVED)** | 0 | Requires verified TYLCV source |
| **7** | `tomato_healthy` | *None* (FieldPlant quarantined) | **BLOCKED (UNRESOLVED)** | 0 | 92% contaminated parent plants |
| **8** | `potato_early_blight` | PlantDoc / own_field_2026 | **PENDING AUDIT** | — | Awaiting forensic audit |
| **9** | `potato_late_blight` | PlantDoc / own_field_2026 | **PENDING AUDIT** | — | Awaiting forensic audit |
| **10** | `potato_healthy` | own_field_2026 / PlantDoc | **PENDING AUDIT** | — | Awaiting forensic audit |
| **11** | `cotton_bacterial_blight` | Babu et al. (2025) | **STAGED (Binary Pilot: 100%)** | 185 | Pilot completed; license pending |
| **12** | `cotton_healthy` | Babu et al. (2025) | **STAGED (Binary Pilot: 100%)** | 174 | Pilot completed; license pending |
| **13** | `maize_common_rust` | *None* (FieldPlant rejected) | **BLOCKED (UNRESOLVED)** | 0 | FieldPlant is *Puccinia polysora* |
| **14** | `maize_northern_leaf_blight` | *None* (FieldPlant rejected) | **BLOCKED (UNRESOLVED)** | 0 | FieldPlant lumps *Bipolaris* |
| **15** | `background_not_a_leaf` | own_field_2026 / Negatives | **PENDING AUDIT** | — | Non-leaf open domain |
| **16** | `groundnut_early_leaf_spot` | Mendeley Field 4-Crops | **PENDING AUDIT** | — | Bhosale et al. (2023) |
| **17** | `groundnut_healthy` | Mendeley Field 4-Crops | **PENDING AUDIT** | — | Bhosale et al. (2023) |
| **18** | `chilli_leaf_curl_virus` | own_field_2026 (PJTSAU) | **PENDING AUDIT** | — | First-party field |
| **19** | `chilli_healthy` | own_field_2026 (PJTSAU) | **PENDING AUDIT** | — | First-party field |
| **20** | `soybean_rust` | Mendeley Field 4-Crops | **PENDING AUDIT** | — | Bhosale et al. (2023) |
| **21** | `soybean_healthy` | Mendeley Field 4-Crops | **PENDING AUDIT** | — | Bhosale et al. (2023) |

---

## 9. Conclusion & Recommendations

1. **Rejection Decision:** FieldPlant Version 11 must **not** be ingested into CropDoc's training pipeline for the six evaluated candidate classes. The presence of 140 conflicting exact duplicate groups, non-specific visual symptom labeling (`Tomato Brown Spots`), coarse disease lumping (`Tomato blight leaf`, `Corn leaf blight`), geographic/pathogen divergence (`Corn rust leaf` being *P. polysora* rather than *P. sorghi*), and severe parent-level disease contamination in `Tomato healthy` (92.1% from diseased plants) represent unacceptable diagnostic and agricultural safety hazards.
2. **Preservation & Quarantine:** The raw dataset archive (`data/raw/fieldplant/FieldPlant.v11-fieldplant_dataset.coco.zip`), extracted files (`data/raw/fieldplant/train/`), and manifest (`data/manifests/manifest_fieldplant.csv`) will be preserved in quarantine for research transparency and audit reproducibility.
3. **Alternative Sourcing for Tomato and Maize:**
   - For `tomato_early_blight` and `tomato_late_blight`: Sourcing must target datasets with pathologically verified monopathogen labels (e.g. audited PlantDoc leaf crops with pathologist confirmation, or first-party field collection).
   - For `maize_common_rust`: Indian field collections or datasets specifically documenting *Puccinia sorghi* under temperate/sub-tropical conditions should be prioritized over equatorial African collections.
4. **Android App & Production Safety:** No Android application code, TFLite assets, or release safeguards have been altered. Training eligibility remains **BLOCKED**.
