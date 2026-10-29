# CropDoc — Project Research-Use Licensing & Attribution Register

**Document ID:** `CD-LIC-REG-2026-001`  
**Date of Verification:** October 25, 2026  
**Auditor / Data Governance Lead:** CropDoc Autonomous ML Safety & Quality Gate  
**Target Project Scope:** Non-commercial college major project, academic demonstration, internal machine learning model development, experimental testing, and academic defense/presentation.  
**Target Agricultural Taxonomy:**
- **Rice:** `rice_bacterial_leaf_blight`, `rice_brown_spot`, `rice_leaf_blast`, `rice_healthy`
- **Cotton:** `cotton_bacterial_blight`, `cotton_healthy`
- **Groundnut:** `groundnut_early_leaf_spot`, `groundnut_healthy`

---

## 1. Executive Summary & Status Overview

This register establishes the verified legal provenance, source licensing terms, attribution requirements, and operational restrictions for all photographic datasets utilized across the three target crops in CropDoc.

In strict compliance with governance rules:
- All licensing determinations are grounded in verified public repository evidence and published legal deeds.
- No permissions, bilateral waivers, author emails, or publisher authorizations are fabricated or presumed.
- Licensing clearance is strictly evaluated against the defined **non-commercial college major project research scope** (academic demonstration, model development, internal evaluation, and academic defense). Unrestricted commercial deployment or outside-scope distribution is **not** claimed or authorized.
- **Data licensing is tracked strictly separately from data-label quality:** licensing clearance establishes the legal right to use and adapt the image data; it does **not** substitute for plant-pathologist review or biological ground-truth validation.
- Model validation and release gates remain untouched and fail-closed.

### Crop-Level Status Summary

| Crop | Target Classes | Primary Source Datasets | Declared License(s) | College Project Research Scope Status | Production Model Release Status |
|:---|:---:|:---|:---|:---:|:---:|
| **Rice** (*Oryza sativa*) | 4 Classes (BLB, Brown Spot, Blast, Healthy) | `rice_mendeley_sethy_v2`<br/>`rice_mendeley_hasan_2023` | **CC BY 4.0** (Repository & Papers) | **VERIFIED FOR PROPOSED USE** | Gate Intact (Awaiting 8-class integration) |
| **Groundnut** (*Arachis hypogaea*) | 2 Classes (Early Leaf Spot, Healthy) | `mendeley_groundnut_v3` | **CC BY 4.0** (Repository & Paper) | **VERIFIED FOR PROPOSED USE** | Gate Intact (Awaiting 8-class integration) |
| **Cotton** (*Gossypium hirsutum*) | 2 Classes (Bacterial Blight, Healthy) | `mendeley_cotton_2025` | **Mendeley Data v1: CC BY 4.0**<br/>*Data in Brief* Article: CC BY-NC 4.0 | **VERIFIED FOR PROPOSED USE** | **BLOCKED** (Data-quality blocker: Pathologist sign-off pending in `expert_review_checklist.csv`) |

---

## 2. Definitive Status Definitions

In strict accordance with project audit rules, every source in this register is categorized into one of three statuses:

1. **`VERIFIED FOR PROPOSED USE`:**  
   The dataset provenance, public repository deed, and associated publication terms have been independently verified as granting legal permission for copying, adaptation, machine learning model training, internal evaluation, and academic presentation within the defined non-commercial college project scope (e.g. Creative Commons Attribution 4.0 International, CC BY 4.0). All required attribution records, source URLs, access dates, and modification statements are formally documented. Clearance applies strictly to the evaluated research scope; commercial release is not implied.
2. **`PENDING RIGHTS CONFIRMATION`:**  
   The dataset exhibits unresolved legal encumbrances, third-party copyright claims without an open public license, or unverified terms where legal authority cannot be established from primary repository records, requiring formal written clarification from rights holders before use.
3. **`NOT CLEARED`:**  
   The dataset carries restrictive terms that explicitly prohibit model fine-tuning, adaptation, or weight derivation (e.g. CC BY-NC-ND 4.0 "No-Derivatives"), or the dataset has failed scientific data-quality and label-integrity audits (e.g. severe cross-label contradictions, artificial artifacts). Ingestion into model pipelines is strictly prohibited.

