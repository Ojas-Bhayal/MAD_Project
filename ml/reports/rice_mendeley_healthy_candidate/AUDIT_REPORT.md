# CropDoc — Rice Healthy Candidate Dataset Audit & Forensic Report

**Dataset:** Rice Leaf Bacterial and Fungal Disease Dataset, Version 2  
**Audit Date:** October 24, 2026  
**Auditor:** CropDoc ML Safety & Data Governance Team  
**Governing Rule:** Research evaluation only; no Android code modification; release safeguards remain active.

---

## 1. Executive Summary & Provenance Verification

We completed the forensic audit and verification of the **Rice Leaf Bacterial and Fungal Disease Dataset (Version 2)** collected by Hasan et al. from Khwaja Yunus Ali University, hosted on Mendeley Data.

| Metric | Verified Value | Notes |
| :--- | :--- | :--- |
| **Dataset Title** | *Rice Leaf Bacterial and Fungal Disease Dataset* (Version 2) | Mendeley Data Repository |
| **Archive Source** | `Original Images.zip` | Extracted into `data/raw/rice_mendeley_healthy_candidate/Original Images/` |
| **Archive File Size** | 1,664,385,692 bytes (~1.55 GB) | Verified byte size |
| **Archive SHA-256** | `eb1e804ae414250d7a333a1c3820c49a520c2f09e2e6a38f280e90f58a4f62fb` | Cryptographically verified |
| **Canonical URL** | [https://data.mendeley.com/datasets/hx6f852hw4/2](https://data.mendeley.com/datasets/hx6f852hw4/2) | Version 2, published Nov 27, 2023 |
| **Permanent DOI** | `10.17632/hx6f852hw4.2` | Data in Brief / Mendeley |
| **Authors** | Mehedi Hasan, Sonia Khatun, Md. Abu Raihan, Abdul Hasib Uddin | Khwaja Yunus Ali University, Sirajganj, Bangladesh |
| **Field Location** | Paddy agricultural plots across Sirajganj and Pabna districts, Bangladesh | Field photography captured July–October 2023 |
| **Verified Licence** | **CC BY 4.0** | Commercial and non-commercial training permitted with attribution |
| **Total Original Files** | **1,701** images | 0 corrupt, 0 unreadable (100% valid JPEGs) |
| **Pre-Augmented Files** | 5,188 augmented files isolated in `Augmented Images.zip` (2.01 GB) | Excluded right at ingestion to prevent benchmark contamination |
| **Total Classes** | 8 categories | 4 CropDoc-compatible, 4 unsupported |
| **Healthy Rice Leaf Class**| **157 images** (**154 unique canonical specimens**) | **Provides the missing negative control class for CropDoc** |
| **Collisions with Sethy v2**| **0** exact SHA-256 collisions, **0** perceptual $d_H=0$ collisions | Independent geographic provenance (Bangladesh vs. Odisha, India) |

---

## 2. Image Geometry, Quality & Integrity

All 1,701 files in `Original Images` were validated through Pillow image decoders and SHA-256 digests:
- **Corrupt / Unreadable Images:** **0**
- **Image Format:** 100% JPEG (`.jpg`), 3-channel RGB color space.
- **Resolution Distribution:** Authentic high-resolution mobile camera captures under natural field and indoor illumination:
  - $1600 \times 1200$: 1,572 images (92.4%)
  - $4000 \times 3000$: 29 images
  - $2160 \times 3840$ / $3840 \times 2160$: 22 images
  - Other aspect ratios: 78 images
- **Green Foliage Dominance:** Healthy leaf images exhibit an average green color ratio of $0.504$ (range $0.418$–$0.580$) with healthy vegetative vigor and uniform exposure.

---

## 3. Class Counts & Taxonomy Compatibility

Comparing the 8 categories in Hasan v2 against CropDoc's 22 target classes ([`classes_v2.yaml`](file:///c:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/data/taxonomy/classes_v2.yaml)):

| Source Label | Target CropDoc Label | Class Index | Compatibility | Raw Files | Usable Canonical Leaves | Status in CropDoc |
| :--- | :--- | :---: | :---: | :---: | :---: | :---: |
| **Healthy Rice Leaf** | `rice_healthy` | 3 | **Exact Match** | 157 | **154** | **APPROVED (Selected for 4-Class Pilot)** |
| **Bacterial Leaf Blight** | `rice_bacterial_leaf_blight` | 0 | Exact Match | 180 | 179 | Supported (Secondary field collection) |
| **Brown Spot** | `rice_brown_spot` | 1 | Exact Match | 267 | 260 | Supported (Secondary field collection) |
| **Leaf Blast** | `rice_leaf_blast` | 2 | Exact Match | 305 | 270 | Supported (Secondary field collection) |
| *Leaf scald* | *None* | -1 | Unsupported | 189 | 0 | **QUARANTINED** (*Microdochium oryzae*) |
| *Narrow Brown Leaf Spot*| *None* | -1 | Unsupported | 117 | 0 | **QUARANTINED** (*Cercospora janseana*) |
| *Rice Hispa* | *None* | -1 | Unsupported | 215 | 0 | **QUARANTINED** (*Dicladispa armigera* insect damage) |
| *Sheath Blight* | *None* | -1 | Unsupported | 271 | 0 | **QUARANTINED** (*Rhizoctonia solani*) |
| **Total** | | | | **1,701** | **863** | 792 unsupported images quarantined |

---

## 4. Internal Duplicate & Contradiction Audit

Our forensic analysis detected **49 exact SHA-256 duplicate groups** (98 files) in Hasan v2:

1. **Critical Cross-Class Contradiction (1 Group / 2 Files):**
   - File 1: `Bacterial Leaf Blight/Bacterial_leaf_Blight  (33).jpg`
   - File 2: `Leaf Blast/Leaf_blast  (289).jpg`
   - SHA-256: `f788382af1a79265...` (Bit-for-bit identical).
   - *Impact:* The exact same physical image is labeled as Bacterial Blight in one folder and Leaf Blast in another.
   - *Action:* **Both files permanently quarantined** (`duplicate_status: CONFLICTING_LABEL_DUPLICATE`, `usable_for_cropdoc_training: false`).
2. **Same-Class Duplicate Groups (48 Groups / 96 Files):**
   - `Healthy Rice Leaf`: 3 exact duplicate pairs (`(3)`/`(4)`, `(37)`/`(39)`, `(41)`/`(42)`). Canonical representatives elected; redundant copies marked `EXACT_BYTE_DUPLICATE`.
   - `Brown Spot`: 7 duplicate pairs (14 files).
   - `Leaf Blast`: 34 duplicate pairs (68 files).
   - `Sheath Blight`: 4 duplicate pairs (8 files).

---

## 5. Cross-Dataset Independence from Sethy v2

We evaluated all 1,701 files in Hasan v2 against all 5,932 files in Sethy v2:
- **Exact SHA-256 Collisions:** **0**.
- **Perceptual $d_H=0$ Collisions:** **0**.
- **Conclusion:** The two datasets represent completely distinct physical specimen populations from different geographic regions (Hasan: Bangladesh; Sethy: Odisha, India).

---

## 6. Healthy Class Split Specification (Seed 42)

The 154 unique canonical healthy rice leaves were partitioned into a group-locked 70/15/15 split:
- **Train Split (70%):** **108 images** (108 groups)
- **Validation Split (15%):** **23 images** (23 groups)
- **Held-Out Test Split (15%):** **23 images** (23 groups)
- Redundant duplicate copies (`(4)`, `(39)`, `(42)`) locked to their canonical twins' partitions. Zero cross-split leakage.

---

## 7. Readiness Verdict

- **Readiness for 4-Class Research Pilot:** **APPROVED**. The 154 canonical healthy images successfully supply the missing negative control class for CropDoc's rice pipeline.
- **Production Android Deployment:** **BLOCKED**. While 4-class discrimination is verified, model rejection of non-leaf images (hands, soil, background) and unsupported conditions (leaf scald, sheath blight, hispa damage) must be evaluated before app integration.
