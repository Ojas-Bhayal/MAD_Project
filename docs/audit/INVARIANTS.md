# CropDoc System Invariant Audit

This document provides explicit proof or disproof for each of the 10 critical system invariants defined for the CropDoc offline-first Android app and ML dataset pipeline.

---

### Invariant 1: `labels.txt` count == model output count, for the bundled model and for every OTA package
**Status:** **FAIL**

- **Bundled Model (`v1`):**
  - Model file: [`app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite) (16,800 bytes)
  - Labels file: [`app/src/main/assets/models/labels.txt`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/assets/models/labels.txt) (16 classes)
  - Output tensor dimension: 16 (`raw_logits [1, 16]`).
  - *Result for v1:* Count matches (16 == 16).
- **OTA Model Package (`v2`):**
  - Model file: [`models/v2/cropdoc_leaf_v2_int8.tflite`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/models/v2/cropdoc_leaf_v2_int8.tflite) (16,848 bytes)
  - Labels file: [`models/v2/labels.txt`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/models/v2/labels.txt) (22 classes)
  - Output tensor dimension: 16. The script [`ml/scripts/train_and_export_v2.py`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/ml/scripts/train_and_export_v2.py#L28-L31) merely appended 48 bytes of footer text (`CROPDOC_V2_INT8_22CLASSES_SHA_LOCKED_20261018...`) to the 16-class v1 model binary without retraining or exporting a 22-class graph.
  - *Result for v2:* 22 labels != 16 output tensor logits.
  - Furthermore, [`LiteRtLeafClassifier.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LiteRtLeafClassifier.java#L172-L175) strictly checks:
    ```java
    if (!isLabelTensorCountConsistent(candidateLabels, tensorClasses)) {
        candidate.close();
        modelUpdateManager.clearInvalidOtaFiles();
        return false;
    }
    ```
    LiteRT correctly rejects the v2 package as corrupted upon installation.

---

### Invariant 2: Every label index maps to a stable disease id; advice exists for every id in en/hi/te; the advice catalog size constant matches reality
**Status:** **PASS**

- **Evidence:**
  - In [`data/taxonomy/classes_v2.yaml`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/data/taxonomy/classes_v2.yaml) and [`models/v2/labels.txt`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/models/v2/labels.txt), indices `0..15` are identical to `v1/labels.txt`. Indices `16..21` are appended additively (`groundnut_early_leaf_spot`, `groundnut_healthy`, `chilli_leaf_curl_virus`, `chilli_healthy`, `soybean_rust`, `soybean_healthy`).
  - [`app/src/main/assets/advice/advice_catalog.json`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/assets/advice/advice_catalog.json) contains exactly 63 entries (21 distinct disease/healthy leaf identifiers $\times$ 3 languages: `en`, `hi`, `te`). The OOD class `background_not_a_leaf` correctly maps to outcome `NOT_A_SUPPORTED_LEAF` and does not require treatment advice.
  - [`AdviceSeedLoader.EXPECTED_CATALOG_SIZE`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/local/AdviceSeedLoader.java#L25) is set to `63`, matching `advice_catalog.json`.
  *(Note: [`README.md`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/README.md#L28) retains a stale comment stating "45-entry catalog", which is a minor documentation nit, but the runtime code and database invariant pass).*

---

### Invariant 3: History rows store predicted id + model_version so old rows stay readable after a model update
**Status:** **PASS**

- **Evidence:**
  - [`ScanEntity.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/local/entity/ScanEntity.java#L36-L67) includes:
    - `@ColumnInfo(name = "disease_id") private final String diseaseId;`
    - `@ColumnInfo(name = "model_version") private final String modelVersion;`
  - In [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L65-L77), `saveScanSync()` populates `entity.diseaseId` from `diagnosis.getLabelId()` (string-based slug, e.g. `rice_bacterial_leaf_blight`) and `diagnosis.getModelVersion()`.
  - When a model update adds new classes, existing Room database rows retain their exact disease string identifier and originating model version, remaining completely readable and valid.

---

### Invariant 4: A model update either fully installs (model+labels+version) or leaves the previous working set untouched
**Status:** **FAIL**

- **Evidence:**
  - In [`ModelUpdateManager.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/ModelUpdateManager.java#L106-L115):
    ```java
    File destLabels = new File(appContext.getFilesDir(), OTA_LABELS_FILENAME);
    File destModel = new File(appContext.getFilesDir(), OTA_MODEL_FILENAME);
    if (!replaceFileAtomically(stagedLabelsFile, destLabels)) {
        return false;
    }
    if (!replaceFileAtomically(stagedModelFile, destModel)) {
        destLabels.delete(); // <--- Destroys working labels!
        return false;
    }
    activeVersion = targetVersion;
    return true;
    ```
  - **Failure Scenario:** If a working OTA update (v1.1) is installed, and a new update (v1.2) arrives: `destLabels` is replaced with v1.2 labels. Then, if replacing `destModel` fails (e.g. disk space exhausted), line 113 executes `destLabels.delete()`. The previous working labels file is permanently deleted while `destModel` remains at v1.1. On the next restart, `otaLabels == null`, crashing or invalidating the previous working model set.
  - Additionally, [`replaceFileAtomically()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/ModelUpdateManager.java#L131-L137) falls back to `new FileOutputStream(destFile, false)` if `renameTo` fails. Truncating a file actively memory-mapped by `LiteRtLeafClassifier` causes an immediate native `SIGBUS` crash.

---

### Invariant 5: One user action produces exactly one scan row, one sync record and one navigation
**Status:** **PASS**

- **Evidence:**
  - **Scan Row:** [`ScanViewModel.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ui/scan/ScanViewModel.java#L39,L69,L113) enforces an `AtomicBoolean analyzing = new AtomicBoolean(false)` concurrency guard. Consecutive double-taps on the capture button or photo picker are dropped (`analyzing.compareAndSet(false, true)`). Exactly one scan row is inserted via `scanDao.insertOrReplace(entity)`.
  - **Sync Record:** [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L163-L178) enqueues a background WorkManager task with `ExistingWorkPolicy.KEEP` and a deterministic UUID `scanId`. In [`FirestoreSyncSource.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L66-L71), writes use `batch.set(db.collection("users").document(userId).collection("scans").document(entity.getScanId()), ...)`. Retried sync tasks overwrite the same document idempotently.
  - **Navigation:** [`ScanFragment.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java#L210-L213,L220-L230) invokes `viewModel.clearScanResult()` prior to navigating to `ResultFragment`, preventing sticky LiveData re-triggering, and verifies `nav.getCurrentDestination().getId() == R.id.scanFragment`.

---

### Invariant 6: Anything marked "stays on device" (guest mode, EXIF/GPS) really never leaves the device on any code path
**Status:** **PASS**

- **Evidence:**
  - **Guest Mode:** In [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L79,L126), `saveScanSync` skips background sync enqueueing if `userId.equals(FirebaseAuthSource.LOCAL_GUEST_UID)`. Furthermore, `syncPendingBatch` verifies `if (FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) return true;` before touching Firestore.
  - **EXIF/GPS Stripping:** On both camera capture and gallery picker paths, [`LeafPreprocessor.decodeSampledBitmap()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LeafPreprocessor.java#L38-L53) decodes the image into an in-memory Android `Bitmap`. [`ExifPrivacySanitizer.saveSanitizedLeafJpeg()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java#L40) writes a freshly compressed JPEG stream from the pixel buffer (which by definition strips camera metadata and GPS coordinates), and executes [`stripSensitiveExifTags()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java#L83-L96). Only an EXIF-free 96x96 Base64 thumbnail is uploaded to Firestore for signed-in users.
  - **Cloud Backups:** [`AndroidManifest.xml`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/AndroidManifest.xml#L14) disables backup (`android:allowBackup="false"`), and [`data_extraction_rules.xml`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/res/xml/data_extraction_rules.xml#L4-L5) explicitly excludes `cropdoc_offline.db` and `scans/` from cloud backups.

---

### Invariant 7: Training preprocessing == app preprocessing
**Status:** **FAIL**

- **Evidence:**
  - **App Preprocessing:** [`LeafPreprocessor.autoTightCropLeafBounds()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LeafPreprocessor.java#L166-L185) scans row and column foliage counts to find a tight bounding box `[x0, y0, spanW, spanH]`, slices `sub = Bitmap.createBitmap(reticle224, x0, y0, spanW, spanH)`, and then executes:
    ```java
    Bitmap tight224 = Bitmap.createScaledBitmap(sub, ModelConfig.INPUT_WIDTH, ModelConfig.INPUT_HEIGHT, true);
    ```
    For long, narrow leaves (such as rice blades with $W:H \approx 1:6$), this stretches the image width by up to 600%, non-isotropically distorting lesions and leaf vein geometry.
  - **Training Preprocessing:** In [`ml/CropDoc_Training_Colab.ipynb`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/ml/CropDoc_Training_Colab.ipynb) (Cell 2 & Cell 3), training images were normalized with standard aspect-ratio preserving resizing, horizontal/vertical flips, and minor rotation. No tight foliage bounding box extraction or non-square aspect stretching was ever applied during dataset preparation.
  - **Runtime Execution:** Because the bundled model is under 512 KB, [`LiteRtLeafClassifier.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LiteRtLeafClassifier.java#L193) sets `interpreter = null`, and the app falls back to heuristic chromaticity rules in [`LeafSpectralAnalyzer.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LeafSpectralAnalyzer.java). The ML model graph is never actually executed.

---

### Invariant 8: No data group appears in more than one split
**Status:** **PASS (with Critical Defect AUD-DATA-001)**

- **Evidence:**
  - Verification script across [`data/manifests/manifest_v2.csv`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/data/manifests/manifest_v2.csv) confirms 0 group leakage: no single `group_id` exists in both `train` and `test` or `val`.
  - **Critical Defect (`AUD-DATA-001`):** In [`ml/scripts/deduplicate_groups.py`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/ml/scripts/deduplicate_groups.py#L55,L61), lines 55 & 61 query `cid = rows[idx].get("class_id", "")`. Because `manifest_v2.csv` has column `class_name` instead of `class_id`, `cid` evaluated to `""` for all rows. Consequently, all 66 curated images across 22 classes were clustered together into a single mega-cluster (`v2_field_grp_00_train`) and overwritten with `split = train`.
  - While technically no group straddles splits, the `val` split was completely wiped out (`val: 0`), and `split_v2.json` (`counts_by_split: {train: 22, val: 22, test: 32}`) is completely out of sync with `manifest_v2.csv` (`train: 66, val: 0, test: 10`).

---

### Invariant 9: Deleting an account/scans removes data from Room, Storage, Firestore and the pending sync queue
**Status:** **FAIL**

- **Evidence:**
  - In [`FirestoreSyncSource.deleteAllUserCloudData()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L102-L109), all delete operations for scans and feedback are committed in a single Firestore `WriteBatch`. Firestore has a hard platform limit of 500 operations per batch (https://firebase.google.com/docs/firestore/manage-data/transactions#batched-writes). If an active user has >500 documents (e.g. 260 scans + 260 feedback entries = 520 writes), `batch.commit()` throws an `IllegalArgumentException` and fails completely.
  - In [`AuthViewModel.deleteAccountAndData()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ui/auth/AuthViewModel.java#L135-L136), the return value of `scanRepository.deleteAllUserScansSync()` is ignored. Even if cloud deletion fails, `authRepository.deleteCurrentAccount()` immediately deletes the Firebase Auth account. Once the account is deleted, Firestore security rules (`request.auth.uid == userId`) permanently prevent anyone from deleting those orphaned cloud documents.
  - In [`ScanRepository.deleteAllUserScansSync()`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L109-L123), `WorkManager.getInstance(appContext).cancelUniqueWork("cropdoc_scan_sync_work")` is never called. If a background sync worker was currently scheduled or running, it continues executing.

---

### Invariant 10: Release builds never contain a placeholder/tiny model or debug-only code
**Status:** **FAIL**

- **Evidence:**
  - Bundled release asset: [`app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite) has a file size of only **16,800 bytes**. A real MobileNetV3-Large full-integer INT8 model requires approximately 3.5 to 5.5 MB.
  - In [`LiteRtLeafClassifier.java`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/src/main/java/com/cropdoc/app/ml/LiteRtLeafClassifier.java#L34,L193-L196):
    ```java
    private static final long MIN_FULL_MODEL_BYTES = 512L * 1024L; // 524,288 bytes
    ...
    if (afd.getDeclaredLength() < MIN_FULL_MODEL_BYTES) {
        interpreter = null;
        return;
    }
    ```
    The codebase itself explicitly acknowledges that the bundled asset is a lightweight placeholder file below `MIN_FULL_MODEL_BYTES` and intentionally refuses to initialize a LiteRT interpreter for it.
  - In [`app/build.gradle`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/app/build.gradle#L37-L43), the `release` build type packages this 16.8 KB placeholder model into production AAB/APK builds.
  - The v2 model [`models/v2/cropdoc_leaf_v2_int8.tflite`](file:///C:/Users/ojas/OneDrive/Desktop/CropDoc/models/v2/cropdoc_leaf_v2_int8.tflite) is also a 16,848 byte placeholder generated by appending text in `ml/scripts/train_and_export_v2.py`.

---

### Invariant Summary Matrix

| Invariant # | Description | Status | Primary Root Cause / Notes |
| :--- | :--- | :---: | :--- |
| **INV-1** | `labels.txt` count == model output count | **FAIL** | v2 OTA model graph has 16 tensor outputs while `v2/labels.txt` has 22 labels. |
| **INV-2** | Label index stability & advice catalog size | **PASS** | 21 classes $\times$ 3 languages = 63 entries matches `EXPECTED_CATALOG_SIZE`. |
| **INV-3** | History rows store predicted id + model_version | **PASS** | Room schema and entity mapping persist both fields stably. |
| **INV-4** | Model update atomic install & untouched rollback | **FAIL** | Failure to install model deletes existing working labels; unsafe `FileOutputStream` truncation. |
| **INV-5** | 1 user action == 1 scan row, 1 sync, 1 navigation | **PASS** | `AtomicBoolean` guard, deterministic UUID, and single-top nav flow. |
| **INV-6** | Device-only privacy guarantees (Guest & EXIF) | **PASS** | In-memory bitmap recompression strips EXIF; guest mode strictly skips network sync. |
| **INV-7** | Training preprocessing == app preprocessing | **FAIL** | App applies arbitrary `autoTightCropLeafBounds` aspect-ratio stretching; bypasses ML model. |
| **INV-8** | No data group in more than one split | **PASS\*** | 0 group leakage, but `manifest_v2.csv` has 0 val samples due to bug in `deduplicate_groups.py`. |
| **INV-9** | Complete data deletion (Room, Cloud, Sync Queue) | **FAIL** | Firestore 500-write batch limit fails on active users; pending sync queue not cancelled. |
| **INV-10** | Release builds never contain placeholder models | **FAIL** | Ships 16.8 KB placeholder model in `assets/models/`; LiteRT disabled in production. |
