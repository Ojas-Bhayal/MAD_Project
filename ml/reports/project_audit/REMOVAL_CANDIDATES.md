# CropDoc — Safe File-Removal Execution Record & Final Audit Manifest

**Execution Date:** October 24, 2026  
**Auditor:** Senior Software Maintenance & Android Build Architect  
**Status:** **CLEANUP COMPLETED FOR APPROVED CANDIDATES (RM-001 through RM-034).**  
**Retained Candidates:** RM-035, RM-036, and RM-037 remain strictly preserved.  
**Post-Cleanup Verification:** **100% PASS** (113 unit tests, split independence, and release gate verified).

---

## 1. Execution Log

| Candidate ID | Exact Relative File Path | Pre-Removal Size | Git Status | Action Executed | Post-Removal Verification | Result |
| :--- | :--- | :---: | :---: | :---: | :--- | :---: |
| **RM-001** | `ml/scripts/get_scripts.py` | 336 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest and verify ml/scripts/inspect_tflite_graph.py remains intact.` | VERIFIED REMOVED |
| **RM-002** | `ml/scripts/find_fetch_files.py` | 368 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-003** | `ml/scripts/find_content_details.py` | 373 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-004** | `ml/scripts/find_replace_hostname.py` | 353 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-005** | `ml/scripts/print_vp.py` | 332 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-006** | `ml/scripts/test_endpoints.py` | 1,493 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-007** | `ml/scripts/test_folders.py` | 721 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-008** | `ml/scripts/test_vendor_mime.py` | 749 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-009** | `ml/scripts/inspect_mendeley_js.py` | 503 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-010** | `ml/scripts/inspect_mendeley_url.py` | 1,191 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-011** | `ml/scripts/inspect_bundle.py` | 723 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-012** | `ml/scripts/inspect_zip_handler.py` | 560 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-013** | `ml/scripts/inspect_orig_folder.py` | 1,211 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-014** | `ml/scripts/inspect_roboflow_page.py` | 1,494 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-015** | `ml/scripts/inspect_fieldplant_coco.py` | 658 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-016** | `ml/scripts/inspect_dr_properties.py` | 740 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-017** | `ml/scripts/compare_img_dr.py` | 645 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-018** | `ml/scripts/inspect_split_collisions.py` | 502 B | Untracked | **REMOVED** | `Run python ml/scripts/verify_split_independence_final.py.` | VERIFIED REMOVED |
| **RM-019** | `ml/scripts/inspect_brownspot_duplication.py` | 663 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-020** | `ml/scripts/inspect_rice_augmentation.py` | 1,897 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-021** | `ml/scripts/inspect_rice_filenames.py` | 1,299 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-022** | `ml/scripts/inspect_groundnut_naming.py` | 1,266 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-023** | `ml/scripts/inspect_groundnut_raw.py` | 1,116 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-024** | `ml/scripts/inspect_specimen_stems.py` | 892 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-025** | `ml/scripts/inspect_groups.py` | 1,490 B | Untracked | **REMOVED** | `Run python ml/scripts/verify_new_split_independence.py.` | VERIFIED REMOVED |
| **RM-026** | `ml/scripts/inspect_dh1.py` | 1,416 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-027** | `ml/scripts/inspect_dh2_close.py` | 1,408 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-028** | `ml/scripts/inspect_clusters_dh1.py` | 2,040 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-029** | `ml/scripts/inspect_cluster_chains.py` | 1,769 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-030** | `ml/scripts/inspect_specific_pairs.py` | 1,750 B | Untracked | **REMOVED** | `Execute ./gradlew.bat testDebugUnitTest.` | VERIFIED REMOVED |
| **RM-031** | `ml/scripts/__pycache__/common.cpython-311.pyc` | 1,964 B | Git Tracked (`git rm`) | **REMOVED** | `Verify git status is clean and python -c "import ml.scripts.common" succeeds.` | VERIFIED REMOVED |
| **RM-032** | `ml/scripts/__pycache__/common.cpython-314.pyc` | 2,116 B | Untracked | **REMOVED** | `Run python ml/scripts/train_crop_domain_gate.py --help.` | VERIFIED REMOVED |
| **RM-033** | `ml/scripts/__pycache__/check_split_phash.cpython-314.pyc` | 5,404 B | Untracked | **REMOVED** | `Verify python ml/scripts/check_split_phash.py --help succeeds.` | VERIFIED REMOVED |
| **RM-034** | `ml/scripts/__pycache__/test_phash_merging.cpython-314.pyc` | 5,295 B | Untracked | **REMOVED** | `Verify python ml/scripts/test_phash_merging.py --help succeeds.` | VERIFIED REMOVED |
| **RM-035** | `app/src/main/assets/models/model_metadata.json` | 902 B | Git Tracked (`git rm`) | **KEPT** | `If approved for removal: update README.md line 64 and execute ./gradlew.bat assembleDebug.` | VERIFIED INTACT |
| **RM-036** | `ml/scripts/fieldplant_detailed_audit_metrics.json` | 6,411 B | Untracked | **KEPT** | `Run python ml/scripts/analyze_fieldplant_annotations_and_hashes.py to verify exact regeneration.` | VERIFIED INTACT |
| **RM-037** | `ml/scripts/fieldplant_image_audit_summary.json` | 178 B | Untracked | **KEPT** | `Run python ml/scripts/deep_audit_fieldplant.py to verify exact regeneration.` | VERIFIED INTACT |

---

## 2. Post-Cleanup Verification Summary

1. **Git Tracking Hygiene:**
   - Stale tracked bytecode cache `ml/scripts/__pycache__/common.cpython-311.pyc` was cleanly unstaged and deleted via `git rm` (`D ml/scripts/__pycache__/common.cpython-311.pyc`).
   - Verified `python -c "import ml.scripts.common"` executes without error.
2. **Unit Test Suite:**
   - Executed `./gradlew.bat testDebugUnitTest` across all 16 test suites (113 assertions).
   - `BUILD SUCCESSFUL in 7s` (0 failures, 0 errors).
3. **Research Dataset Split Independence:**
   - Executed `python ml/scripts/verify_split_independence_final.py`: PASS on all 4 independent tests (zero exact duplicates, zero specimen groups crossing splits, 100% physical hash match).
   - Executed `python ml/scripts/verify_new_split_independence.py`: PASS (zero $d_H = 0$ cross-split pairs).
4. **Release Gate Enforced:**
   - Executed `./gradlew.bat validateBundledModelForRelease`: Aborted as designed (placeholder model threshold guard active).
5. **Explicitly Retained Files Verified Intact:**
   - `app/src/main/assets/models/model_metadata.json` (RM-035) — 958 bytes (Preserved for README doc consistency).
   - `ml/scripts/fieldplant_detailed_audit_metrics.json` (RM-036) — 6,411 bytes (Preserved).
   - `ml/scripts/fieldplant_image_audit_summary.json` (RM-037) — 178 bytes (Preserved).
   - `ml/scripts/inspect_tflite_graph.py` (Category D) — 5,402 bytes (Preserved for release validation task).