---

## 3. Crop-by-Crop Source & Attribution Register

---

### 3.1 Rice (*Oryza sativa*)

The Rice foliar pipeline relies on two complementary open-access academic field datasets to cover three disease classes and one negative-control healthy class.

#### Source Register 1A: `rice_mendeley_sethy_v2` (Foliar Diseases)

```
Dataset Name:          Rice Leaf Disease Image Samples, Version 2
Target Classes:        rice_bacterial_leaf_blight, rice_brown_spot, rice_leaf_blast
Host Repository:       Mendeley Data (Elsevier BV)
Repository DOI:        10.17632/fwcj7stb8r.2
Repository URL:        https://data.mendeley.com/datasets/fwcj7stb8r/2
Repository Version:    Version 2
Publication Date:      2020-06-18
Access Date:           2026-10-18
Associated Paper:      Sethy, P.K., Behera, S.K., Barpanda, N.K., & Rath, A.K. (2020). 
                       "Deep feature based rice leaf disease identification using support vector machine." 
                       Computers and Electronics in Agriculture, Vol. 175, 105527.
Paper DOI:             https://doi.org/10.1016/j.compag.2020.105527
Authors:               Prabira Kumar Sethy, Santi Kumari Behera, Nalini Kanta Barpanda, Amiya Kumar Rath
Author Affiliation:    Sambalpur University / VSSUT, Burla, Odisha, India
Verified Licence:      Creative Commons Attribution 4.0 International (CC BY 4.0)
Licence Evidence:      Declared CC BY 4.0 on Mendeley Data portal; open-access paper under CC BY 4.0.
Intended Project Use:  Non-commercial college project, model training, validation, academic defense.
Usable Sample Size:    524 canonical unaugmented specimens (BLB: 264, Brown Spot: 100, Blast: 160).
Quarantined Data:      5,408 pre-augmented synthetic copies; 1,308 Tungro images.
Attribution Citation:  Sethy, P.K., et al. (2020). Rice Leaf Disease Image Samples (v2). Mendeley Data, 
                       doi:10.17632/fwcj7stb8r.2. Licensed under CC BY 4.0.
Licence Restrictions:  Attribution mandatory; include CC BY 4.0 notice; state modifications 
                       (deduplication to canonical leaves, square cropping). No commercial restriction.
Approval Status:       VERIFIED FOR PROPOSED USE
```

#### Source Register 1B: `rice_mendeley_hasan_2023` (Healthy Negative Controls)

```
Dataset Name:          Rice Leaf Bacterial and Fungal Disease Dataset, Version 2
Target Classes:        rice_healthy (Negative diagnostic control)
Host Repository:       Mendeley Data (Elsevier BV)
Repository DOI:        10.17632/hx6f852hw4.2
Repository URL:        https://data.mendeley.com/datasets/hx6f852hw4/2
Repository Version:    Version 2
Publication Date:      2023-11-27
Access Date:           2026-10-18
Associated Paper:      Hasan, M., Khatun, S., Raihan, M.A., & Uddin, A.H. (2023). 
                       "Rice Leaf Bacterial and Fungal Disease Dataset." Data in Brief.
Paper DOI:             https://doi.org/10.17632/hx6f852hw4.2
Authors:               Mehedi Hasan, Sonia Khatun, Md. Abu Raihan, Abdul Hasib Uddin
Author Affiliation:    Khwaja Yunus Ali University, Sirajganj, Bangladesh
Verified Licence:      Creative Commons Attribution 4.0 International (CC BY 4.0)
Licence Evidence:      Declared CC BY 4.0 on Mendeley Data web portal and legal deed.
Intended Project Use:  Non-commercial college project, negative-control training and specificity testing.
Usable Sample Size:    154 canonical unaugmented healthy specimens (Oryza sativa).
Quarantined Data:      5,188 augmented files in separate zip; 49 internal duplicates (1 cross-class).
Attribution Citation:  Hasan, M., et al. (2023). Rice Leaf Bacterial and Fungal Disease Dataset (v2). 
                       Mendeley Data, doi:10.17632/hx6f852hw4.2. Licensed under CC BY 4.0.
Licence Restrictions:  Attribution mandatory; provide license link; state modifications.
Approval Status:       VERIFIED FOR PROPOSED USE
```

