package com.cropdoc.app.ml.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Before;
import org.junit.Test;

/**
 * Comprehensive Unit and Regression Test Suite for {@link InferenceDecisionPolicy}.
 *
 * Verifies all 8 required safety criteria:
 * 1. Normal, confident predictions for all six supported classes
 * 2. Healthy leaves for rice and groundnut (isolated from disease advice)
 * 3. Unsupported crop leaves (cotton, cassava, maize, tomato, unknown) rejected as OUT_OF_DOMAIN
 * 4. Non-leaf or background inputs rejected by leaf gate as OUT_OF_DOMAIN
 * 5. Low-confidence and ambiguous top-2 predictions rejected as INCONCLUSIVE_LOW_CONFIDENCE
 * 6. Invalid model outputs (NaN, inf, wrong dimension, non-normalised probabilities, degenerate)
 * 7. Model unavailable states
 * 8. Leaf gate unvalidated or missing states fail closed
 * 9. Agronomic boundary guard asserts non-diagnostic outcomes cannot produce disease diagnosis or advice
 */
public final class InferenceDecisionPolicyTest {

    private InferenceDecisionPolicy policy;
    private LeafGateValidationStatus validPassingGate;

    @Before
    public void setUp() {
        policy = new InferenceDecisionPolicy();
        validPassingGate = LeafGateValidationStatus.validatedAndAccepted("Verified leaf detected");
    }

    private float[] makeConfidentLogits(int targetIndex) {
        float[] logits = new float[6];
        for (int i = 0; i < 6; i++) {
            logits[i] = (i == targetIndex) ? 6.0f : 0.5f;
        }
        return logits;
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Confident predictions for all six supported classes
    // -------------------------------------------------------------------------

    @Test
    public void allSixSupportedClasses_confidentPredictions_accepted() {
        assertSupportedClassAccepted(0, 0, "rice_bacterial_leaf_blight", "rice", false);
        assertSupportedClassAccepted(1, 1, "rice_brown_spot", "rice", false);
        assertSupportedClassAccepted(2, 2, "rice_leaf_blast", "rice", false);
        assertSupportedClassAccepted(3, 3, "rice_healthy", "rice", true);
        assertSupportedClassAccepted(4, 16, "groundnut_early_leaf_spot", "groundnut", false);
        assertSupportedClassAccepted(5, 17, "groundnut_healthy", "groundnut", true);
    }

    private void assertSupportedClassAccepted(
            int localIdx, int globalIdx, String classId, String crop, boolean healthy) {
        InferenceDecisionResult r = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(localIdx))
                .setCropContext(crop)
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.ACCEPTED_SUPPORTED_CLASS, r.getOutcomeType());
        assertEquals(localIdx, r.getLocalIndex());
        assertEquals(globalIdx, r.getGlobalIndex());
        assertEquals(classId, r.getClassId());
        assertEquals(crop, r.getCrop());
        assertEquals(healthy, r.isHealthy());
        assertTrue(r.isDiagnostic());
        assertEquals(!healthy, r.isDiseaseDiagnosis());
        if (!healthy) {
            assertEquals(classId, r.requireDiseaseClassId());
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Healthy leaves cannot produce disease advice
    // -------------------------------------------------------------------------

    @Test
    public void healthyLeaves_riceAndGroundnut_cannotProduceDiseaseAdvice() {
        InferenceDecisionResult riceHealthy = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(3))
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertTrue(riceHealthy.isAccepted());
        assertTrue(riceHealthy.isHealthy());
        assertFalse(riceHealthy.isDiseaseDiagnosis());

        try {
            riceHealthy.requireDiseaseClassId();
            fail("Expected IllegalStateException when requesting disease ID on healthy rice");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot retrieve disease diagnosis"));
        }

        InferenceDecisionResult groundnutHealthy = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(5))
                .setCropContext("groundnut")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertTrue(groundnutHealthy.isAccepted());
        assertTrue(groundnutHealthy.isHealthy());
        assertFalse(groundnutHealthy.isDiseaseDiagnosis());

