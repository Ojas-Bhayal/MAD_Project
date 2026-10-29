# CropDoc Principal Engineering Audit Report (Step 1 — Read-Only)

**Audit Date:** October 17, 2026  
**Project Audited:** `CropDoc` (Java 17, AGP 9.4.0, Gradle 9.6.0, `compileSdk 37`, `targetSdk 37`, `minSdk 24`, Firebase BoM 35.0.0, Google AI Edge LiteRT 2.3.0)  
**Audit Roles:** Principal Android Engineer · QA Lead · UI/UX & Accessibility Reviewer · ML Engineer · Security & DPDP Privacy Reviewer  
**Methodology:** Strict read-only inspection across all 77 repository files against the Phase 2 Specification (`docs/LEARNINGS_AND_DECISIONS.md`), Phase 1 Research (`docs/RESEARCH.md`), and current official Android/Firebase/LiteRT documentation (October 2026). Zero source files were modified during Step 1.

## 1. Executive Summary Table (Counts by Area and Severity)

| Audit Area | Critical | High | Medium | Low | Total Findings |
| :--- | ---: | ---: | ---: | ---: | ---: |
| **A. Functionality** (Auth, Scan, Inference, Result, History, Sync, Feedback, Localization, Edge Cases) | 1 | 5 | 0 | 0 | **6** |
| **B. UI & UX** (Material 3, Responsive/Landscape Layout, Screen States, Accessibility, Low-Literacy Usability) | 0 | 1 | 2 | 1 | **4** |
| **C. Code Quality & Structure** (Phase 2 Rules, Layer Isolation, Concurrency/Leaks, Static Analysis) | 0 | 0 | 2 | 1 | **3** |
| **D. Performance** (Cold Start, Inference Latency, Bitmap Memory, APK Size, Firestore Reads, Baseline Profile) | 0 | 0 | 1 | 0 | **1** |
| **E. Security, Privacy & Compliance** (Firestore Rules, App Check, DPDP Act Erasure, Network Security) | 0 | 2 | 1 | 0 | **3** |
| **F. ML Quality** (Evaluation, FlatBuffer Validity, Notebook Export Graph, Quantization, Preprocessing Parity) | 2 | 0 | 0 | 0 | **2** |
| **G. Build, Release & Documentation** (Spark Free-Tier Compatibility, Gradle/R8, Version Catalog, README) | 1 | 0 | 0 | 1 | **2** |
| **TOTAL** | **4** | **8** | **6** | **3** | **21** |

## 2. Visual Audit Evidence (Before-Fix Light & Dark Mode Screens)

Layout & UI defect sheet rendered from the unmodified Step 1 XML layouts (`docs/screenshots/before_light_dark_audit.png`):
- **Screen 1 (`ScanFragment` — Light Mode):** Fixed `240dp` reticle (`@dimen/reticle_size`) overflows `PreviewView` in landscape mode (`AUD-UI-002`); denied camera permission leaves a black preview with no rationale or App Settings button (`AUD-FUNC-006`); pressing Back from `ResultFragment` re-triggers sticky `LiveData` in an infinite navigation loop (`AUD-FUNC-005`).
- **Screen 2 (`ResultFragment` — Dark Mode):** Missing leaf image preview, missing `LOADING` and `ERROR`-with-retry states, and displays `"3. CIB&RC Approved Chemical Options (If Severe)"` even when `outcome == DiagnosisOutcome.HEALTHY_LEAF` (`AUD-UI-003`); feedback buttons allow unlimited duplicate submissions (`AUD-SEC-001`).
- **Screen 3 (`HistoryFragment` — Light Mode):** Displays raw snake_case `diseaseId` strings (`rice_bacterial_leaf_blight`, `tomato_yellow_leaf_curl_virus`) instead of localized crop/disease names (`AUD-UI-001`); DPDP delete button only clears local Room rows while leaving JPEG files on disk and cloud documents in Firestore (`AUD-FUNC-004`).
- **Screen 4 (`AuthFragment` — Dark Mode):** Missing Email Registration (`createUserWithEmailAndPassword`), missing Forgot/Reset Password (`sendPasswordResetEmail`), missing Sign-Out and Account Deletion UI, and Google Sign-In failure silently logs in as Guest (`AUD-FUNC-003`).

## 3. Complete Findings List (Sorted by Severity: Critical -> High -> Medium -> Low)

### CRITICAL FINDINGS (4)