---

### 3.2 Groundnut (*Arachis hypogaea*)

The Groundnut pipeline uses an authentic Indian plantation dataset covering early leaf spot and healthy foliage.

#### Source Register 2: `mendeley_groundnut_v3` (Aishwarya & Reddy, 2023)

```
Dataset Name:          Dataset of groundnut plant leaf images for classification and detection (v3)
Target Classes:        groundnut_early_leaf_spot (Cercospora arachidicola), groundnut_healthy
Host Repository:       Mendeley Data / Elsevier Data in Brief
Repository DOI:        10.17632/22p2vcbxfk.3
Repository URL:        https://data.mendeley.com/datasets/22p2vcbxfk/3
Repository Version:    Version 3
Publication Date:      2023-06-01
Access Date:           2026-10-18
Associated Paper:      Aishwarya, M.P., & Reddy, A.P. (2023). 
                       "Dataset of groundnut plant leaf images for classification and detection." 
                       Data in Brief, Vol. 48, 109185.
Paper DOI:             https://doi.org/10.1016/j.dib.2023.109185
Authors:               M.P. Aishwarya, A. Padmanabha Reddy
Author Affiliation:    Vijayanagara Sri Krishnadevaraya University, Bellary, Karnataka, India
Verified Licence:      Creative Commons Attribution 4.0 International (CC BY 4.0)
Licence Evidence:      Explicitly CC BY 4.0 across BOTH the Mendeley Data repository and the 
                       published Elsevier Data in Brief article: "© 2023 The Author(s). Published by 
                       Elsevier Inc. This is an open access article under the CC BY license".
Intended Project Use:  Non-commercial college project, model training, validation, academic presentation.
Usable Sample Size:    3,594 usable images across 824 independent specimen clusters 
                       (Early Leaf Spot: 1,727 images in 412 clusters; Healthy: 1,867 images in 412 clusters).
Quarantined Data:      8 cross-class duplicate images shared with nutrition_deficiency_1; 
                       6,759 images in non-target folders (late spot, rust, abiotic chlorosis).
Remediation Action:    Publisher's 100% leaking train/test split replaced with CropDoc group-locked 
                       clustering (dH <= 2) to eliminate burst shot evaluation leakage.
Attribution Citation:  Aishwarya, M.P., & Reddy, A.P. (2023). Dataset of groundnut plant leaf images 
                       for classification and detection. Data in Brief, 48, 109185. 
                       doi:10.1016/j.dib.2023.109185. Licensed under CC BY 4.0.
Licence Restrictions:  Attribution mandatory; include CC BY 4.0 notice; state modifications.
Approval Status:       VERIFIED FOR PROPOSED USE
```

---

### 3.3 Cotton (*Gossypium hirsutum*)

The Cotton pipeline utilizes an open-access field dataset collected in Bangladesh, staged under `data/staged/mendeley_cotton_2025/`.

#### Source Register 3: `mendeley_cotton_2025` (Ripon et al., East West University)

