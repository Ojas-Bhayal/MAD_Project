# CropDoc — Android Architecture and Execution Flow Audit

**Date:** October 24, 2026  
**Auditor:** Senior Android Application Architect  
**Scope:** Complete Architecture, Lifecycle, Concurrency, and Data Flow Audit  

---

## 1. High-Level Architectural Overview

CropDoc follows the **Modern Android Architecture** recommendations, employing a **Single-Activity, Fragment-based MVVM (Model-View-ViewModel)** design with strict separation of concerns across Presentation, Domain, Data, and ML Inference layers. Manual Dependency Injection is orchestrated via `AppContainer`, avoiding annotation processing and reflection overhead at runtime.

```mermaid
flowchart TD
    subgraph Presentation_Layer["Presentation Layer (Jetpack Navigation & MVVM)"]
        MA["MainActivity\n(WindowInsets, Shell, Language Toggle)"]
        SF["ScanFragment\n(CameraX, Reticle Box, Gallery Picker)"]
        RF["ResultFragment\n(Hero Card, Safety Banner, TTS)"]
        HF["HistoryFragment\n(Offline List, DPDP Deletion)"]
        AF["AuthFragment\n(Guest Mode, Credential Manager)"]
        SVM["ScanViewModel"]
        RVM["ResultViewModel"]
        HVM["HistoryViewModel"]
        AVM["AuthViewModel"]
    end

    subgraph Domain_Layer["Domain Layer (Use Cases & Business Logic)"]
        ALU["AnalyzeLeafUseCase\n*Strict Diagnostic Boundary Guard*"]
        DR["Domain Models\n(DiagnosisResult, CropDiseaseAdvice, ScanRecord)"]
    end

    subgraph ML_Inference_Layer["ML & Inference Safety Layer"]
        LRLC["LiteRtLeafClassifier (Production 22-Class)"]
        RIH["ResearchInferenceHarness (Research Isolated)"]
        IQG["ImageQualityGate (Exposure/Dimensions)"]
        VLG["ValidatedLeafGate (Semantic Leaf Validation)"]
        CDG["CropDomainGate (3-Output Rice/Groundnut/Unsupported)"]
        IDP["InferenceDecisionPolicy (Entropy, Margins, Cross-Crop)"]
    end

    subgraph Data_Layer["Data & Persistence Layer (Offline First)"]
        SR["ScanRepository"]
        AR["AdviceRepository"]
        AUR["AuthRepository"]
        RDB[("CropDocDatabase (Room 2.8.5)\nscans, advice_catalog")]
        SSW["ScanSyncWorker (WorkManager 2.12.0)"]
        FAS["FirebaseAuthSource (Guest fallback)"]
        FSS["FirestoreSyncSource (Spark tier batch)"]
        MUM["ModelUpdateManager (OTA models)"]
    end

    MA --> SF & RF & HF & AF
    SF --> SVM --> ALU
    RF --> RVM --> AR & SR
    HF --> HVM --> SR
    AF --> AVM --> AUR & SR
    ALU --> LRLC & AR & SR & AUR
    LRLC --> IQG & VLG
    RIH --> IQG & CDG & IDP
    SR --> RDB & FSS & SSW
    AR --> RDB
    AUR --> FAS
```

---

## 2. Detailed Execution Flows

### 2.1 Leaf Image Acquisition & Preprocessing Flow
1. **Camera Capture or Gallery Pick:**
   - In `ScanFragment`, camera preview is managed via **CameraX 1.6.2** (`Preview`, `ImageCapture`).
   - The user aligns the crop leaf within a square 1:1 reticle box.
   - Alternatively, the user picks an image using the Android Photo Picker (`PickVisualMediaRequest`).
2. **Concurrency & Thread Safety:**
   - `ScanViewModel` enforces an atomic double-tap concurrency guard (`AtomicBoolean analyzing = new AtomicBoolean(false)`) using `compareAndSet(false, true)`.
   - Gallery stream copying runs on `executors.diskIo()`.
   - Bitmap decoding and analysis execute on a dedicated single-threaded executor (`executors.mlInference()`).
