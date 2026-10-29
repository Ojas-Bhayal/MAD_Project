# Phase I: Database, Authentication, Networking, and Privacy Audit

**Audit Date:** 2026-10-24T23:40:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Mobile Security Architect, Data Privacy & Room Systems Auditor  

---

## 1. Executive Summary: Security & Privacy Posture

The application demonstrates strong compliance with modern mobile security standards and India's Digital Personal Data Protection (DPDP) Act 2023:
1. **Zero Cleartext Networking:** Enforced at the OS level via `app/src/main/res/xml/network_security_config.xml`:
   ```xml
   <network-security-config>
       <base-config cleartextTrafficPermitted="false" />
   </network-security-config>
   ```
2. **FileProvider Path Isolation:**
   - Provider authority: `${applicationId}.fileprovider`.
   - Path mapping (`app/src/main/res/xml/file_paths.xml`): Strictly bounded to the internal app cache directory (`<cache-path name="research_cam" path="." />`). No SD card or external storage paths exposed.
3. **EXIF Metadata Privacy Sanitization:**
   - [`ExifPrivacySanitizer.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/main/java/com/cropdoc/app/util/ExifPrivacySanitizer.java) strips GPS latitude, longitude, altitude, timestamp, camera make/model, and device hardware IDs before saving local leaf JPEG files.
4. **DPDP Act Right to Erasure:**
   - `ScanRepository.deleteAllUserScansSync()` deletes all local database records, purges local JPEG files from internal storage, and removes cloud records from Firestore.

---

## 2. Persistence Layer Audit (Room Database)

- **Database Class:** `com.cropdoc.app.data.local.CropDocDatabase` (Room v2.8.5).
- **Entities:**
  - `ScanEntity`: Local diagnosis records (`scan_id` PK, `user_id`, `crop_id`, `disease_id`, `confidence`, `outcome`, `local_image_path`, `remote_image_url`, `timestamp_ms`, `synced`, `user_feedback_helpful`, `model_version`).
  - `AdviceEntity`: Offline agronomic treatment records (`disease_id` + `language_code` composite PK).
- **Threading & Safety:**
  - All Room queries and transactions are dispatched through `AppExecutors.diskIo()`. Zero database calls touch the Android main UI thread.
  - Room compile-time query verification is active (`schemas` exported to `app/schemas/`).
- **Data Integrity:**
  - `ScanDao` enforces pagination limits (`LIMIT 20`) and user-scoped isolation (`WHERE user_id = :userId`).

---

## 3. Authentication & Identity Audit

- **Authentication Architecture:**
  - Integrates modern AndroidX Credential Manager (`androidx.credentials`) with Google ID Token authentication (`com.google.android.libraries.identity.googleid.GoogleIdTokenCredential`).
  - Legacy, deprecated Google Sign-In SDK is completely avoided.
- **Anonymous Guest Mode:**
  - Provides a frictionless "Guest Mode" (`LOCAL_GUEST_UID = "local_guest"`).
  - Guest scans remain 100% local on the device and are never synced to cloud Firestore collections.
- **Firebase App Check:**
  - Configured in `CropDocApplication`:
    - Release builds: `PlayIntegrityAppCheckProviderFactory` (verifies device integrity and authentic Google Play binary signature).
    - Debug builds: `DebugAppCheckProviderFactory`.

---

## 4. Networking and Cloud Synchronization

- **Sync Architecture:**
  - Background synchronization is managed by `androidx.work.WorkManager` via `ScanSyncWorker`.
  - Constraints: Requires active internet (`NetworkType.CONNECTED`) and healthy battery (`RequiresBatteryNotLow = true`).
  - Free Tier Spark Optimization: Rather than uploading heavy multi-megabyte camera photos, `ExifPrivacySanitizer` downsamples images and compresses 96 px Base64 thumbnails ($< 12$ KB) directly into Firestore documents, incurring zero Cloud Storage costs.
- **Offline Resilience:**
  - The application is fully functional without internet access. Local inference, offline Room history, and offline ICAR/CABI treatment catalogs function identically with or without cloud connectivity.

---

## 5. Security & Privacy Findings

| Finding ID | Severity | File / Component | Risk Description & Consequence | Recommended Remediation |
| :--- | :--- | :--- | :--- | :--- |
| **SEC-01** | **Medium** | `app/google-services.json` | **Committed Firebase Configuration:** `google-services.json` is tracked in the Git repository. While Firebase client keys are meant to be public in client binaries, untrusted forks can trigger quota exhaustion on the Firebase project if unauthenticated access is permitted. | Verify Google Cloud Console API key restrictions: restrict keys by Android package name (`com.cropdoc.app`) and SHA-1 release signing certificate. Add `google-services.json` to `.gitignore` and supply template `google-services.json.template` for open-source contributors. |
| **SEC-02** | **Low** | `CropDocApplication.java` (line 62) | **Swallowed Firebase Initialization Exception:** `initFirebaseAndAppCheckSafely()` catches `Throwable` and ignores it. While intended to allow offline operation, syntax or manifest errors in configuration are suppressed from logcat. | Log a debug warning (`Log.d`) in debug builds when Firebase is uninitialized so developers know why cloud sync is disabled. |
