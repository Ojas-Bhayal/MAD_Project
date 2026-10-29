# CropDoc — Fail-Closed Inference Decision Policy: Implementation Report

**Status:** Completed and Verified  
**Date:** October 24, 2026  
**Auditor / Engineer:** CropDoc Safety & ML Evaluation Team  
**Blast Radius:** Isolated Research Layer (Zero modifications to Android production models, labels, or `classes_v2.yaml`)  
**Test Suite:** `com.cropdoc.app.ml.policy.InferenceDecisionPolicyTest` — **100% Passing**  

---

## 1. Executive Summary & Architecture Overview

CropDoc requires strict agronomic safety guardrails: a machine learning model must **never** output a disease diagnosis, persist a scan record, or generate pesticide/chemical recommendations on out-of-domain images, unsupported crop leaves, non-leaf inputs, or ambiguous predictions.

We implemented an isolated, fail-closed inference decision layer consisting of:
- **Java Component:** `com.cropdoc.app.ml.policy.InferenceDecisionPolicy` (with strongly-typed POJOs, builders, and enums).
- **Python Bridge & Harness:** `ml/scripts/calibrate_and_benchmark_policy.py` for reproducible offline calibration and benchmarking against PyTorch checkpoints.
- **Isolated Research Taxonomy:** `com.cropdoc.app.ml.policy.SixClassResearchMapping`, completely decoupled from the app's frozen 16/22-class production taxonomy.

```
+----------------------------------------------------------------------------------------------------+
|                                    INFERENCE INPUT TENSORS                                         |
| Raw Logits / Probabilities  |  Model Availability  |  LeafGate Status  |  Crop Domain Context      |
+----------------------------------------------------------------------------------------------------+
                                                  │
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                              STAGE 1: MODEL AVAILABILITY CHECK                                     |
| Is model initialized and available?  ──► [NO] ──► MODEL_UNAVAILABLE (MODEL_NOT_AVAILABLE)          |
+----------------------------------------------------------------------------------------------------+
                                                  │ [YES]
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                          STAGE 2: STRUCTURAL & NUMERICAL INTEGRITY                                 |
| Shape == 6? Finite (no NaN/Inf)? Non-degenerate? ──► [FAIL] ──► INVALID_MODEL_OUTPUT                |
+----------------------------------------------------------------------------------------------------+
                                                  │ [PASS]
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                         STAGE 3: SEMANTIC LEAF GATE VALIDATION (G2)                                |
| Gate certified & present?  ──► [NO] ──► MODEL_UNAVAILABLE (LEAF_GATE_UNVALIDATED)                  |
| Gate decision == ACCEPT?   ──► [NO] ──► OUT_OF_DOMAIN (LEAF_GATE_REJECTED_NON_LEAF)                |
+----------------------------------------------------------------------------------------------------+
                                                  │ [PASS]
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                           STAGE 4: CROP DOMAIN VERIFICATION (G1, G3)                               |
| Is crop in {"rice", "groundnut"}? ──► [NO] ──► OUT_OF_DOMAIN (UNSUPPORTED_CROP_DOMAIN)              |
| OOD detector score > 0.50?         ──► [YES] ──► OUT_OF_DOMAIN (OOD_SCORE_EXCEEDED)                |
+----------------------------------------------------------------------------------------------------+
                                                  │ [PASS]
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                      STAGE 5: DISTRIBUTIONAL CALIBRATION & MARGIN GATING                           |
| Top-2 Margin < 0.35?               ──► [YES] ──► INCONCLUSIVE_LOW_CONFIDENCE (MARGIN_BELOW)        |
| Top-1 Confidence < 0.60?           ──► [YES] ──► INCONCLUSIVE_LOW_CONFIDENCE (CONF_BELOW)          |
| Normalized Entropy > 0.55?         ──► [YES] ──► INCONCLUSIVE_LOW_CONFIDENCE (ENTROPY_ABOVE)       |
+----------------------------------------------------------------------------------------------------+
                                                  │ [ALL PASS]
                                                  ▼
+----------------------------------------------------------------------------------------------------+
|                                     ACCEPTED_SUPPORTED_CLASS                                       |
| Confident in-domain prediction (Rice / Groundnut), verified disease or healthy status.             |
+----------------------------------------------------------------------------------------------------+
```

---

## 2. Decision Policy Outcome Contract

The policy explicitly produces five mutually exclusive outcomes:

| Outcome Type | Description | Agronomic Action |
|:---|:---|:---|
| `ACCEPTED_SUPPORTED_CLASS` | Verified in-domain specimen with passing leaf gate, valid crop domain, and unambiguous margin. | Valid for domain display. Disease classes can look up ICAR/CABI advice; healthy classes show asymptomatic status. |
| `INCONCLUSIVE_LOW_CONFIDENCE` | Prediction lacks sufficient confidence ($p_1 < 0.60$), has ambiguous top-2 margin ($\Delta < 0.35$), or high entropy ($H > 0.55$). | Non-diagnostic status screen. Prompts farmer to retake scan under better lighting/focus. Zero advice rendered. |
| `OUT_OF_DOMAIN` | Input rejected by leaf gate (non-leaf/soil/hand) or belongs to an unsupported crop (cotton, cassava, maize). | Rejection notice. Directs farmer to scan only supported crops (Rice, Groundnut). Zero advice rendered. |
| `INVALID_MODEL_OUTPUT` | Corrupt tensor, wrong class count, NaN/Inf values, or degenerate uniform distribution. | Safety fault logged. Zero advice rendered. |
| `MODEL_UNAVAILABLE` | Model uninitialized or leaf gate unvalidated/missing. | Fail closed. Model download/validation prompt. Zero advice rendered. |

