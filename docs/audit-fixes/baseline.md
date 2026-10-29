# CropDoc Audit Implementation Baseline & Verification Record

**Date:** October 18, 2026
**Project:** CropDoc (`com.cropdoc.app`, `minSdk 24`, `targetSdk 37`, Java 17, LiteRT 2.3.0)

## 1. Phase 0 Claim Verifications (Section 2 of IMPLEMENTATION_PLAN.md)
1. **#2 (`Files.move` vs `minSdk 24`):** Verified `minSdk = 24` in `app/build.gradle`. Because `java.nio.file.Files` requires API 26+, `ModelUpdateManager.replaceFileAtomically` uses same-directory staging + `renameTo` with `FileChannel` stream-copy fallback and `finally` cleanup.
2. **#1 / D2 / D3 (OTA Model + Labels Pair):** `ModelUpdateManager` now verifies SHA-256 for both `cropdoc_ota_verified.tflite` and `cropdoc_ota_labels.txt` and installs them as one atomic unit; `LiteRtLeafClassifier` checks `labels.size() == tensorClasses` at load time and falls back to the bundled pair on mismatch.
3. **#3 / Advice Catalog Count (63 Rows):** Verified 21 crop/disease classes (15 in v1 + 6 in v2; index 15 is `background_not_a_leaf` which uses the OOD rejection card) x 3 languages (`en`, `hi`, `te`) = 63 rows (`AdviceSeedLoader.EXPECTED_CATALOG_SIZE = 63`).
4. **#6 (`deduplicate_groups.py`):** Pre-parses 64-bit integer pHashes once, uses 8-bit multi-index bucket lookup + `int.bit_count()`, preserves existing `group_id` values via Union-Find, and writes `manifest_v2.csv` atomically with `.bak` backup.
5. **#10 (`LeafClassifier` Interface):** Kept by design (`D4`) so `AnalyzeLeafUseCase` and JVM unit tests (`ScanViewModelTest`) remain free of Android Context and native LiteRT dependencies.
6. **#14 (`DiseaseLabelFormatter.capitalizeWords`):** Replaced `split("_")` with a single-pass character loop.
7. **#15 (`room-guava`):** Removed unused `room-guava` from `gradle/libs.versions.toml` and `app/build.gradle`; retained `guava` for CameraX `ListenableFuture`.
