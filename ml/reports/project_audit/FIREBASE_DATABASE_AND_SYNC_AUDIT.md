# CropDoc — Local Database Storage & Firebase Firestore Synchronization Audit

**Audit Date:** October 2026  
**Target Repository:** `C:\Users\ojas\OneDrive\Desktop\MajorProjectMAD\CropDoc`  
**Application ID:** `com.cropdoc.app`  
**Firebase Project ID:** `cropdoc-india-prod`  
**Audit Type:** Static Code Inspection, Architectural Tracing, Security Assessment, and Manual Verification Design  

---

## 1. Executive Summary & Core Verdict

This audit provides a comprehensive, evidence-based investigation into how application data is persisted locally and synchronized to the cloud within the **CropDoc** Android application.

### Key Findings:

1. **Dual Storage Architecture (Offline-First):**
   - **Local Database (Room 2.8.5 / SQLite):** Acts as the **primary, authoritative single source of truth** for all diagnosis and offline agronomic advice. Scan records are stored in `cropdoc_offline.db` within the `scans` table.
   - **Cloud Database (Firebase Firestore BOM 35.0.0):** Acts as a **secondary, deferred synchronization sink**. It synchronizes lightweight scan metadata and compact Base64 thumbnails for authenticated accounts.
   - **Zero Cloud Storage Cost (Spark Tier):** The application does **not** use Firebase Cloud Storage. Instead, it generates a micro-thumbnail ($< 12$ KB) and writes it directly to Firestore within the free Spark quota.

2. **Strict Boundary Guard (Fail-Closed Persistence):**
   - In [`AnalyzeLeafUseCase.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/domain/usecase/AnalyzeLeafUseCase.java#L93-L98), a single boundary guard ensures **ONLY** verified `ClassificationOutcome.Diagnosis` instances are persisted to Room or scheduled for sync.
   - Non-diagnostic outcomes (`Rejected`, `ModelUnavailable`, `LowConfidence`) **never touch the database, disk history, WorkManager, or Firestore**.

3. **Production State & Current Runtime Behavior:**
   - The bundled production model asset (`cropdoc_leaf_v1_int8.tflite`) is a **16.8 KB placeholder**.
   - Load-time cryptographic validation in [`ModelValidator.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ml/ModelValidator.java) detects this and fails closed.
   - Consequently, in normal production builds, **0 records are written to Room**, **0 WorkManager sync jobs are enqueued**, and **0 documents are created in Firestore**. The UI displays a Snackbar: *"Diagnosis is currently unavailable. A verified model update is required."*
   - The database and synchronization machinery is **fully implemented and verified via unit tests**, but remains dormant at runtime pending a genuine OTA or bundled model release.

4. **Security & DPDP Compliance:**
   - Security rules in [`firebase/firestore.rules`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/firebase/firestore.rules) enforce strict per-user authorization (`request.auth.uid == userId`) and payload schema validation. Unauthenticated users cannot write to Firestore.
   - A 1-tap **DPDP Act data erasure** workflow is implemented, deleting local image files, Room database records, and Firestore cloud documents in a single atomic action.

---

## 2. Complete End-to-End Data Flow

The following sequence illustrates the complete lifecycle of a leaf scan from image acquisition to cloud synchronization:

```mermaid
sequenceDiagram
    autonumber
    actor Farmer as User (Farmer)
    participant UI as ScanFragment / ScanViewModel
    participant UC as AnalyzeLeafUseCase
    participant ML as LiteRtLeafClassifier
    participant Sanitizer as ExifPrivacySanitizer
    participant Repo as ScanRepository
    participant Room as CropDocDatabase (Room/SQLite)
    participant WM as WorkManager / ScanSyncWorker
    participant Cloud as Firebase Firestore

    Farmer->>UI: Captures Camera Photo or Selects Gallery Leaf
    UI->>Sanitizer: Preprocesses image & strips GPS/Camera EXIF tags
    UI->>UC: analyzeLeafUseCase.execute(bitmap, localPath, lang)
    UC->>ML: classifier.classify(bitmap)
    
    alt Model Unavailable / Rejected / Low Confidence
        ML-->>UC: ModelUnavailable / Rejected / LowConfidence
        UC-->>UI: AnalysisOutput(outcome, null, null)
        Note over Room,Cloud: Single Boundary Guard: Zero DB inserts, Zero WorkManager syncs
        UI-->>Farmer: Shows Snackbar ("Diagnosis unavailable" / "Rejected")
    else Confirmed Diagnosis Outcome
        ML-->>UC: ClassificationOutcome.Diagnosis(classId, confidence, etc.)
        UC->>Repo: saveScanSync(userId, domainDiagnosis, localImagePath)
        Repo->>Room: scanDao.insertOrReplace(ScanEntity [synced=0])
        
        alt Guest User (guest_offline_farmer)
            Note over Repo,WM: Background sync skipped for offline guest
        else Authenticated User
            Repo->>WM: enqueueUniqueWork("cropdoc_scan_sync_work", KEEP, OneTimeWorkRequest)
        end
        
        UC-->>UI: AnalysisOutput(outcome, savedRecord, localizedAdvice)
        UI-->>Farmer: Navigates to ResultFragment (Shows diagnosis & ICAR/CABI advice)
    end

    Note over WM: Background Async Execution (Constraints: Network Connected + Battery Not Low)
    WM->>Repo: syncPendingBatch(currentUserId)
    Repo->>Room: scanDao.findUnsyncedBatch(userId, 10)
    Room-->>Repo: List<ScanEntity> (synced=0)
    Repo->>Sanitizer: encodeCompactBase64Thumbnail(localJpegFile)
    Sanitizer-->>Repo: Base64 data URI (<12 KB)
    Repo->>Cloud: uploadScanBatch(userId, readyForFirestore)
    Cloud-->>Repo: Batch commit acknowledged
    Repo->>Room: scanDao.markSynced(scanId, remoteThumbUrl) [synced=1]
```

### Detailed Trace of Each Stage:

1. **User Initiation:**
   The user taps capture in [`ScanFragment.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/scan/ScanFragment.java#L170-L193) or selects an image from the gallery. CameraX writes to a temporary file in `cacheDir`.
2. **Preprocessing & Privacy Stripping:**
   [`ScanViewModel.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/scan/ScanViewModel.java#L122-L147) executes on background threads (`diskIo` & `mlInference`). The image is sampled, square-cropped to the reticle box, and saved via [`ExifPrivacySanitizer.saveSanitizedLeafJpeg`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java#L30-L53) to `context.getFilesDir() / "scans" / "leaf_<timestamp>.jpg"`. All GPS coordinates, camera serial numbers, and EXIF metadata are stripped.
3. **Inference Execution:**
   [`AnalyzeLeafUseCase.execute(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/domain/usecase/AnalyzeLeafUseCase.java#L87-L98) invokes `classifier.classify(reticleBitmap)`.
4. **Boundary Guard Decision:**
   [`AnalyzeLeafUseCase.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/domain/usecase/AnalyzeLeafUseCase.java#L96-L98) evaluates:
   ```java
   if (!(outcome instanceof ClassificationOutcome.Diagnosis)) {
       return new AnalysisOutput(outcome, null, null);
   }
   ```
   If the outcome is `ModelUnavailable`, `Rejected`, or `LowConfidence`, the method halts immediately.
5. **Local Room Insertion:**
   For a `ClassificationOutcome.Diagnosis`, [`ScanRepository.saveScanSync(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L59-L83) generates a UUID (`scanId`), constructs a [`ScanEntity`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/local/entity/ScanEntity.java#L68-L93) with `synced = false`, and executes `scanDao.insertOrReplace(entity)`.
6. **WorkManager Scheduling:**
   In `ScanRepository.java`, if `!FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)`, [`enqueueBackgroundSync()`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L163-L178) builds a `OneTimeWorkRequest` for [`ScanSyncWorker.class`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/sync/ScanSyncWorker.java) requiring `NetworkType.CONNECTED` and `RequiresBatteryNotLow(true)`. It is enqueued uniquely using `ExistingWorkPolicy.KEEP`.
7. **Worker Execution:**
   When Android OS satisfies constraints, [`ScanSyncWorker.doWork()`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/sync/ScanSyncWorker.java#L24-L33) resolves the current user ID via [`AuthRepository.getCurrentUserId()`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/AuthRepository.java#L27-L29) and calls `scanRepository.syncPendingBatch(userId)`.
8. **Batch Upload & Thumbnail Generation:**
   In [`ScanRepository.syncPendingBatch(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L125-L161), the repository queries `scanDao.findUnsyncedBatch(userId, 10)`. For each record, it compresses the local leaf file into a 96x96 Base64 JPEG data URI ($< 12$ KB) and delegates to [`FirestoreSyncSource.uploadScanBatch(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L56-L86).
9. **Firestore Batch Write:**
   `FirestoreSyncSource` initiates a Firestore `WriteBatch`, targeting `/users/{userId}/scans/{scanId}` and optional `/users/{userId}/feedback/{scanId}`. It commits with a 15-second timeout via `Tasks.await(...)`.
10. **Local State Update:**
    Upon commit success, [`scanDao.markSynced(scanId, remoteThumbUrl)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/local/dao/ScanDao.java#L42-L43) sets `synced = 1`. If the network fails, `ScanSyncWorker` returns `Result.retry()`, and WorkManager backs off exponentially.
11. **UI Presentation:**
    `HistoryFragment` observes `scanDao.observeRecentScans(userId, 20)` via `LiveData`. It loads records purely from SQLite, incurring **0 Firestore reads**.

---

## 3. Storage Architecture: Concrete Evidence for Key Questions

### Question 1: Are scan records saved in Room, Firestore, or both?
**Answer: Both, conditionally.**
- **Room (`cropdoc_offline.db`):** Every confirmed diagnosis (`ClassificationOutcome.Diagnosis`) is immediately saved to Room SQLite table `scans` ([`ScanRepository.java#L78`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L78)). This applies to both authenticated and guest users, offline or online. Non-diagnostic outcomes are never saved.
- **Firestore:** Confirmed diagnoses for authenticated users are synchronized to `/users/{userId}/scans/{scanId}` ([`FirestoreSyncSource.java#L67-L71`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L67-L71)).
- **Guest users:** Saved only in Room; never uploaded to Firestore.

### Question 2: Is Room the primary local database?
**Answer: Yes.**
Room 2.8.5 (`CropDocDatabase.java`) is the sole local storage engine and the single source of truth for all UI screens ([`HistoryViewModel.java#L40`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/history/HistoryViewModel.java#L40) and [`ResultViewModel.java#L94`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/result/ResultViewModel.java#L94)).

### Question 3: Is Firestore synchronization automatic, user-triggered, conditional, or currently unused?
**Answer: Automatic and conditional in code, but dormant at runtime due to the placeholder model.**
- **Automatic:** Scheduling is triggered automatically by `saveScanSync` and `recordFeedbackSync`.
- **Conditional:** Requires a confirmed diagnosis, non-guest user, active internet connection, and adequate battery.
- **Dormant in production builds:** Because the bundled model is a placeholder, normal scans produce `ModelUnavailable`, so no scan ever reaches `saveScanSync`.

### Question 4: What event schedules `ScanSyncWorker`?
**Answer: Two distinct events:**
1. A successful leaf diagnosis persisted in [`ScanRepository.saveScanSync(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L80) (when `userId != LOCAL_GUEST_UID`).
2. User submission of helpful/unhelpful feedback in [`ScanRepository.recordFeedbackSync(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L106).

### Question 5: Is the worker actually registered and enqueued with WorkManager?
**Answer: Yes, enqueued dynamically via WorkManager API.**
- In modern Android WorkManager, workers do not require `AndroidManifest.xml` registration; `androidx.work.impl.WorkManagerInitializer` handles provider setup.
- Enqueued via:
  ```java
  WorkManager.getInstance(appContext)
      .enqueueUniqueWork("cropdoc_scan_sync_work", ExistingWorkPolicy.KEEP, request);
  ```
  ([`ScanRepository.java#L173-L174`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L173-L174)).

### Question 6: Which WorkManager constraints are configured?
**Answer:**
- Network: `NetworkType.CONNECTED` ([`ScanRepository.java#L166`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L166))
- Battery: `setRequiresBatteryNotLow(true)` ([`ScanRepository.java#L167`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L167))
- Storage: None configured.
- Backoff: `BackoffPolicy.EXPONENTIAL`, 30 seconds ([`ScanRepository.java#L171`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L171)).

### Question 7: Which Firestore collections or document paths are written?
**Answer:**
1. `/users/{userId}/scans/{scanId}` ([`FirestoreSyncSource.java#L67-L71`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L67-L71))
2. `/users/{userId}/feedback/{scanId}` ([`FirestoreSyncSource.java#L74-L78`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L74-L78))

### Question 8: What data fields are sent to Firestore?
**Answer:**
Written to `/users/{userId}/scans/{scanId}` ([`FirestoreSyncSource.java#L177-L194`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L177-L194)):
- `scanId` (String): UUID
- `userId` (String): Firebase Auth UID
- `cropId` (String): e.g. `"rice"`, `"tomato"`
- `diseaseId` (String): e.g. `"tomato_early_blight"`
- `confidence` (Double): Calibrated score (0.0 to 1.0)
- `outcome` (String): e.g. `"CONFIDENT_DISEASE"`, `"HEALTHY_LEAF"`
- `remoteImageUrl` (String): Base64 data URI string (`"data:image/jpeg;base64,..."`)
- `timestampMs` (Long): Epoch milliseconds
- `modelVersion` (String): e.g. `"1.0.0-int8"`
- `userFeedbackHelpful` (Boolean, optional): Feedback value

Written to `/users/{userId}/feedback/{scanId}` ([`FirestoreSyncSource.java#L197-L207`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L197-L207)):
- `userId`, `scanId`, `helpful`, `modelVersion`, `timestampMs`.

### Question 9: Are raw images uploaded to Firebase Storage or sent to Firestore as Base64 strings?
**Answer: Base64 micro-thumbnails are stored in Firestore; raw images are never sent to cloud.**
- Firebase Cloud Storage is **not** included in dependencies and **never** used.
- Full-resolution photos ($< 80$ KB, 512x512 max) remain strictly on local disk at `context.getFilesDir() + "/scans/leaf_<timestamp>.jpg"`.
- Firestore receives only the compact Base64 thumbnail data URI stored in field `remoteImageUrl`.

### Question 10: Is there an image size limit or compression step to prevent exceeding Firestore's 1 MB limit?
**Answer: Yes, aggressive multi-stage downsampling and compression.**
In [`ExifPrivacySanitizer.encodeCompactBase64Thumbnail(...)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java#L56-L81):
- Maximum dimension: `THUMBNAIL_DIMENSION = 96` pixels (96x96 box).
- JPEG quality: `THUMB_JPEG_QUALITY = 60`.
- Resulting byte payload: 2 KB to 6 KB, yielding a Base64 string of **$< 12$ KB**.
- Firestore document limit is 1,048,576 bytes (1 MB). The 12 KB payload uses **~1.1%** of the document capacity.

### Question 11: How are offline scans handled when there is no network connectivity?
**Answer:**
1. The scan record is saved immediately into Room SQLite with `synced = 0`.
2. Diagnosis and ICAR/CABI agronomic advice are immediately rendered to the user from the local Room database.
3. `ScanSyncWorker` is queued in WorkManager. Because `NetworkType.CONNECTED` is unsatisfied, WorkManager keeps the request in persistent `ENQUEUED` state without failing or dropping it.

### Question 12: When the device comes back online, does the app retry pending uploads?
**Answer: Yes.**
WorkManager's network constraint tracker detects connectivity and triggers `ScanSyncWorker.doWork()`.
1. Queries `scanDao.findUnsyncedBatch(userId, 10)`.
2. Generates thumbnails and uploads the batch to Firestore.
3. Upon commit, marks items `synced = 1`.
4. If the upload encounters an exception, it returns `Result.retry()`, and WorkManager retries with exponential backoff (starting at 30 seconds).
*(Note: A single worker pass processes up to 10 items. Any remaining items are processed on subsequent worker runs).*

### Question 13: Does the app support guest or unauthenticated mode?
**Answer: Yes.**
- [`FirebaseAuthSource.LOCAL_GUEST_UID`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirebaseAuthSource.java#L18) is `"guest_offline_farmer"`.
- Guest scans are **stored locally in Room** under `user_id = "guest_offline_farmer"` and are visible in the History screen.
- Guest scans are **explicitly blocked from syncing to Firestore** ([`ScanRepository.java#L79-L81`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L79-L81) and [`#L126-L128`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L126-L128)).
- If online anonymous auth succeeds (`auth.signInAnonymously()`), the user receives a real Firebase anonymous UID. In that case, Firestore rules permit synchronization because `request.auth != null`.

### Question 14: How does the History screen load past scans? Does it read from Room, Firestore, or both?
**Answer: Exclusively from Room (0 Firestore reads).**
[`HistoryViewModel.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/history/HistoryViewModel.java#L38-L41) calls `scanRepository.observeRecentScans(userId)`, which binds directly to Room DAO query:
```sql
SELECT * FROM scans WHERE user_id = :userId ORDER BY timestamp_ms DESC LIMIT :limit
```
Firestore is never queried by History. (`FirestoreSyncSource.fetchPaginatedScans` is an unused utility method).

### Question 15: Does the History screen work without an internet connection?
**Answer: Yes, 100%.**
Because it reads entirely from local SQLite via Room LiveData, History works completely offline, in airplane mode, or with no network hardware active.

### Question 16: What happens when a scan is deleted? Is it removed from Room, Firestore, or both?
**Answer: Removed from both local storage and cloud via DPDP Act history erasure.**
The app provides a complete history erasure feature (`btnEraseHistoryDpdp`):
1. Deletes local sanitized JPEG files from disk via `File.delete()` ([`ScanRepository.java#L114-L117`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L114-L117)).
2. Deletes all user rows from SQLite via `scanDao.deleteAllForUser(userId)` ([`ScanRepository.java#L121`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L121)).
3. Deletes all cloud documents in `/users/{userId}/scans` and `/users/{userId}/feedback` via batch delete in [`FirestoreSyncSource.deleteAllUserCloudData(userId)`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L88-L114).

### Question 17: Is there any conflict resolution mechanism if the same scan exists in both Room and Firestore?
**Answer: Not required; unidirectional sync with client-side UUID keys.**
- The data flow is unidirectional: Room $\rightarrow$ Firestore.
- Each scan ID is a globally unique client-generated UUID (`UUID.randomUUID().toString()`).
- Room insertion uses `OnConflictStrategy.REPLACE`.
- Firestore batch write uses `WriteBatch.set(...)` with the identical UUID document key.
- Since scans are never modified by the server or downloaded to other devices, no timestamp arbitration or CRDT conflict resolution is necessary.

---

## 4. Production-Model Failure Case Trace

Here is the exact step-by-step trace of what occurs today when a user takes or selects a photo in the normal production Scan tab:

```
[User initiates Scan]
         │
         ▼
[ScanViewModel.analyzeImageFile / analyzeGalleryUri]
         │
         ▼
[ExifPrivacySanitizer strips EXIF & saves local temp image]
         │
         ▼
[AnalyzeLeafUseCase.execute(reticleBitmap, localPath, lang)]
         │
         ▼
[LiteRtLeafClassifier.classify(reticleBitmap)]
         │
         ├─► ensureInterpreterInitialized()
         │        │
         │        ├─► tryInitializeOtaPair() --> No verified OTA model found
         │        │
         │        └─► initializeBundledPair()
         │                 │
         │                 └─► ModelValidator.validateModelWithManifest(...)
         │                          │
         │                          └─► Bundled asset 'cropdoc_leaf_v1_int8.tflite' = 16.8 KB
         │                              (< 500,000 bytes minimum real model threshold)
         │                              Result: INVALID (Placeholder Model)
         │
         └─► checkValidationFailure()
                  │
                  └─► Returns: ClassificationOutcome.ModelUnavailable(PLACEHOLDER_MODEL)
```

### Downstream Consequences:

1. **Inside `AnalyzeLeafUseCase.java` (Lines 96–98):**
   ```java
   if (!(outcome instanceof ClassificationOutcome.Diagnosis)) {
       return new AnalysisOutput(outcome, null, null);
   }
   ```
   The use case halts and returns immediately.
2. **Room Database:**
   `scanRepository.saveScanSync(...)` is **never called**. **0 rows** are written to Room SQLite table `scans`.
3. **WorkManager:**
   `enqueueBackgroundSync()` is **never invoked**. No background job is created.
4. **Firebase Firestore:**
   **0 writes** are attempted. Zero documents are created in Firestore.
5. **User Interface (`ScanFragment.java` Lines 211–212):**
   ```java
   } else if (outcome instanceof ClassificationOutcome.ModelUnavailable) {
       showShortSnackbar(R.string.status_model_unavailable);
   }
   ```
   A Snackbar appears: *"Diagnosis is currently unavailable. A verified model update is required."*
6. **History Screen:**
   When the user opens History, the screen shows the empty state (`layoutEmptyHistory`: *"No scans yet"*). Failed scans do not appear in history.

---

## 5. Firebase Configuration & Security Rules Audit

### Configuration Inspection (`google-services.json`):
- **Project ID:** `cropdoc-india-prod`
- **Project Number:** `876346749628`
- **Package Name:** `com.cropdoc.app`
- **Client Services:** Android client info configured with Google Identity Services (OAuth 2.0 Web & Android client IDs) and AppInvite service.
- **CI/CD Template:** An open template (`app/google-services.json.template`) is maintained for clean CI builds without secret leakage.

### Security Rules Inspection (`firebase/firestore.rules`):
```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    function isSignedIn() {
      return request.auth != null;
    }

    function isOwner(userId) {
      return isSignedIn() && request.auth.uid == userId;
    }

    function isValidOutcome(outcome) {
      return outcome in [
        'CONFIDENT_DISEASE',
        'HEALTHY_LEAF',
        'LOW_CONFIDENCE_RETAKE',
        'NOT_A_SUPPORTED_LEAF'
      ];
    }

    function isValidScanPayload(data) {
      return data.keys().hasAll([
          'scanId', 'userId', 'cropId', 'diseaseId', 'confidence', 'outcome', 'timestampMs'
        ])
        && data.keys().size() <= 12
        && data.scanId is string && data.scanId.size() <= 64
        && data.userId is string
        && data.cropId is string && data.cropId.size() <= 64
        && data.diseaseId is string && data.diseaseId.size() <= 64
        && data.confidence is number && data.confidence >= 0.0 && data.confidence <= 1.0
        && isValidOutcome(data.outcome)
        && data.timestampMs is int && data.timestampMs > 0;
    }

    match /users/{userId}/scans/{scanId} {
      allow read: if isOwner(userId);
      allow create, update: if isOwner(userId)
        && request.resource.data.userId == userId
        && request.resource.data.scanId == scanId
        && isValidScanPayload(request.resource.data);
      allow delete: if isOwner(userId);
    }

    match /users/{userId}/feedback/{feedbackId} {
      allow read: if isOwner(userId);
      allow create, update: if isOwner(userId)
        && request.resource.data.userId == userId
        && request.resource.data.scanId == feedbackId
        && request.resource.data.helpful is bool
        && request.resource.data.modelVersion is string
        && request.resource.data.modelVersion.size() <= 32;
      allow delete: if isOwner(userId);
    }

    match /advice_catalog/{diseaseId} {
      allow read: if true;
      allow write: if false;
    }

    match /{document=**} {
      allow read, write: if false;
    }
  }
}
```

### Security Audit Findings:
1. **Access Control:**
   - Writing to `/users/{userId}/scans/{scanId}` requires `isOwner(userId)` (`request.auth.uid == userId`).
   - Unauthenticated requests (`request.auth == null`) are **strictly blocked**.
   - Cross-user data snooping is prevented; users can only read and write their own documents.
2. **Schema & Payload Validation:**
   - Validates all 7 required fields: `scanId`, `userId`, `cropId`, `diseaseId`, `confidence`, `outcome`, `timestampMs`.
   - String length caps ($\le 64$) prevent document bloat.
   - Outcome is strictly constrained to the 4 whitelisted domain values.
   - Confidence must be bounded within $[0.0, 1.0]$.
3. **App Check Protection:**
   - Configured in [`CropDocApplication.java#L50-L65`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/CropDocApplication.java#L50-L65):
     - Debug builds: `DebugAppCheckProviderFactory.getInstance()`.
     - Release builds: `PlayIntegrityAppCheckProviderFactory.getInstance()`.
4. **Graceful Degradation:**
   - In `CropDocApplication.java`, Firebase initialization is wrapped in a `try-catch` block. If `google-services.json` is missing or initialization fails, the app boots gracefully into offline mode.
   - `FirebaseAuthSource` and `FirestoreSyncSource` safely check for null Firebase instances and fall back to local offline operations.

---

## 6. Concrete Manual Verification Procedure

Since the bundled model fails closed, use the following step-by-step procedure to test and verify local Room persistence, WorkManager execution, and Firestore cloud synchronization.

### Test Setup: Triggering a Diagnostic Outcome
To generate a valid diagnosis for testing, either:
- **Option A (Automated JVM Test Harness):** Run existing `ScanRepositoryTest` to verify Room and mock Firestore sync.
- **Option B (Debug Device / Emulator):** Use a debug build where a verified model is placed in the OTA directory (`context.getFilesDir() / "models" / "active"`), or simulate an authenticated scan via an instrumented test.

### Step 1: Verify Local Record Creation in Room
Connect a physical device or emulator via ADB:

```bash
# 1. Open an interactive SQLite shell into the app's private database
adb shell run-as com.cropdoc.app sqlite3 databases/cropdoc_offline.db

# 2. Query the schema of the scans table
.schema scans

# 3. Inspect recent scan records
SELECT scan_id, user_id, crop_id, disease_id, confidence, outcome, synced, datetime(timestamp_ms/1000, 'unixepoch') AS scan_time FROM scans ORDER BY timestamp_ms DESC LIMIT 5;
```

**Expected Result:**
- A row exists with a non-null `scan_id` (UUID), `user_id` matching the authenticated UID, `confidence >= 0.70`, and `synced = 0`.
- Local image file exists at the path recorded in `local_image_path`:
  ```bash
  adb shell run-as com.cropdoc.app ls -lh files/scans/
  ```

### Step 2: Verify WorkManager Enqueueing & Constraints
Inspect WorkManager jobs queued in Android's JobScheduler:

```bash
# Filter WorkManager logs in real time
adb logcat -v time -s WM-WorkConstraintsTracker WM-GreedyScheduler WM-WorkerWrapper ScanSyncWorker
```

**Dumping WorkManager status via ADB:**
```bash
adb shell dumpsys jobscheduler | grep -A 25 "com.cropdoc.app"
```

**Expected Log Messages:**
- `Work [id=..., com.cropdoc.app.data.sync.ScanSyncWorker] constraints met`
- `WorkerWrapper: Task com.cropdoc.app.data.sync.ScanSyncWorker started`

### Step 3: Test Offline Behavior (Network Disconnected)
Simulate offline conditions:

```bash
# 1. Disable Wi-Fi and Cellular Data on the device
adb shell svc wifi disable
adb shell svc data disable

# 2. Trigger a scan in the app
# (or insert an unsynced row via test hook)

# 3. Check SQLite database
adb shell run-as com.cropdoc.app sqlite3 databases/cropdoc_offline.db "SELECT scan_id, synced FROM scans WHERE synced = 0;"
```

**Expected Result:**
- The record remains `synced = 0`.
- WorkManager logs indicate the job is deferred awaiting `NetworkType.CONNECTED`.

### Step 4: Test Automatic Sync Upon Reconnection
Restore network connectivity:

```bash
# Re-enable Wi-Fi and Cellular Data
adb shell svc wifi enable
adb shell svc data enable

# Monitor Logcat for execution
adb logcat -v time -s ScanSyncWorker FirestoreSyncSource
```

**Expected Log Messages:**
- WorkManager triggers `ScanSyncWorker.doWork()`.
- `ScanSyncWorker` returns `Result.success()`.

### Step 5: Verify Room Record Marked Synced (`synced = 1`)
Check SQLite again immediately following network restoration:

```bash
adb shell run-as com.cropdoc.app sqlite3 databases/cropdoc_offline.db "SELECT scan_id, synced, remote_image_url IS NOT NULL AS has_thumbnail FROM scans WHERE scan_id = '<YOUR_SCAN_ID>';"
```

**Expected Result:**
- `synced` is now `1`.
- `has_thumbnail` is `1` (indicating `remote_image_url` contains the Base64 thumbnail).

### Step 6: Verify Firestore Cloud Document in Web Console
1. Open the Firebase Console in a browser:
   `https://console.firebase.google.com/project/cropdoc-india-prod/firestore`
2. Navigate to collection path:
   `/users/<USER_UID>/scans/<SCAN_ID>`
3. Inspect the document fields:
   - `scanId`: Matches the Room SQLite `scan_id`.
   - `userId`: Matches the Firebase Auth UID.
   - `cropId`: e.g. `"rice"`.
   - `diseaseId`: e.g. `"rice_bacterial_leaf_blight"`.
   - `confidence`: Matches Room float value.
   - `outcome`: `"CONFIDENT_DISEASE"`.
   - `remoteImageUrl`: Starts with `"data:image/jpeg;base64,..."` (length $< 12,000$ characters).
   - `timestampMs`: Matches Room `timestamp_ms`.
   - `modelVersion`: `"1.0.0-int8"`.

### Step 7: Verify DPDP Act Cloud Erasure
1. In the app, navigate to the History tab and tap **"Erase History (DPDP Act)"**.
2. Confirm the dialog.
3. Verify in SQLite:
   ```bash
   adb shell run-as com.cropdoc.app sqlite3 databases/cropdoc_offline.db "SELECT COUNT(*) FROM scans WHERE user_id = '<USER_UID>';"
   ```
   **Expected Result:** `0`.
4. Refresh the Firebase Console at `/users/<USER_UID>/scans`:
   **Expected Result:** The document collection is empty.

---

## 7. Audit Summary Table

| Architectural Feature | Status in Code | Runtime Status in Production Build | Supporting Files |
|---|---|---|---|
| **Local Room Database** | Fully Implemented & Tested | Active (Awaiting valid model output) | [`CropDocDatabase.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/local/CropDocDatabase.java), [`ScanDao.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/local/dao/ScanDao.java) |
| **Boundary Guard** | Fully Implemented & Tested | Active (Fail-closed on placeholder) | [`AnalyzeLeafUseCase.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/domain/usecase/AnalyzeLeafUseCase.java#L93-L98) |
| **WorkManager Sync Worker** | Fully Implemented & Tested | Enqueued only on valid diagnosis | [`ScanSyncWorker.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/sync/ScanSyncWorker.java), [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java) |
| **Firestore Cloud Sync** | Fully Implemented & Tested | Triggered via WorkManager | [`FirestoreSyncSource.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java) |
| **Base64 Micro-Thumbnail** | Fully Implemented & Tested | Active ($< 12$ KB, 96x96 box) | [`ExifPrivacySanitizer.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java#L56-L81) |
| **Offline History Reading** | Fully Implemented & Tested | Active (Reads 100% from Room, 0 cloud reads) | [`HistoryViewModel.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/ui/history/HistoryViewModel.java), [`ScanDao.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/local/dao/ScanDao.java) |
| **Guest Mode Isolation** | Fully Implemented & Tested | Active (Stored in Room, skipped in Firestore) | [`FirebaseAuthSource.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirebaseAuthSource.java), [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L79-L81) |
| **DPDP Cloud & Local Erasure** | Fully Implemented & Tested | Active (1-tap batch erasure) | [`ScanRepository.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/repository/ScanRepository.java#L109-L123), [`FirestoreSyncSource.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/data/remote/FirestoreSyncSource.java#L88-L114) |
| **Firestore Security Rules** | Configured in Repo | Enforced at Firebase backend | [`firebase/firestore.rules`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/firebase/firestore.rules) |
| **Firebase App Check** | Configured in Code | Active (Debug in debug, Play Integrity in prod) | [`CropDocApplication.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/CropDocApplication.java#L50-L65) |