---

## 3. Fail-Closed Guarantees & Agronomic Boundary Guard

To prevent silent safety leaks where non-diagnostic results trigger disease diagnosis or chemical treatment recommendations, `InferenceDecisionResult` provides programmatic boundary guards:

1. **Strict Type Separation:**
   - `isAccepted()` returns `true` **only** for `ACCEPTED_SUPPORTED_CLASS`.
   - `isDiagnostic()` returns `true` **only** for `ACCEPTED_SUPPORTED_CLASS`.
   - `isDiseaseDiagnosis()` returns `true` **only** when `isAccepted() && !isHealthy()`.

2. **Guarded Advice & Diagnosis Accessors:**
   ```java
   public String requireDiseaseClassId() {
       if (!isDiseaseDiagnosis() || classId == null) {
           throw new IllegalStateException(
               "Agronomic Boundary Guard: Cannot retrieve disease diagnosis for non-disease outcome: "
               + outcomeType + " (" + rejectionDetail + ")");
       }
       return classId;
   }
   ```
   Calling `requireDiseaseClassId()` or `requireAgronomicAdviceClassId()` on any non-diagnostic outcome (or on an asymptomatic healthy leaf) throws an immediate `IllegalStateException`.

3. **Prevention of High-Softmax Unsupported Crop Leaks:**
   - Standard neural network classification computes softmax:
     $$p_i = \frac{e^{z_i}}{\sum_{j=1}^K e^{z_j}}$$
   - In a closed-world 6-class model, out-of-domain images (such as cotton bacterial blight or cassava mosaic) can achieve over 95% softmax confidence on groundnut leaf spot merely because their features project slightly closer to that class than the other five classes.
   - The decision policy enforces that **high softmax confidence alone is strictly insufficient for disease diagnosis**. Stage 4 explicitly verifies that the specimen's crop domain is within `{"rice", "groundnut"}`. Any unsupported crop leaf is rejected as `OUT_OF_DOMAIN` before confidence or margin can be accepted.

4. **Leaf Gate Fail-Closed Invariant (G2):**
   - If the semantic leaf gate is missing (`LEAF_GATE_NOT_PRESENT`) or unvalidated (`LEAF_GATE_UNVALIDATED`), the policy rejects with `MODEL_UNAVAILABLE`.
   - If the leaf gate detects non-leaf objects (soil, hands, clothing, tools), the policy rejects with `OUT_OF_DOMAIN` (`LEAF_GATE_REJECTED_NON_LEAF`), preventing foliar logit synthesis on non-leaves.

---

## 4. Integration Boundary with Android Architecture

In CropDoc's production architecture, `AnalyzeLeafUseCase` enforces a single boundary check before writing to Room database (`ScanRepository`) or querying offline advice (`AdviceRepository`):

```java
// Existing AnalyzeLeafUseCase single boundary check:
if (!(outcome instanceof ClassificationOutcome.Diagnosis)) {
    return new AnalysisOutput(outcome, null, null);
}
```

The new `InferenceDecisionPolicy` integrates seamlessly as the decision arbiter:
- The classifier executes the model and constructs an `InferenceDecisionRequest`.
- `InferenceDecisionPolicy.evaluate(request)` evaluates all 5 safety stages.
- Only if `result.isDiseaseDiagnosis()` is true can a `ClassificationOutcome.Diagnosis` be instantiated.
- All non-diagnostic outcomes map directly to `ClassificationOutcome.Rejected`, `ClassificationOutcome.LowConfidence`, or `ClassificationOutcome.ModelUnavailable`.
- The production app code and 22-class taxonomy remains 100% frozen and untouched until the research model is fully certified and converted.

---

## 5. Implementation Artifacts

| Component | Path | Description |
|:---|:---|:---|
| Outcome Enum | `app/src/main/java/com/cropdoc/app/ml/policy/PolicyOutcomeType.java` | 5 strongly-typed mutually exclusive outcomes |
| Rejection Details | `app/src/main/java/com/cropdoc/app/ml/policy/PolicyRejectionDetail.java` | Detailed diagnostic rejection codes |
| Gate Status | `app/src/main/java/com/cropdoc/app/ml/policy/LeafGateValidationStatus.java` | Encapsulates presence, validation, and gate decision |
| Research Taxonomy | `app/src/main/java/com/cropdoc/app/ml/policy/SixClassResearchMapping.java` | Isolated 6-class research metadata and crop mappings |
| Policy Config | `app/src/main/java/com/cropdoc/app/ml/policy/InferencePolicyConfig.java` | Calibrated threshold constants |
| Request POJO | `app/src/main/java/com/cropdoc/app/ml/policy/InferenceDecisionRequest.java` | Fluent builder for inference evaluation |
| Result POJO | `app/src/main/java/com/cropdoc/app/ml/policy/InferenceDecisionResult.java` | Strongly typed result with agronomic boundary guards |
| Decision Policy | `app/src/main/java/com/cropdoc/app/ml/policy/InferenceDecisionPolicy.java` | 11-stage fail-closed evaluation engine |
| Regression Test Suite | `app/src/test/java/com/cropdoc/app/ml/policy/InferenceDecisionPolicyTest.java` | 9 test suites covering all required scenarios |
| Python Evaluation Harness | `ml/scripts/calibrate_and_benchmark_policy.py` | Python calibration and benchmarking script |