        try {
            groundnutHealthy.requireAgronomicAdviceClassId();
            fail("Expected IllegalStateException when requesting disease advice on healthy groundnut");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Cannot retrieve disease diagnosis"));
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Unsupported crop leaves rejected as OUT_OF_DOMAIN
    // -------------------------------------------------------------------------

    @Test
    public void unsupportedCropLeaves_rejectedAsOutOfDomain_evenWithHighSoftmaxConfidence() {
        String[] unsupportedCrops = {"cotton", "cassava", "corn", "maize", "tomato", "potato", "unknown", ""};

        for (String crop : unsupportedCrops) {
            // Even if model logits strongly resemble Groundnut Early Leaf Spot (99% softmax)
            InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(makeConfidentLogits(4))
                    .setCropContext(crop)
                    .setLeafGateStatus(validPassingGate)
                    .build());

            assertEquals("Crop '" + crop + "' must be rejected as OUT_OF_DOMAIN",
                    PolicyOutcomeType.OUT_OF_DOMAIN, result.getOutcomeType());
            assertEquals(PolicyRejectionDetail.UNSUPPORTED_CROP_DOMAIN, result.getRejectionDetail());
            assertFalse(result.isAccepted());
            assertFalse(result.isDiseaseDiagnosis());

            try {
                result.requireDiseaseClassId();
                fail("Expected IllegalStateException for unsupported crop: " + crop);
            } catch (IllegalStateException expected) {
                // Verified
            }
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Non-leaf or background inputs rejected by leaf gate
    // -------------------------------------------------------------------------

    @Test
    public void nonLeafOrBackgroundInputs_rejectedByLeafGate_asOutOfDomain() {
        LeafGateValidationStatus soilRejected = LeafGateValidationStatus.rejectedNonLeaf(
                "Non-leaf detected: soil and tools");

        // High confidence logits must NOT override leaf gate rejection
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(0))
                .setCropContext("rice")
                .setLeafGateStatus(soilRejected)
                .build());

        assertEquals(PolicyOutcomeType.OUT_OF_DOMAIN, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_REJECTED_NON_LEAF, result.getRejectionDetail());
        assertFalse(result.isAccepted());
        assertFalse(result.isDiseaseDiagnosis());
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Low-confidence and ambiguous top-2 predictions
    // -------------------------------------------------------------------------

    @Test
    public void ambiguousTop2Predictions_rejectedAsInconclusive() {
        // Logits resulting in close top-2 predictions: top1 ~0.45, top2 ~0.42 (margin ~0.03 < 0.35)
        float[] ambiguousLogits = {3.0f, 2.9f, 0.1f, 0.1f, 0.1f, 0.1f};
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(ambiguousLogits)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());

        assertEquals(PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.MARGIN_BELOW_THRESHOLD, result.getRejectionDetail());
        assertFalse(result.isAccepted());
        assertFalse(result.isDiseaseDiagnosis());
    }

    @Test
    public void lowConfidencePredictions_rejectedAsInconclusive() {
        // Flat logits where top-1 is ~0.25 (below 0.60 threshold)
        float[] lowConfLogits = {1.2f, 1.0f, 0.8f, 0.7f, 0.5f, 0.4f};
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(lowConfLogits)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());

        assertEquals(PolicyOutcomeType.INCONCLUSIVE_LOW_CONFIDENCE, result.getOutcomeType());
        assertFalse(result.isAccepted());
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Invalid model outputs (NaN, Inf, wrong dimension, non-normalised, degenerate)
    // -------------------------------------------------------------------------

    @Test
    public void invalidModelOutputs_nanAndInf_rejectedAsInvalidModelOutput() {
        float[] nanLogits = {Float.NaN, 1.0f, 0.5f, 0.2f, 0.1f, 0.1f};
        InferenceDecisionResult rNan = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(nanLogits)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, rNan.getOutcomeType());
        assertEquals(PolicyRejectionDetail.NON_FINITE_VALUES, rNan.getRejectionDetail());

