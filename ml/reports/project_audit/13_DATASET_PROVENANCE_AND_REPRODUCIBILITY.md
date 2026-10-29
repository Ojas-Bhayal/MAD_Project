# Phase M: Dataset Provenance, Research Scripts, and Reproducibility Audit

**Audit Date:** 2026-10-24T23:48:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** ML Provenance Specialist, Data Integrity & Ethics Auditor  

---

## 1. Executive Summary: Provenance and Ethics Log

The CropDoc project enforces a strict dataset integrity and licensing policy (`data/licenses/SOURCES.md`).
- **Commercial & Mobile Release Boundary:** Only datasets possessing verified **CC0 1.0**, **CC BY 4.0**, or first-party field collection with explicit farmer consent are permitted for production model training.
- **Strict Quarantine of NC/ND Sources:** All Non-Commercial (`CC BY-NC`) and No-Derivatives (`CC BY-NC-ND`) datasets (such as `plantwild_ext` and `plantseg_ext`) are quarantined and excluded from training.
- **Publisher Leakage Remediation:** Mendeley Groundnut v3 initially suffered from severe publisher leakage where 100% of test images were pre-augmented duplicates of train images. The CropDoc ML team clustered specimens via perceptual hashing (`phash`), isolating all physical specimens into group-aware splits with **0% specimen leakage**.

---

## 2. Dataset Inventory and Licensing Decision Matrix

| Dataset Identifier | Full Dataset Title & Source | License Claimed | Verified License | Audit Verdict | Staged Specimens | Forensic Findings |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`rice_mendeley_sethy`** | Rice Leaf Disease Image Samples, v2 (Sethy et al., 2020) | CC BY 4.0 | **CC BY 4.0** | **APPROVED (Pilot Only)** | 524 Canonical Leaves | 5,408 pre-augmented synthetic copies quarantined; 524 core physical leaves extracted across Blight (264), Brown Spot (100), and Blast (160). |
| **`rice_mendeley_hasan`** | Rice Bacterial & Fungal Disease Dataset, v2 (Hasan et al., 2023) | CC BY 4.0 | **CC BY 4.0** | **APPROVED (Pilot Only)** | 154 Healthy Leaves | Provided 154 canonical healthy rice leaves, resolving the missing healthy control blocker. 49 duplicate groups deduplicated. |
| **`mendeley_groundnut_v3`**| Groundnut Plant Leaf Images (Aishwarya & Reddy, 2023) | CC BY 4.0 | **CC BY 4.0** | **APPROVED (Pilot Only)** | 824 Clusters | Publisher split had 100% test leakage. Re-clustered into 824 specimen clusters with 0 test leakage. 8 cross-class conflicts quarantined. |
| **`fieldplant_v2`** | FieldPlant Dataset (Moupojou et al., IEEE Access 2023) | CC BY 4.0 | CC BY 4.0 | **REJECTED / QUARANTINED** | 0 Images Approved | 231 exact duplicate groups (140 conflicting labels). Cameroon Corn rust is Southern rust (*P. polysora*), not Common rust (*P. sorghi*); generic symptom lumping. |
| **`mendeley_cotton_2025`** | Cotton Leaf Image Dataset (Babu et al., 2025) | CC BY 4.0 | **CC BY-NC 4.0 (Paper Conflict)** | **QUARANTINED** | 359 Review Only | Mendeley repo claims CC BY 4.0, but Elsevier paper asserts CC BY-NC 4.0. Training strictly blocked pending legal confirmation. |
| **`own_field_2026`** | CropDoc Telangana & Pan-India Field Collection | First-Party | **First-Party Open (DPDP Consent)** | **APPROVED** | Multi-crop Field | Collected under morning/noon/dusty field conditions across budget smartphones. Double-labeled by agronomists ($\kappa = 0.91$). |
| **`plantwild_ext`** | PlantWild Dataset | CC BY-NC-ND | **CC BY-NC-ND 4.0** | **REJECTED** | 0 Images | Prohibits modification, resizing, and commercial distribution. |

---

## 3. Dataset Leakage and Deduplication Verification

Audit scripts in `ml/scripts/` were evaluated for specimen isolation and leakage prevention:
1. `check_dataset_overlap.py`: Computes SHA-256 exact byte matches across train, validation, and test splits.
2. `check_split_phash.py`: Computes difference-hash (dHash) and perceptual-hash (pHash) with Hamming distance threshold $\le 4$.
3. **Specimen Grouping Rule:**
   - Where identical plants were photographed from multiple angles or lightings, they share identical specimen IDs (`specimen_id`).
   - Group-aware split generators ensure all images of a physical plant are placed exclusively in Train, Validation, or Test—never split across partitions.

---

## 4. Script and Training Pipeline Reproducibility

| Script Path | Purpose | Reproducibility Status | Notes |
| :--- | :--- | :--- | :--- |
| `ml/scripts/train_crop_domain_gate.py` | Trains 3-class crop domain gate on MobileNetV3-Small | Fully Reproducible | Output: PyTorch `.pt` checkpoint and TorchScript model. |
| `ml/scripts/train_unified_six_class_pilot.py` | Trains 6-class disease model (Rice & Groundnut) | Fully Reproducible | Output: PyTorch `.pt` checkpoint. |
| `ml/scripts/export_to_litert_tflite.py` | Exports PyTorch models to LiteRT FP32 via `litert-torch` | Remote Linux / Colab | Requires Linux environment with `litert-torch 0.10.0`. |
| `ml/scripts/inspect_tflite_graph.py` | Inspects LiteRT subgraphs for tensor shapes, dtypes, class counts | Local Windows / Host | Fully executable with standard Python and TensorFlow/LiteRT. |
| `ml/scripts/verify_tflite_numerical_parity.py` | Verifies numerical parity between PyTorch logits and LiteRT output | Host Python | Parity verified: Max absolute error $< 3.1 \times 10^{-6}$. |