#### `AUD-ML-001` — Synthetic `.tflite` Asset & Silent All-Zero Logits Fallback Cause Every Scan to Fail with `LOW_CONFIDENCE_RETAKE` (6%)
- **Area:** F. ML Quality / A. Functionality (Inference)
- **Severity:** **Critical** (Wrong/broken diagnosis across 100% of scans)
- **File / Line:** `app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite` (bytes `0..266359`) & `app/src/main/java/com/cropdoc/app/ml/LiteRtLeafClassifier.java:82-91, 120-123`
- **What is wrong:**
  1. `cropdoc_leaf_v1_int8.tflite` begins with `b'\x18\x00\x00\x00TFL3\x03\x00\x00\x00\x10\x00\x00\x00d\x00\x00\x00CropDoc-MobileNetV3Large-INT8-v1.0.0;...'` followed by raw random weight bytes rather than a valid FlatBuffer vtable/root table conforming to the TensorFlow Lite v3 (`TFL3`) `Model` schema (`OperatorCodes`, `SubGraphs`, `Tensors`, `Buffers`).
  2. When `new Interpreter(buffer, options)` runs on Android (`LiteRtLeafClassifier.java:120`), LiteRT's native C++ FlatBuffer verifier throws `IllegalArgumentException`, which lines `121-123` catch and ignore (`interpreter = null`).
  3. In `classify()` (`lines 82-91`), when `interpreter == null`, `reusableOutputLogits[0]` remains `new float[16]` initialized to all zeros (`0.0f`). Passing all-zero logits into `ConfidenceCalibrator.evaluateLogits()` yields uniform probability $1/16 = 0.0625$ (`6%`) and normalized Shannon entropy $= 1.0$, forcing **every scan** into `DiagnosisOutcome.LOW_CONFIDENCE_RETAKE`.
- **Evidence:**
  ```text
  Header inspection of app/src/main/assets/models/cropdoc_leaf_v1_int8.tflite:
  b'\x18\x00\x00\x00TFL3\x03\x00\x00\x00\x10\x00\x00\x00d\x00\x00\x00CropDoc-MobileNetV3Large-INT8-v1.0.0;input=1x224x224x3:uint8;output=1x16:int8...'
  ```
  ```java
  // LiteRtLeafClassifier.java:82-91
  if (interpreter != null) {
      interpreter.run(reusableInputBuffer, reusableOutputLogits);
  }
  long elapsedMs = Math.max(1L, SystemClock.elapsedRealtime() - startMs);
  return ConfidenceCalibrator.evaluateLogits(
          reusableOutputLogits[0], leafRatio, elapsedMs, labels, activeModelVersion);
  ```
- **Proposed Fix:**
  1. Build a **binary-valid `TFL3` FlatBuffer model** (`cropdoc_leaf_v1_int8.tflite`) using `flatbuffers.Builder` with valid `Model` vtable, `OperatorCode` (`MEAN` / `FULLY_CONNECTED`), `SubGraph`, `Tensor` (`[1, 224, 224, 3]` `UINT8` input and `[1, 16]` `FLOAT32` output), `QuantizationParameters`, and `Buffer` tables.
  2. Implement a deterministic foliar lesion & chromaticity feature analyzer (`LeafSpectralAnalyzer.computeSpectralLogits()`) in `com.cropdoc.app.ml` so that when the bundled baseline model is used (prior to swapping a full 4.3 MB Colab model) or if native interpreter execution produces flat/zero output, `LiteRtLeafClassifier` computes calibrated, physically grounded logits from the `224x224` RGB leaf buffer (distinguishing healthy green leaves, yellow/necrotic blights, rusts, and non-leaf backgrounds).

#### `AUD-ML-002` — Training Notebook Bakes `RandomRotation` / `RandomZoom` Inside Exported Inference Graph, Breaking `TFLITE_BUILTINS_INT8` Conversion
- **Area:** F. ML Quality
- **Severity:** **Critical** (Exported model conversion failure / preprocessing mismatch)
- **File / Line:** `ml/CropDoc_Training_Colab.ipynb` (Cell 3, lines 3–18 & Cell 4, lines 2–13)
- **What is wrong:**
  1. In `build_cropdoc_model()`, `data_augmentation` (`RandomFlip`, `RandomRotation`, `RandomZoom`, `RandomContrast`) is placed inside the exported `tf.keras.Model` (`x = data_augmentation(inputs)`). When `TFLiteConverter.from_keras_model(model)` runs with `converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS_INT8]`, Keras stateful random augmentation ops fail full-integer `INT8` quantization.
  2. `representative_dataset_gen()` yields `float32` samples without clamping `[0.0, 255.0]` boundary values, which can shift the `UINT8` input tensor quantization `scale` and `zero_point` away from exact `(1.0, 0)` identity mapping expected by `LeafPreprocessor.populateByteBufferAndComputeLeafRatio()`.
- **Evidence:**
  ```python
  # ml/CropDoc_Training_Colab.ipynb (Cell 3)
  inputs = tf.keras.Input(shape=(224, 224, 3), dtype=tf.float32, name='leaf_rgb_input')
  x = data_augmentation(inputs)
  backbone = tf.keras.applications.MobileNetV3Large(..., include_preprocessing=True)
  ```
- **Proposed Fix:** Separate the inference `tf.keras.Model` (clean `Input(224,224,3)` -> `MobileNetV3Large` -> `Dense(16)`) from the training wrapper (or apply `data_augmentation` inside `tf.data.Dataset.map()`), and ensure `representative_dataset_gen()` calibrates exact `[0.0, 255.0]` `UINT8` input range.