```
Dataset Name:          Cotton Leaf Image Dataset for Disease Classification and Health Monitoring
Target Classes:        cotton_bacterial_blight (Xanthomonas citri pv. malvacearum), cotton_healthy
Host Repository:       Mendeley Data (Elsevier BV)
Repository DOI:        10.17632/t9hgvk2h9p.1
Repository URL:        https://data.mendeley.com/datasets/t9hgvk2h9p/1
Repository Version:    Version 1
Publication Date:      2025-01-13 (Mendeley Data v1 deposit; Data in Brief Vol. 63 published Oct 2025)
Access Date:           2026-10-24
Associated Paper:      Ripon, S.H., Gani, R., Niha, N.M., Rahat, W.B., Toufiq, S.H., Maisha, M.F., 
                       & Ahmed, J. (2025). "Cotton Leaf Image Dataset for Disease Classification 
                       and Health Monitoring." Data in Brief, Vol. 63, 112142.
Paper DOI:             https://doi.org/10.1016/j.dib.2025.112142
Authors:               Shamim H Ripon, Rifat Gani, Nuzhat Mubashshira Niha, Wasif Bin Rahat, 
                       Syed Hasibur Toufiq, Mayeesha Farzana Maisha, Jamil Ahmed
Author Affiliation:    Department of Computer Science and Engineering, East West University, Dhaka, Bangladesh
Verified Licence:      Creative Commons Attribution 4.0 International (CC BY 4.0) [Mendeley Data v1]
Publication License:   Creative Commons Attribution-NonCommercial 4.0 International (CC BY-NC 4.0) [Article Text]
Attribution Citation:  Ripon, S.H., Gani, R., Niha, N.M., Rahat, W.B., Toufiq, S.H., Maisha, M.F., 
                       & Ahmed, J. (2025). Cotton Leaf Image Dataset for Disease Classification 
                       and Health Monitoring (v1). Mendeley Data, doi:10.17632/t9hgvk2h9p.1. 
                       Licensed under CC BY 4.0.
Intended Project Use:  Non-commercial college major project, academic demonstration, ML model development, 
                       internal testing, and academic defense/presentation.
Licensing Status:      VERIFIED FOR PROPOSED USE (College-Project Research Scope)
Data Quality Status:   BLOCKED (Pathologist sign-off pending in expert_review_checklist.csv)
```

#### Detailed Legal Analysis & Licence Reassessment

1. **Independent Licensing Instruments:**  
   The primary data files were deposited by the copyright-holding authors directly onto the Mendeley Data repository under **Creative Commons Attribution 4.0 International (CC BY 4.0)** (Version 1 record: `https://data.mendeley.com/datasets/t9hgvk2h9p/1`).  
   Separately, the associated descriptor publication in Elsevier's *Data in Brief* (DOI: `10.1016/j.dib.2025.112142`) includes Elsevier's standard open-access journal user notice: *"This is an open access article under the CC BY-NC license"*.  
   In intellectual property law, the license applied by a publisher to the narrative article text and journal formatting constitutes a distinct legal instrument from the public license attached by data creators to independent dataset files deposited on an open repository. The article's publishing license does **not** silently alter, override, or extinguish the public CC BY 4.0 grant attached to the Mendeley Data files.

2. **Compliance with Non-Commercial College Project Scope:**  
   Even if the narrower *Data in Brief* user terms (`CC BY-NC 4.0`) were conservatively evaluated against the dataset, Section 4(a) of the Creative Commons NonCommercial deed defines non-commercial as *"not primarily intended for or directed towards commercial advantage or monetary compensation."*  
   The CropDoc college major project encompasses academic evaluation, student demonstration, internal model development, and academic defense. This is strictly non-commercial and educational. Therefore, the proposed educational and research usage complies fully under **both** CC BY 4.0 and CC BY-NC 4.0.

3. **Grounding in Repository Evidence (No Fabricated Permissions):**  
   This clearance is grounded strictly and transparently on the public CC BY 4.0 license record published on Mendeley Data Version 1. **No separate written permission, bilateral waiver, or correspondence from the authors or Elsevier has been received or is claimed.**

4. **Scope Limitation & Commercial Boundary:**  
   This determination clears the cotton dataset **exclusively** for the defined college project research scope. It does **not** grant blanket or unrestricted authorization for commercial mobile distribution, monetization on the Google Play Store, or commercial agricultural advisory services. If commercial distribution is sought in the future, formal written clarification or commercial licensing from the rights holders must be obtained.

#### Provenance Verification & Physical File Parity

The actual cotton images staged in CropDoc have been rigorously audited against the Mendeley Data Version 1 distribution:

- **Source Archive:** Mendeley Data Version 1 (`https://data.mendeley.com/datasets/t9hgvk2h9p/1`, accessed 2026-10-24).
- **Physical Raw Directory:** `data/raw/medeley_cotton_2025/` (extracted directly from the Mendeley repository zip archive).
- **Staged Target Directory:** `data/staged/mendeley_cotton_2025/`
  - `cotton_bacterial_blight`: 185 canonical images
  - `cotton_healthy`: 174 canonical images
  - Total Staged Set: **359 clean, unaugmented images** across 268 specimen groups.
