# Phase O: Individual File-Removal Candidate Assessment

**Audit Date:** 2026-10-24T23:52:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Senior Software Maintenance & Clean Code Auditor  
**Enforcement Policy:** **ANALYSIS ONLY. ZERO FILES DELETED. ALL CANDIDATES SET TO `PENDING_APPROVAL`.**

---

## 1. Context and Previous Removal Audit Re-Verification

During earlier audit phases, approved candidates **RM-001 through RM-034** were executed and confirmed cleanly removed.
The previously audited candidates **RM-035, RM-036, and RM-037** were explicitly designated as **KEPT** and remain preserved intact:
- `app/src/main/assets/models/model_metadata.json` (RM-035) — 958 B (`KEPT` for README documentation parity).
- `ml/scripts/fieldplant_detailed_audit_metrics.json` (RM-036) — 6,411 B (`KEPT` for provenance record).
- `ml/scripts/fieldplant_image_audit_summary.json` (RM-037) — 178 B (`KEPT` for provenance record).

---

## 2. New Candidate Inventory (RM-038 through RM-050)

Every potential candidate file was checked against Java code, XML layouts, Gradle tasks, Python scripts, manifests, and test suites.

| Candidate ID | Exact Relative Path | Size (Bytes) | Git Status | Purpose | Dependency Evidence | Runtime / Build Impact | Research Impact | Recommendation | Confidence | Approval Status |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **RM-038** | `ml/scripts/get_config_client.py` | 658 B | Untracked | Scratch script used to scrape Mendeley public API JSON config | Zero references in Gradle, Java, or ML training pipelines. | None. Pure one-off utility. | None. Mendeley dataset download complete. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-039** | `ml/scripts/inspect_exact_and_phash.py` | 1,845 B | Untracked | Early scratch script inspecting exact and pHash matches in Mendeley Groundnut | Superseded by `ml/scripts/verify_split_independence_final.py`. | None. | None. Covered by canonical verification scripts. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-040** | `ml/scripts/inspect_groundnut_duplicates.py` | 2,027 B | Untracked | Diagnostic script inspecting duplicate filenames in Mendeley Groundnut | Superseded by `generate_groundnut_manifest_and_splits.py`. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-041** | `ml/scripts/inspect_remaining_cross.py` | 5,405 B | Untracked | Diagnostic script checking cross-split duplicates before final split generation | Superseded by `verify_split_independence_final.py`. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-042** | `ml/scripts/analyze_hamming_distances.py` | 1,293 B | Untracked | Diagnostic script printing dHash Hamming distance distribution | One-off exploratory analysis. Zero pipeline references. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-043** | `ml/scripts/test_268_split_audit.py` | 5,381 B | Untracked | Temporary test script auditing the 268-specimen split iteration | Superseded by `test_split_regeneration.py`. | None. | None. | **PROPOSE REMOVAL** | Medium | `PENDING_APPROVAL` |
| **RM-044** | `ml/scripts/test_clustering_criteria.py` | 4,349 B | Untracked | Experimental script testing dHash thresholds (1, 2, 3) | Superseded by `cluster_all_cotton_specimens.py`. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-045** | `ml/scripts/test_final_clustering_rule.py` | 2,292 B | Untracked | Test runner validating final clustering rule | Superseded by canonical verification test suite. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-046** | `ml/scripts/test_refined_rule.py` | 2,256 B | Untracked | Scratch script testing intermediate cluster refinement rule | One-off testing script. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-047** | `ml/scripts/test_rule_with_dh3.py` | 2,292 B | Untracked | Scratch script testing Hamming distance threshold $d_H = 3$ | Exploratory experiment script. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-048** | `ml/scripts/test_specimen_grouping.py` | 2,680 B | Untracked | Unit-like test checking specimen ID grouping | Superseded by `verify_split_independence_final.py`. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-049** | `ml/scripts/test_phash_merging.py` | 3,110 B | Untracked | Unit-like test checking pHash graph merging | Superseded by `cluster_all_cotton_specimens.py`. | None. | None. | **PROPOSE REMOVAL** | High | `PENDING_APPROVAL` |
| **RM-050** | `ml/scripts/test_split_regeneration.py` | 5,977 B | Untracked | Regression test confirming split JSON files can be regenerated deterministically | Validates deterministic split regeneration. | None on app build. | Useful for long-term split reproducibility. | **KEEP** | High | `PENDING_APPROVAL` |

---

## 3. Explicit Retain List (Do Not Remove)

The following files were inspected and confirmed **CRITICAL**:
1. `ml/scripts/inspect_tflite_graph.py`: **DO NOT REMOVE.** Directly invoked by Gradle task `validateBundledModelForRelease` in `app/build.gradle`.
2. `ml/scripts/verify_split_independence_final.py`: **DO NOT REMOVE.** Canonical test asserting zero specimen leakage across data splits.
3. `ml/scripts/export_to_litert_tflite.py`: **DO NOT REMOVE.** Official PyTorch-to-LiteRT export script.
4. `ml/scripts/calibrate_and_benchmark_policy.py`: **DO NOT REMOVE.** Generates calibration tables for `InferenceDecisionPolicy`.
5. `app/src/main/assets/models/model_metadata.json`: **DO NOT REMOVE.** Documented in project README.

---

## 4. Mandatory Approval Statement

No files have been modified or deleted. All candidates RM-038 through RM-050 remain in `PENDING_APPROVAL` status. Removal will only occur if explicitly directed with specific candidate IDs.