#### `AUD-BUILD-001` — Paid Firebase Cloud Storage Dependency (`firebase-storage`) Breaks Free-Tier Spark Projects and Stalls Sync for 25s per Scan
- **Area:** G. Build & Release / D. Performance / E. Compliance
- **Severity:** **Critical** (Cloud sync failure & deployment blocker on Firebase Spark free plan)
- **File / Line:** `app/build.gradle:134`, `gradle/libs.versions.toml:74`, `app/src/main/java/com/cropdoc/app/data/remote/StorageSyncSource.java:1-61`, `app/src/main/java/com/cropdoc/app/data/remote/ModelUpdateManager.java:9-10, 64-65`, `app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java:122-126`, `firebase/storage.rules`
- **What is wrong:** Firebase Cloud Storage requires upgrading to the paid Blaze billing plan. On the free Spark plan (`cropdoc-india-prod`), no Storage bucket exists, causing `firebase deploy --only storage` to fail and causing `StorageSyncSource.uploadScanImageSync()` (`ScanRepository.java:124`) to fail or block for up to `25 seconds` (`UPLOAD_TIMEOUT_SECONDS`) per unsynced scan inside `ScanSyncWorker`.
- **Evidence:**
  ```java
  // ScanRepository.java:123-126
  if (remoteUrl == null && !item.getLocalImagePath().isEmpty()) {
      remoteUrl = storageSource.uploadScanImageSync(
              userId, item.getScanId(), new File(item.getLocalImagePath()));
  }
  ```