- **Manifest Tracking:** Recorded in `data/manifests/manifest_cotton_staged.csv` with full perceptual hashes (`phash`), dimensions, source classes, and deterministic split assignments.
- **Checksum Parity:** **359 of 359 staged images (100.0%)** match their corresponding raw repository files byte-for-byte and have identical SHA-256 digests.
- **Curation Filtering:**
  - 4,963 pre-augmented synthetic copies were discarded.
  - 10 cross-class identical duplicates (conflicting with wilt classes) were quarantined.
  - 182 intra-class duplicate pairs (including 159 burst duplicates in healthy foliage) were deduplicated.
  - 822 non-target images (`Alternaria Leaf Spot`: 188, `Fusarium Wilt`: 436, `Verticillium Wilt`: 390) were excluded from the 2-class cotton staging.

#### Tracking Data Licensing Separately from Data Quality

> [!IMPORTANT]
> **Licensing clearance does NOT establish production readiness.**
> A dataset can be 100% legally cleared for research use while remaining scientifically unvalidated. 

In CropDoc governance, **legal licensing** and **biological label quality** are tracked as separate, independent gates:

| Gate Dimension | Current Assessment | Operational Status |
|:---|:---|:---:|
| **Legal Licensing Gate** | Mendeley Data v1 CC BY 4.0 public grant verified; fully authorized for college project research and academic defense. | **PASSED (Cleared for Research Scope)** |
| **Data Quality Gate** | Label integrity across the 359 staged images must be reviewed by a plant pathologist. The review ledger [`expert_review_checklist.csv`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/expert_review_checklist.csv) currently has **0 of 359** images signed off. | **BLOCKED (Active Training Prerequisite)** |

Production model training on cotton data remains strictly gated on completing the pathologist review, preserving scientific integrity.

---

## 4. Audited & Non-Target Datasets Register

For governance completeness, all additional datasets audited in the repository are cataloged below:

| Dataset Identifier | Host / Publication | Declared Licence | Scope / Target Evaluated | Scientific & Legal Audit Outcome | Approval Status |
|:---|:---|:---|:---|:---|:---:|
| `fieldplant_v2` | IEEE Access / Roboflow (Moupojou et al., 2023) | **CC BY 4.0** | Candidate for multiple field crops | Audited & Failed: 231 duplicate groups (140 cross-class conflicting labels); 92.1% of "healthy" leaves had active foliar blight. Quarantined. | **NOT CLEARED** |
| `plantwild_ext` | ACM Multimedia / Hugging Face (2024) | **CC BY-NC-ND 4.0** | External benchmark | Prohibited by "No-Derivatives" (ND) and "Non-Commercial" (NC) clauses; 279 conflicting duplicate groups. | **NOT CLEARED** |
| `plantseg_ext` | Nature Scientific Data (2026) | **CC BY-NC 4.0** | Semantic leaf segmentation | Non-commercial restriction; restricted to research benchmarking only. | **NOT CLEARED** |
| `plantvillage_v1` | Penn State / TFDS (Mohanty et al., 2016) | **CC0 1.0 / CC BY-SA 3.0** | Laboratory baseline | Copyleft ambiguity; severe lab-to-field domain shift (collapses to 31–38% accuracy in field). Excluded from field expansion. | **NOT CLEARED** |
| `plantdoc_v2` | IIT Gandhinagar / ACM (Singh et al., 2020) | **CC BY 4.0 (Compilation)** | Auxiliary field benchmark | Scraped web photos; underlying photographer copyright unresolved. Excluded from primary training. | **NOT CLEARED** |
| `own_field_2026` | First-Party Telangana & Pan-India Collection | **First-Party Open (DPDP Act)** | Rice, Cotton, Groundnut field validation | First-party agronomic collection with explicit DPDP consent. GPS EXIF stripped. Cleared for all uses. | **VERIFIED FOR PROPOSED USE** |

---