3. **Isotropic Cropping & Sanitization:**
   - `LeafPreprocessor.decodeSampledBitmap()` subsamples image decoding based on EXIF rotation (`ExifInterface`).
   - `LeafPreprocessor.extractReticleBitmap()` performs an isotropic square crop matching the camera viewfinder aspect ratio, preventing non-isotropic distortion.
   - `ExifPrivacySanitizer.saveSanitizedLeafJpeg()` strips sensitive GPS/device metadata to satisfy DPDP Act privacy requirements before saving to internal app storage.

---

### 2.2 Inference Gating & Decision Flow

```mermaid
flowchart TD
    IMG["Raw Cropped Leaf Bitmap (224 x 224)"] --> STAGE1["Stage 1: ImageQualityGate\nMean luminance check: 15 <= lum <= 245 lux"]
    STAGE1 -- Under/Overexposed --> REJ1["REJECT (Under/Over-exposed)\n*Shows Snackbar / No Persistence*"]
    STAGE1 -- Sanity Pass --> STAGE2["Stage 2: Leaf Gating\nProduction: LeafGate.getState() == VALIDATED\nResearch: Foliar Spectral Coverage >= 18%"]
    STAGE2 -- Unvalidated / Non-leaf --> REJ2["FAIL-CLOSED (ModelUnavailable / OutOfDomain)\n*Prevents false diagnosis*"]
    STAGE2 -- Leaf Validated --> STAGE3["Stage 3: Crop-Domain Gate (Research Pathway)\nEvaluates Rice vs Groundnut vs Unsupported"]
    STAGE3 -- Unsupported / Uncertain --> REJ3["SHORT-CIRCUIT (Out-of-Domain)\n*Disease Model Never Invoked*"]
    STAGE3 -- Supported Crop --> STAGE4["Stage 4: Six-Class Disease Model\nMobileNetV3 FP32 [1, 6] Logits"]
    STAGE4 --> STAGE5["Stage 5: InferenceDecisionPolicy\nCalibrated Threshold (T >= 0.95), Entropy (<= 0.45),\nMargin (>= 0.15), Cross-Crop Context Guard"]
    STAGE5 -- Inconclusive / Mismatch --> REJ4["REJECT (Inconclusive / Low Confidence)\n*No Advice Fetched*"]
    STAGE5 -- Confident & Valid --> ACC["DIAGNOSIS CONFIRMED\n(Confident Disease or Confident Healthy)"]
```

---

### 2.3 Diagnostic Boundary Guard & Offline Persistence Flow

A critical architectural safeguard in `AnalyzeLeafUseCase` enforces that **only genuine `ClassificationOutcome.Diagnosis` instances may ever trigger database persistence or advice lookup**:

```java
// Strict boundary guard in AnalyzeLeafUseCase.execute():
ClassificationOutcome outcome = classifier.classify(croppedLeafBitmap);

if (!(outcome instanceof ClassificationOutcome.Diagnosis)) {
    // Non-diagnostic results (Rejected, ModelUnavailable, LowConfidence)
    // NEVER touch Room, never save a scan record, and fetch zero advice.
    return new AnalysisOutput(outcome, null, null);
}
```

* **Persistence Contract:**
  - Diagnosed scans are synchronously written to Room (`scans` table) with locally stored sanitized JPEG file paths.
  - Offline advice is loaded from Room (`advice_catalog` table) containing all 63 ICAR/CABI localized recommendations across English, Hindi, and Telugu.
  - If signed in with a Google or Email account, a background `OneTimeWorkRequest` is enqueued for `ScanSyncWorker` with network constraints to sync metadata and compact Base64 thumbnails to Firestore.
  - In Guest Mode, zero network calls occur.

---

### 2.4 Navigation & Screen Lifecycle Management
- **Single Activity Navigation:** `MainActivity` hosts `NavHostFragment` (`nav_graph.xml`).
- **Edge-to-Edge Compatibility:** `WindowInsetsHelper.applyShellInsets()` safely coordinates system bar insets across API levels 24 through 37.
- **Backstack Safety:** When switching tabs via `BottomNavigationView`, transient `ResultFragment` instances are cleared using `navController.popBackStack(R.id.scanFragment, false)`, preventing backstack loops.
- **Language Switching:** `LocaleHelper` updates runtime app locales and re-creates activities smoothly via `AppCompatDelegate.setApplicationLocales()`.
- **ViewBinding Cleanup:** All fragments properly nullify their binding references in `onDestroyView()` to prevent Fragment view memory leaks.