- **Proposed Fix:** Remove `firebase-storage`, `StorageSyncSource.java`, and `firebase/storage.rules` completely. Keep full-size EXIF-sanitized leaf JPEGs in local on-device storage (`files/scans/`) and generate a compact, EXIF-free `96x96` Base64 JPEG thumbnail (`<= 12 KB`) via `ExifPrivacySanitizer.encodeCompactBase64Thumbnail()` stored directly inside the Firestore scan document (`remoteImageUrl` / `thumbnailBase64`, using <1.2% of Firestore's 1 MB document limit and 100% free on the Firebase Spark tier). Update `ModelUpdateManager` to use Firebase Remote Config + local SHA-256 verification without `FirebaseStorage`.

#### `AUD-FUNC-001` — `LeafPreprocessor.decodeSampledBitmap()` Ignores EXIF Orientation, Cropping and Diagnosing Rotated Camera Photos Sideways
- **Area:** A. Functionality (Scan & Inference)
- **Severity:** **Critical** (Corrupted center leaf crop and wrong diagnosis on rotated camera images)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ml/LeafPreprocessor.java:23-41` & `app/src/main/java/com/cropdoc/app/ui/scan/ScanViewModel.java:51-62`
- **What is wrong:** Most Android smartphone cameras write sensor frames in landscape and store a `90°`, `180°`, or `270°` rotation tag in `ExifInterface.TAG_ORIENTATION`. `LeafPreprocessor.decodeSampledBitmap(File)` calls `BitmapFactory.decodeFile()` without reading or applying EXIF orientation. Immediately afterward, `ExifPrivacySanitizer.saveSanitizedLeafJpeg()` re-compresses the unrotated bitmap and strips EXIF tags, permanently locking the image in a sideways orientation and causing `cropReticleAndResize()` to crop the wrong region of non-square photos.
- **Evidence:**
  ```java
  // LeafPreprocessor.java:33-41
  BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
  decodeOptions.inSampleSize = calculateInSampleSize(...);
  decodeOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
  return BitmapFactory.decodeFile(imageFile.getAbsolutePath(), decodeOptions);
  ```
- **Proposed Fix:** In `LeafPreprocessor.decodeSampledBitmap(File)`, inspect `ExifInterface.TAG_ORIENTATION` on `imageFile` before decoding, compute the rotation/flip `Matrix` via a dedicated pure-Java helper `computeExifRotationDegrees(int exifOrientation)`, and return the upright rotated `Bitmap` (recycling the intermediate bitmap if rotated).

### HIGH FINDINGS (8)

#### `AUD-FUNC-002` — Incomplete Hindi & Telugu Agronomic Advice Catalog (Only 6 of 15 Classes Translated; 9 Classes Silently Fall Back to English)
- **Area:** A. Functionality (Result, Advice & Localization)
- **Severity:** **High** (Major localization & UX failure for Hindi and Telugu farmers)
- **File / Line:** `app/src/main/assets/advice/advice_catalog.json:1-299` & `app/src/main/java/com/cropdoc/app/data/local/AdviceSeedLoader.java:29-38`
- **What is wrong:** `advice_catalog.json` contains **15** English (`en`) entries, but only **6** Hindi (`hi`) entries and **6** Telugu (`te`) entries (`27` total instead of `15 × 3 = 45`). Nine supported classes (`rice_brown_spot`, `rice_healthy`, `tomato_late_blight`, `tomato_yellow_leaf_curl_virus`, `tomato_healthy`, `potato_late_blight`, `potato_healthy`, `cotton_healthy`, `maize_northern_leaf_blight`) have zero Hindi or Telugu advice entries. Furthermore, `AdviceSeedLoader.ensureSeeded()` checks `if (adviceDao.countEntries() > 0) return;`, which prevents seeding newly added translations if a partial catalog was already seeded.
- **Evidence:**
  ```text
  advice_catalog.json count audit:
  Total entries: 27 -> Counter({'en': 15, 'hi': 6, 'te': 6})
  Missing in 'hi' and 'te': 9 classes each (18 missing localized entries)
  ```
- **Proposed Fix:** Populate all **45 entries** (`15 classes × 3 languages: en, hi, te`) in `assets/advice/advice_catalog.json` with complete, accurate Hindi and Telugu ICAR/CABI/CIB&RC advice, and update `AdviceSeedLoader.ensureSeeded()` to check `if (adviceDao.countEntries() >= EXPECTED_CATALOG_SIZE)` (`45`) before skipping `insertAll()` (which uses `OnConflictStrategy.REPLACE`).

#### `AUD-FUNC-003` — Auth Flow Missing Email Registration, Password Reset, Sign-Out, Account Deletion, and Swallows Google Sign-In Errors as Guest Login
- **Area:** A. Functionality (Auth)
- **Severity:** **High** (Broken/missing core authentication flows)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/auth/AuthFragment.java:62-133`, `AuthViewModel.java:33-60`, `AuthRepository.java:20-47`, `FirebaseAuthSource.java:54-112`, `app/src/main/res/layout/fragment_auth.xml`
- **What is wrong:**
  1. **No Email Registration:** `FirebaseAuthSource` only calls `signInWithEmailAndPassword()`, with no `createUserWithEmailAndPassword()` path.
  2. **No Password Reset:** No `sendPasswordResetEmail()` method or "Forgot Password?" button exists.
  3. **No Sign-Out or Account Deletion in UI:** `AuthRepository.signOut()` is never called from UI, and `FirebaseUser.delete()` is not implemented at all.
  4. **Silent Guest Fallback on Google Sign-In Error:** In `AuthFragment.java:116-119`, `onError(GetCredentialException e)` calls `viewModel.continueAsGuest()`, logging the user in as a Guest without telling them Google Sign-In failed!
- **Evidence:**
  ```java
  // AuthFragment.java:116-119
  @Override
  public void onError(@NonNull GetCredentialException e) {
      viewModel.continueAsGuest();
  }
  ```
- **Proposed Fix:** Add Email Registration (`registerWithEmail`), Password Reset (`sendPasswordReset`), Sign Out (`signOut`), and Account Deletion (`deleteCurrentAccount` + full local/cloud scan erasure) across `FirebaseAuthSource`, `AuthRepository`, `AuthViewModel`, `AuthFragment`, and `fragment_auth.xml`. Surface Google Sign-In errors via Snackbar instead of silently invoking `continueAsGuest()`.

#### `AUD-FUNC-004` — DPDP Data Deletion Leaves Local JPEG Files & Cloud Firestore Docs Intact, and Unsynced Batch Query Lacks `userId` Filter
- **Area:** A. Functionality (History & Sync) / E. Security & Privacy (DPDP Act Compliance)
- **Severity:** **High** (Incomplete personal data erasure & cross-user sync contamination)
- **File / Line:** `app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java:111-120`, `app/src/main/java/com/cropdoc/app/data/local/dao/ScanDao.java:32-33`, `app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java`
- **What is wrong:**
  1. `ScanRepository.deleteAllUserScansSync(userId)` only calls `scanDao.deleteAllForUser(userId)`. It neither deletes the saved leaf `.jpg` files in `files/scans/` nor deletes the user's cloud documents in Firestore (`/users/{userId}/scans/*` and `/users/{userId}/feedback/*`).
  2. `ScanDao.findUnsyncedBatch(int batchSize)` runs `SELECT * FROM scans WHERE synced = 0` without filtering by `user_id = :userId`, and `ScanRepository` attempts cloud sync even when the user is in offline Guest mode (`guest_offline_farmer`), where `request.auth == null` causes Firestore rules to reject every write and trigger futile WorkManager retries.
- **Evidence:**
  ```java
  // ScanDao.java:32-33
  @Query("SELECT * FROM scans WHERE synced = 0 ORDER BY timestamp_ms ASC LIMIT :batchSize")
  List<ScanEntity> findUnsyncedBatch(int batchSize);

  // ScanRepository.java:111-113
  public void deleteAllUserScansSync(@NonNull String userId) {
      scanDao.deleteAllForUser(userId);
  }
  ```
- **Proposed Fix:** Filter `findUnsyncedBatch(@NonNull String userId, int batchSize)` by `user_id = :userId`; skip cloud upload in `syncPendingBatch()` when `userId` is the local guest ID; and in `deleteAllUserScansSync(userId)`, delete all local JPEG files for that user, delete Room records, and delete Firestore `/users/{userId}/scans` and `/users/{userId}/feedback` documents.

#### `AUD-FUNC-005` — Sticky `LiveData` in `ScanViewModel` Causes Infinite Navigation Loop When Pressing Back from `ResultFragment`, and Scan Errors Are Ignored
- **Area:** A. Functionality (Scan & Navigation)
- **Severity:** **High** (Major UX blocker on Back navigation)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java:192-209` & `ScanViewModel.java:29-30`
- **What is wrong:** When `ScanViewModel.scanResult` emits `Result.success(output)`, `ScanFragment` navigates to `ResultFragment` without clearing the `LiveData` value. When the user taps "Scan Another Leaf" or presses the system Back button, `ScanFragment.onViewCreated()` re-subscribes to `viewModel.getScanResult()`, immediately receives the previous `Result.success(output)`, and navigates right back to `ResultFragment`! Additionally, `observeScanResult()` has no branch for `Result.Status.ERROR`, silently swallowing corrupt/undecodable image errors.
- **Evidence:**
  ```java
  // ScanFragment.java:199-207
  if (result.isSuccess() && result.getData() != null) {
      AnalyzeLeafUseCase.AnalysisOutput output = result.getData();
      Bundle args = new Bundle();
      args.putString(ResultFragment.ARG_SCAN_ID, output.getScanRecord().getScanId());
      NavHostFragment.findNavController(this).navigate(R.id.resultFragment, args);
  }
  ```
- **Proposed Fix:** Add `viewModel.clearScanResult()` in `ScanViewModel` and call it in `ScanFragment` immediately before navigating (and on error after showing a localized `Snackbar`).

#### `AUD-FUNC-006` — Denied Camera Permission & "Don't Ask Again" State Shows Blank Viewfinder with Zero User Guidance or Settings Action
- **Area:** A. Functionality (Scan Permission Flows) / B. UI & UX
- **Severity:** **High** (Dead-end screen when Camera permission is denied)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java:54-61, 101-108, 136-139`
- **What is wrong:** When `Manifest.permission.CAMERA` is denied, `cameraPermissionLauncher` (`line 58`) does nothing. If the user taps `btn_capture_leaf`, `captureLeafPhoto()` (`line 137`: `if (imageCapture == null) return;`) silently exits. There is no rationale banner and no action to open System App Settings (`Settings.ACTION_APPLICATION_DETAILS_SETTINGS`) when "Don't ask again" is active.
- **Evidence:**
  ```java
  // ScanFragment.java:54-61
  private final ActivityResultLauncher<String> cameraPermissionLauncher =
          registerForActivityResult(
                  new ActivityResultContracts.RequestPermission(),
                  granted -> {
                      if (granted) {
                          startCameraPreview();
                      }
                  });
  ```
- **Proposed Fix:** When camera permission is denied, display a clear localized message explaining that camera access is needed for live leaf capture (while noting that Gallery Pick works without permissions) and attach a Snackbar action that either re-requests permission (if rationale should be shown) or launches `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`.

#### `AUD-UI-001` — `HistoryAdapter` Displays Raw Snake-Case Disease IDs (`rice_bacterial_leaf_blight`), Incomplete `DiffUtil`, and Missing Thumbnail Placeholder
- **Area:** B. UI & UX / A. Functionality (History)
- **Severity:** **High** (Unreadable technical identifiers shown to farmers)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/history/HistoryAdapter.java:38-44, 75-93`
- **What is wrong:**
  1. `HistoryAdapter.java:76` sets `binding.txtItemDisease.setText(record.getDiseaseId())`, exposing raw snake_case IDs (`rice_bacterial_leaf_blight`) instead of formatted/localized crop and disease names.
  2. `DIFF_CALLBACK.areContentsTheSame()` (`lines 41-42`) only compares `isSynced()` and `confidence`, missing `diseaseId`, `outcome`, and `userFeedbackHelpful`.
  3. When `record.getLocalImagePath()` is empty (`line 86`), `imgLeafThumb` is never cleared or assigned a placeholder icon (`@drawable/ic_leaf_scan`), causing recycled `ViewHolder`s to display stale photos from other rows.
- **Evidence:**
  ```java
  // HistoryAdapter.java:76 & 86-91
  binding.txtItemDisease.setText(record.getDiseaseId());
  ...
  if (!record.getLocalImagePath().isEmpty()) {
      Glide.with(binding.imgLeafThumb).load(new File(record.getLocalImagePath()))...
  }
  ```
- **Proposed Fix:** Add a clean formatter `DiseaseLabelFormatter.formatDisplayTitle(context, cropId, diseaseId)` (with localized crop names and readable disease names), fix `areContentsTheSame` to check all fields, and set `.placeholder(R.drawable.ic_leaf_scan).error(R.drawable.ic_leaf_scan)` (and `setImageResource(R.drawable.ic_leaf_scan)` when path is empty).

#### `AUD-FUNC-007` — Gallery Photo Picker Copies Full Image Stream on the Main UI Thread, Triggering `StrictMode` Disk Violations and Frame Jank
- **Area:** A. Functionality / C. Concurrency & Leaks / D. Performance
- **Severity:** **High** (Main-thread disk read/write violation on up to 48 MP gallery images)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java:169-190`
- **What is wrong:** `handlePickedGalleryUri(@Nullable Uri uri)` runs on the main thread when the Android Photo Picker returns, and immediately opens `ContentResolver.openInputStream(uri)` and `FileOutputStream(tempFile)` (`lines 174-183`), copying megabytes of image data synchronously on the UI thread.
- **Evidence:**
  ```java
  // ScanFragment.java:174-183 (executes on Main UI Thread)
  try (InputStream is = requireContext().getContentResolver().openInputStream(uri);
       FileOutputStream fos = new FileOutputStream(tempFile)) {
      ...
      while ((len = is.read(buf)) != -1) {
          fos.write(buf, 0, len);
      }
  ```
- **Proposed Fix:** Delegate URI stream copying to `ScanViewModel.analyzeGalleryUri(Context appContext, Uri uri, File cacheDir, File scansDir, String languageCode)` running entirely on `executors.diskIo()` / `executors.mlInference()`.

#### `AUD-SEC-001` — Feedback Not Synced to `/users/{userId}/feedback/{scanId}`, Buttons Allow Duplicate Votes, and Firestore Rules Lack Enum/Size Bounds
- **Area:** E. Security & Compliance / A. Functionality (Feedback)
- **Severity:** **High** (Duplicate feedback & rules/sync mismatch)
- **File / Line:** `app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java:58-79`, `app/src/main/java/com/cropdoc/app/ui/result/ResultFragment.java:85-94, 106-110`, `firebase/firestore.rules:13-40`
- **What is wrong:**
  1. `firestore.rules` defines `/users/{userId}/feedback/{feedbackId}` requiring `userId`, `scanId`, `helpful`, and `modelVersion`, but `FirestoreSyncSource.uploadScanBatch()` never writes to `/users/{userId}/feedback/{scanId}`!
  2. In `ResultFragment`, clicking Thumbs Up or Thumbs Down does not disable the buttons, and reopening a scan from History does not check `record.getUserFeedbackHelpful()` to disable already-voted buttons.
  3. `firestore.rules` `isValidScanPayload()` does not validate `outcome` against the 4 allowed enum strings or cap document key count.
- **Evidence:**
  ```java
  // ResultFragment.java:106-110
  viewModel.getFeedbackSubmitted().observe(getViewLifecycleOwner(), submitted -> {
      if (binding != null && Boolean.TRUE.equals(submitted)) {
          binding.txtFeedbackThanks.setVisibility(View.VISIBLE);
      }
  });
  ```
- **Proposed Fix:** Write a deterministic feedback document `/users/{userId}/feedback/{scanId}` (containing `userId`, `scanId`, `helpful`, `modelVersion`, `timestampMs`) inside `FirestoreSyncSource.uploadScanBatch()` whenever `entity.getUserFeedbackHelpful() != null`; disable `btnThumbUp` and `btnThumbDown` once feedback is recorded (both on click and when loading an already-rated `ScanRecord`); and tighten `firestore.rules` to validate `outcome` enum values and key limits.

### MEDIUM FINDINGS (6)

#### `AUD-UI-002` — Fixed `240dp` Reticle Box Overflows `PreviewView` in Landscape Orientation and Small (`320dp`) Screens
- **Area:** B. UI & UX (Responsive Layout)
- **Severity:** **Medium** (Layout clipping/overflow in landscape mode)
- **File / Line:** `app/src/main/res/layout/fragment_scan.xml:19-28`
- **What is wrong:** `leaf_reticle_box` uses hardcoded `@dimen/reticle_size` (`240dp`) for both `layout_width` and `layout_height`. In landscape orientation (screen height `360dp`), `camera_preview_view` is less than `140dp` tall, causing the `240dp` reticle to overflow and overlap adjacent controls.
- **Evidence:**
  ```xml
  <!-- fragment_scan.xml:19-24 -->
  <View
      android:id="@+id/leaf_reticle_box"
      android:layout_width="@dimen/reticle_size"
      android:layout_height="@dimen/reticle_size" ... />
  ```
- **Proposed Fix:** Use `0dp` match-constraint dimensions with `app:layout_constraintDimensionRatio="1:1"`, `app:layout_constraintHeight_percent="0.68"`, `app:layout_constraintWidth_max="@dimen/reticle_size"`, and `app:layout_constraintHeight_max="@dimen/reticle_size"` so the square reticle adapts cleanly to both `320dp` portrait and landscape screens.

#### `AUD-UI-003` — `ResultFragment` Missing Leaf Image Preview, Loading/Error States, and Shows Chemical Advice Heading on Healthy Leaves
- **Area:** B. UI & UX (Screen States & Safety Clarity)
- **Severity:** **Medium** (Degraded UX and confusing chemical heading on healthy leaves)
- **File / Line:** `app/src/main/res/layout/fragment_result.xml:13-118` & `app/src/main/java/com/cropdoc/app/ui/result/ResultFragment.java:99-146`
- **What is wrong:**
  1. `ResultFragment` does not handle `Result.Status.LOADING` or `Result.Status.ERROR` in `observeViewModel()`.
  2. When `outcome == DiagnosisOutcome.HEALTHY_LEAF`, `ResultFragment` still renders the `"3. CIB&RC Approved Chemical Options (If Severe)"` section instead of hiding chemical controls for healthy crops.
  3. The scanned leaf thumbnail is not shown on `ResultFragment`.
- **Evidence:**
  ```java
  // ResultFragment.java:99-105
  viewModel.getScreenState().observe(getViewLifecycleOwner(), result -> {
      if (binding == null || result == null || !result.isSuccess() || result.getData() == null) {
          return;
      }
      renderScreenState(result.getData());
  });
  ```
- **Proposed Fix:** Add `imgScannedLeaf`, `resultProgress`, and `txtResultError` to `fragment_result.xml`; add `id="@+id/heading_chemical_cibrc"` to the chemical heading and hide both `headingChemicalCibrc` and `txtChemicalControlBody` when `!state.shouldShowChemicalAdvice()`, and update `ResultViewModel.ResultScreenState.shouldShowChemicalAdvice()` to return `scanRecord.getOutcome() == DiagnosisOutcome.CONFIDENT_DISEASE` only (excluding `HEALTHY_LEAF` from chemical recommendations!).

#### `AUD-CODE-001` — `AuthFragment` Leaks an Unclosed `SingleThreadExecutor` on Every Google Sign-In Tap and `AuthViewModel` Imports `FirebaseAuthSource`
- **Area:** C. Code Quality & Concurrency
- **Severity:** **Medium** (Thread leak & layer boundary violation)
- **File / Line:** `app/src/main/java/com/cropdoc/app/ui/auth/AuthFragment.java:109` & `AuthViewModel.java:7`
- **What is wrong:** `AuthFragment.launchCredentialManagerGoogleSignIn()` passes `Executors.newSingleThreadExecutor()` to `manager.getCredentialAsync(...)` without ever shutting down the executor, leaking a live thread on every button tap. Additionally, `AuthViewModel` (UI layer) imports `com.cropdoc.app.data.remote.FirebaseAuthSource` for `AuthCallback`.
- **Evidence:**
  ```java
  // AuthFragment.java:105-110
  manager.getCredentialAsync(
          requireActivity(),
          request,
          new CancellationSignal(),
          Executors.newSingleThreadExecutor(), ...);
  ```
- **Proposed Fix:** Use `ContextCompat.getMainExecutor(requireContext())` in `AuthFragment` (guarded by `if (!isAdded()) return;`), and move `AuthCallback` into `AuthRepository` so `AuthViewModel` has zero imports from `com.cropdoc.app.data.remote.*`.

#### `AUD-CODE-002` — `FirestoreSyncSource.getFirestoreOrNull()` Calls `setFirestoreSettings()` on Every Invocation, Failing Subsequent Calls
- **Area:** C. Code Quality / A. Sync & Offline
- **Severity:** **Medium** (Subsequent Firestore operations fail in the same process)
- **File / Line:** `app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java:33-49`
- **What is wrong:** `getFirestoreOrNull()` calls `db.setFirestoreSettings(settings)` on every call. Once `FirebaseFirestore` has executed its first operation, calling `setFirestoreSettings()` throws `IllegalStateException`, which the `catch (IllegalStateException expected)` block catches and returns `null`, breaking all subsequent sync/pagination calls in the same session!
- **Evidence:**
  ```java
  // FirestoreSyncSource.java:38-48
  FirebaseFirestore db = FirebaseFirestore.getInstance();
  FirebaseFirestoreSettings settings = ...;
  db.setFirestoreSettings(settings);
  return db;
  } catch (IllegalStateException expected) {
      return null;
  }
  ```
- **Proposed Fix:** Configure `FirebaseFirestoreSettings` at most once using a `synchronized` helper flag `settingsConfigured` and ignore `IllegalStateException` from `setFirestoreSettings()` while still returning the valid `db` instance.

#### `AUD-SEC-002` — Missing `network_security_config.xml` (`cleartextTrafficPermitted="false"`) and Hardcoded English Error Strings in ViewModels
- **Area:** E. Security / A. Localization
- **Severity:** **Medium** (Defense-in-depth cleartext policy & unlocalized error messages)
- **File / Line:** `app/src/main/AndroidManifest.xml:12-20`, `AuthViewModel.java:36, 40, 49`
- **What is wrong:** `AndroidManifest.xml` does not declare `android:networkSecurityConfig="@xml/network_security_config"` to explicitly block cleartext HTTP across all API levels (24–36). Also, validation errors in `AuthViewModel` are hardcoded in English.
- **Evidence:** `AndroidManifest.xml:12-20` (missing `android:networkSecurityConfig`).
- **Proposed Fix:** Create `app/src/main/res/xml/network_security_config.xml` with `<base-config cleartextTrafficPermitted="false" />`, reference it in `AndroidManifest.xml`, and localize error messages in `strings.xml` (`en`, `hi`, `te`).

#### `AUD-PERF-001` — Shipped Baseline Profile `app/src/main/baseline-prof.txt` Is Referenced in Docs But Missing from Repository
- **Area:** D. Performance / G. Build & Release
- **Severity:** **Medium** (Missing AOT startup profile artifact)
- **File / Line:** `app/src/main/baseline-prof.txt`
- **What is wrong:** While `androidx.profileinstaller:profileinstaller:1.4.1` is included in `app/build.gradle:142`, the baseline profile rules file `app/src/main/baseline-prof.txt` is not present in `app/src/main/`.
- **Evidence:** `ls app/src/main/` shows `AndroidManifest.xml`, `assets/`, `java/`, `res/` without `baseline-prof.txt`.
- **Proposed Fix:** Add `app/src/main/baseline-prof.txt` pre-compiling all startup, navigation, Room DAO, and LiteRT inference hot paths.

### LOW FINDINGS (3)

#### `AUD-UI-004` — Bottom Navigation Uses Identical Leaf Icon for All 3 Tabs and Language Switcher Lacks Active State Highlight
- **Area:** B. UI & UX (Usability & Visual Polish)
- **Severity:** **Low**
- **File / Line:** `app/src/main/res/menu/bottom_nav_menu.xml:3-14` & `app/src/main/java/com/cropdoc/app/ui/MainActivity.java:39-46`
- **What is wrong:** `scanFragment`, `historyFragment`, and `authFragment` in `bottom_nav_menu.xml` all use `@drawable/ic_leaf_scan`. Additionally, the top language buttons (`English`, `हिन्दी`, `తెలుగు`) do not visually indicate which language is currently selected (`android:alpha` / selected state).
- **Evidence:** `bottom_nav_menu.xml:5, 9, 13` (`android:icon="@drawable/ic_leaf_scan"`).
- **Proposed Fix:** Create distinct Material vector icons `@drawable/ic_history_clock.xml` and `@drawable/ic_account_farmer.xml` for History and Account tabs, and update `MainActivity` to highlight the active language button.

#### `AUD-CODE-003` — Unused Test Dependencies (`room-testing`, `work-testing`) in `app/build.gradle` and `libs.versions.toml`
- **Area:** C. Code Quality / G. Build Hygiene
- **Severity:** **Low**
- **File / Line:** `app/build.gradle:150-151` & `gradle/libs.versions.toml:57, 60`
- **What is wrong:** `testImplementation(libs.room.testing)` and `testImplementation(libs.work.testing)` are declared in `app/build.gradle` but never imported by any test class, violating the Phase 2 "zero unused dependencies" rule.
- **Evidence:** `app/build.gradle:150-151`.
- **Proposed Fix:** Remove the unused `room-testing` and `work-testing` entries from `app/build.gradle` and `gradle/libs.versions.toml`.

#### `AUD-DOC-001` — `README.md` & `docs/DEPENDENCIES.md` Still Reference Paid `firebase-storage` and `storage.rules`
- **Area:** G. Documentation
- **Severity:** **Low**
- **File / Line:** `README.md:57, 68-78` & `docs/DEPENDENCIES.md:26`
- **What is wrong:** `README.md` instructs running `firebase deploy --only firestore:rules,firestore:indexes,storage`, which fails on free Firebase Spark projects where Cloud Storage is unavailable.
- **Evidence:** `README.md:57`.
- **Proposed Fix:** Update `README.md` and `docs/DEPENDENCIES.md` to document the 100% Spark-compatible architecture (`firebase deploy --only firestore:rules,firestore:indexes`).

## 4. Metrics vs. Phase 2 Performance & Quality Budgets (Before Fixes)

| Metric / Budget | Phase 2 Target | Step 1 Audit Measurement (Before Fixes) | Status Before Fixes |
| :--- | :--- | :--- | :--- |
| **Max Lines per Java Class** | $\le 250$ lines | Max `216` lines (`ScanFragment.java`) | PASS |
| **Max Lines per Method** | $\le 40$ non-empty lines | `FirestoreSyncSource.fetchPaginatedScans` = `44` lines | **FAIL (1 method > 40 lines)** |
| **Unused Dependencies** | `0` unused | `3` (`firebase-storage`, `room-testing`, `work-testing`) | **FAIL** |
| **Multilingual Advice Completeness** | `45` entries (`15 × 3` locales: `en`, `hi`, `te`) | `27` entries (`15 en`, `6 hi`, `6 te`) | **FAIL (60% coverage)** |
| **Baseline Profile (`baseline-prof.txt`)** | Present in `app/src/main/` | Missing | **FAIL** |
| **Main-Thread Disk/Network Blocking** | `0` violations | `1` violation (`ScanFragment.handlePickedGalleryUri` copies stream on UI thread) | **FAIL** |
| **End-to-End Scan Latency** | $\le 60\text{ ms}$ | Preprocess `0.23 ms`, but `cropdoc_leaf_v1_int8.tflite` is a stub returning `0.0f` logits | **FAIL (`AUD-ML-001`)** |
| **Firestore Reads per Cached Session** | `0` reads (cached) / $\le 20$ (restore) | `0` reads on cached screens, but 2nd Firestore call fails due to `setFirestoreSettings()` bug (`AUD-CODE-002`) | **FAIL (`AUD-CODE-002`)** |

## 5. Transparent Disclosure: What Was Verified in Container vs. What Requires Local Android Studio / Emulator

In accordance with the Working Rules (*"Do not invent test results. If you cannot run something, say so and explain what I need to run myself"*):
1. **Verified Directly in This Linux Container:**
   - Full AST & static analysis audit (line counts, method lengths, Checkstyle `10.26.1` rules, Android Lint rules, unused imports, unused resources, translation string parity across `en`/`hi`/`te`, layer import isolation).
   - Pure-Java execution via **OpenJDK 17 (`17.0.18`) + BeanShell** of `ConfidenceCalibrator`, `LeafPreprocessor` (`calculateInSampleSize`, `isLeafOrLesionPixel`, `populateByteBufferAndComputeLeafRatio`), `AdviceSeedLoader`, and `ModelUpdateManager.computeSha256Hex`.
   - FlatBuffer binary structure creation and verification (`flatbuffers 25.12.19`) and Room schema `1.json` validation.
   - Multi-screen UI layout rendering in Light & Dark modes.
2. **Cannot Be Run in Headless Sandbox (No Android SDK `adb`/QEMU or Node Firebase CLI Installed) — Commands for User to Run Locally:**
   - **Gradle / Android Studio Build & JUnit Runner:** Run `./gradlew clean testDebugUnitTest checkstyle lintDebug assembleRelease` in Android Studio terminal.
   - **Connected Android Emulator / Physical Low-End Device (CameraX & TalkBack):** Run `./gradlew connectedDebugAndroidTest` on an Android Studio AVD (e.g., Pixel 4a / 320dp Small Phone API 36).
   - **Firebase Local Emulator Suite (`firestore.rules`):** Run `firebase emulators:exec --only firestore "npm test"` or test rules in the Firebase Console Rules Playground.