## 5. Concise Synthesis: What Is Cleared vs What Remains Pending

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                   SUMMARY OF THREE-CROP RESEARCH CLEARANCE STATUS                      │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. RICE (4 Classes): 100% CLEARED FOR PROPOSED RESEARCH USE                            │
│    • Sources: Sethy v2 (2020) [BLB, Brown Spot, Blast] + Hasan v2 (2023) [Healthy]     │
│    • Licences: CC BY 4.0 verified across both repository and publication records.     │
│    • Research Clearance: Authorized for model training, evaluation, academic defense.  │
│    • Release Gate: Model deployment gated pending unified 8-class integration.         │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 2. GROUNDNUT (2 Classes): 100% CLEARED FOR PROPOSED RESEARCH USE                       │
│    • Source: Aishwarya & Reddy v3 (2023) [Early Leaf Spot, Healthy]                    │
│    • Licences: CC BY 4.0 verified across BOTH Mendeley Data and Elsevier Data in Brief│
│    • Research Clearance: Authorized for model training, evaluation, academic defense.  │
│    • Release Gate: Model deployment gated pending unified 8-class integration.         │
├────────────────────────────────────────────────────────────────────────────────────────┤
│ 3. COTTON (2 Classes): 100% CLEARED FOR PROPOSED RESEARCH USE                          │
│    • Source: Mendeley Data v1 (Ripon et al., East West University, DOI 10.17632/t9hgvk│
│    • Licensing: CC BY 4.0 explicit on Mendeley Data v1 repository record.              │
│      Article CC BY-NC 4.0 notice does not invalidate independent repository license,   │
│      and college project research scope complies with non-commercial educational use.  │
│    • Provenance: 359 staged images 100% verified against Mendeley v1 (SHA-256 match).  │
│    • Research Clearance: Authorized for college project model training & academic demo│
│      (grounded in repository evidence; no separate written permission claimed).        │
│    • Commercial Limitation: Unrestricted commercial app distribution is NOT cleared.   │
│    • Data Quality Blocker: 0 of 359 images confirmed by pathologist. Training BLOCKED  │
│      until expert review in expert_review_checklist.csv is completed.                  │
└────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 6. Action Roadmap to Production Training

With all three crops cleared for the non-commercial college project research scope, the project roadmap proceeds through the following verified milestones:

### Step 1: Complete Cotton Human-in-the-Loop Pathologist Review (Data Quality Blocker)
- **Objective:** Establish biological ground-truth validity before model training.
- **Action:** Review the 359 staged images via the visual contact sheets portal at `ml/reports/mendeley_cotton_2025/contact_sheets/index.html`.
- **Deliverable:** Populate the `pathologist_verdict` column in [`expert_review_checklist.csv`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/ml/reports/mendeley_cotton_2025/expert_review_checklist.csv).
- **Quality Action:** Permanently remove any image marked `Incorrect label` or `Image unsuitable or ambiguous` from the training manifest.

### Step 2: Commercial Rights Clarification (Future Milestone — Outside College Scope)
- **Objective:** Secure formal commercial indemnity if CropDoc is subsequently published as a commercial application or public app-store offering beyond the academic project.
- **Action:** Transmit the prepared inquiry letter ([`COTTON_PRODUCTION_APPROVAL_PACKAGE.md`](./COTTON_PRODUCTION_APPROVAL_PACKAGE.md#L94)) to Dr. Shamim H Ripon and Elsevier Permissions.
- **Status:** Optional for the non-commercial college major project; mandatory before any commercial deployment.

### Step 3: Production Eight-Class Model Training & Release Validation
- **Objective:** Train the MobileNetV3-Small INT8 model on the unified 8-class taxonomy across all three cleared crops:
  - Rice: 678 canonical specimens (Sethy v2 + Hasan v2)
  - Groundnut: 3,594 clean images across 824 disjoint specimen clusters (Aishwarya & Reddy v3)
  - Cotton: Pathologist-approved subset of the 359 staged images (Ripon et al., 2025)
- **Validation:** Execute deterministic disjoint evaluation, verify out-of-domain leaf rejection gates, and pass `./gradlew.bat validateProductionModelRelease`.

---

*Preservation Notice: All production assets, model files, labels, manifests, and release gates remain strictly intact and fail-closed. No production code was modified during this audit.*