        float[] infLogits = {Float.POSITIVE_INFINITY, 1.0f, 0.5f, 0.2f, 0.1f, 0.1f};
        InferenceDecisionResult rInf = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(infLogits)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, rInf.getOutcomeType());
        assertEquals(PolicyRejectionDetail.NON_FINITE_VALUES, rInf.getRejectionDetail());
    }

    @Test
    public void invalidModelOutputs_wrongDimensions_rejectedAsInvalidModelOutput() {
        // Length 5 (expected 6)
        InferenceDecisionResult r5 = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(new float[]{1f, 2f, 3f, 4f, 5f})
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, r5.getOutcomeType());
        assertEquals(PolicyRejectionDetail.INVALID_CLASS_COUNT, r5.getRejectionDetail());

        // Length 16 (legacy 16-class output)
        InferenceDecisionResult r16 = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(new float[16])
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, r16.getOutcomeType());
        assertEquals(PolicyRejectionDetail.INVALID_CLASS_COUNT, r16.getRejectionDetail());

        // Null / empty
        InferenceDecisionResult rNull = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(null)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, rNull.getOutcomeType());
        assertEquals(PolicyRejectionDetail.OUTPUT_NULL_OR_EMPTY, rNull.getRejectionDetail());
    }

    @Test
    public void invalidModelOutputs_degenerateUniform_rejectedAsInvalidModelOutput() {
        float[] uniformLogits = {0f, 0f, 0f, 0f, 0f, 0f};
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(uniformLogits)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.DEGENERATE_UNIFORM_OUTPUT, result.getRejectionDetail());
    }

    @Test
    public void invalidModelOutputs_nonNormalisedProbabilities_rejectedAsInvalidModelOutput() {
        // Probabilities summing to 0.50 (invalid distribution)
        float[] invalidSumProbs = {0.2f, 0.1f, 0.1f, 0.05f, 0.05f, 0.0f};
        InferenceDecisionResult rSum = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(invalidSumProbs)
                .setIsLogits(false)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, rSum.getOutcomeType());
        assertEquals(PolicyRejectionDetail.INVALID_PROBABILITY_DISTRIBUTION, rSum.getRejectionDetail());

        // Negative probability
        float[] negativeProbs = {-0.1f, 0.5f, 0.3f, 0.1f, 0.1f, 0.1f};
        InferenceDecisionResult rNeg = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(negativeProbs)
                .setIsLogits(false)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.INVALID_MODEL_OUTPUT, rNeg.getOutcomeType());
        assertEquals(PolicyRejectionDetail.INVALID_PROBABILITY_DISTRIBUTION, rNeg.getRejectionDetail());
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Model unavailable states
    // -------------------------------------------------------------------------

    @Test
    public void modelUnavailable_rejectedAsModelUnavailable() {
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(0))
                .setModelAvailable(false)
                .setCropContext("rice")
                .setLeafGateStatus(validPassingGate)
                .build());
        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.MODEL_NOT_AVAILABLE, result.getRejectionDetail());
        assertFalse(result.isAccepted());
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Leaf gate failure or unvalidated gate state
    // -------------------------------------------------------------------------

    @Test
    public void unvalidatedLeafGate_failsClosed_asModelUnavailable() {
        LeafGateValidationStatus unvalidated = LeafGateValidationStatus.unvalidated(
                "Evidence report SHA-256 missing");
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(0))
                .setCropContext("rice")
                .setLeafGateStatus(unvalidated)
                .build());

        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_UNVALIDATED, result.getRejectionDetail());
        assertFalse(result.isAccepted());
    }

    @Test
    public void missingLeafGate_failsClosed_asModelUnavailable() {
        LeafGateValidationStatus notPresent = LeafGateValidationStatus.notPresent();
        InferenceDecisionResult result = policy.evaluate(new InferenceDecisionRequest.Builder()
                .setRawOutput(makeConfidentLogits(0))
                .setCropContext("rice")
                .setLeafGateStatus(notPresent)
                .build());

        assertEquals(PolicyOutcomeType.MODEL_UNAVAILABLE, result.getOutcomeType());
        assertEquals(PolicyRejectionDetail.LEAF_GATE_NOT_PRESENT, result.getRejectionDetail());
        assertFalse(result.isAccepted());
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Non-diagnostic outcomes cannot produce disease diagnosis or advice
    // -------------------------------------------------------------------------

    @Test
    public void boundaryGuard_assertNonDiagnosticOutcomesCannotProduceDiseaseDiagnosisOrAdvice() {
        InferenceDecisionResult[] nonDiagnosticResults = createNonDiagnosticResults();

        for (int i = 0; i < nonDiagnosticResults.length; i++) {
            InferenceDecisionResult res = nonDiagnosticResults[i];
            assertFalse("Result " + i + " must not be accepted", res.isAccepted());
            assertFalse("Result " + i + " must not be diagnostic", res.isDiagnostic());
            assertFalse("Result " + i + " must not be disease diagnosis", res.isDiseaseDiagnosis());

            try {
                res.requireDiseaseClassId();
                fail("Result " + i + " must throw on requireDiseaseClassId");
            } catch (IllegalStateException expected) {
                // Expected
            }

            try {
                res.requireAgronomicAdviceClassId();
                fail("Result " + i + " must throw on requireAgronomicAdviceClassId");
            } catch (IllegalStateException expected) {
                // Expected
            }
        }
    }

    private InferenceDecisionResult[] createNonDiagnosticResults() {
        return new InferenceDecisionResult[]{
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(makeConfidentLogits(0))
                    .setModelAvailable(false)
                    .build()),
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(new float[]{Float.NaN, 1f, 1f, 1f, 1f, 1f})
                    .build()),
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(makeConfidentLogits(4))
                    .setCropContext("cotton") // unsupported crop
                    .setLeafGateStatus(validPassingGate)
                    .build()),
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(makeConfidentLogits(0))
                    .setCropContext("rice")
                    .setLeafGateStatus(LeafGateValidationStatus.rejectedNonLeaf("Soil"))
                    .build()),
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(new float[]{1f, 1f, 1f, 1f, 1f, 1f}) // degenerate uniform
                    .build()),
            policy.evaluate(new InferenceDecisionRequest.Builder()
                    .setRawOutput(new float[]{3.0f, 2.9f, 0.1f, 0.1f, 0.1f, 0.1f}) // ambiguous
                    .setCropContext("rice")
                    .setLeafGateStatus(validPassingGate)
                    .build())
        };
    }
}
