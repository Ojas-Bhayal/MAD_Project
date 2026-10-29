package com.cropdoc.app.ml;

import androidx.annotation.NonNull;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Mathematically calibrates raw model logits via temperature scaling, computes Shannon entropy,
 * and determines safe agronomic classification outcomes.
 *
 * Enforces strict rejection of non-diagnostic / non-leaf classes (G1).
 */
public final class ConfidenceCalibrator {

    private static final String UNKNOWN_LABEL = "unknown";
    private static final String UNKNOWN_CROP = "unknown";
    private static final float LOG_EPSILON = 1e-12f;
    private static final float MATERIAL_NON_LEAF_PROBABILITY_THRESHOLD = 0.15f;

    private ConfidenceCalibrator() {
        // Utility class
    }

    @NonNull
    public static float[] computeCalibratedProbabilities(
            @NonNull float[] logits,
            float temperature) {
        Objects.requireNonNull(logits, "logits");
        float temp = Math.max(0.01f, temperature);
        float maxLogit = Float.NEGATIVE_INFINITY;
        for (float val : logits) {
            float scaled = val / temp;
            if (scaled > maxLogit) {
                maxLogit = scaled;
            }
        }
        float sumExp = 0f;
        float[] expValues = new float[logits.length];
        for (int i = 0; i < logits.length; i++) {
            float exp = (float) Math.exp((logits[i] / temp) - maxLogit);
            expValues[i] = exp;
            sumExp += exp;
        }
        float[] probabilities = new float[logits.length];
        float safeSum = sumExp > 0f ? sumExp : 1f;
        for (int i = 0; i < logits.length; i++) {
            probabilities[i] = expValues[i] / safeSum;
        }
        return probabilities;
    }

    public static float computeNormalizedEntropy(@NonNull float[] probabilities) {
        Objects.requireNonNull(probabilities, "probabilities");
        if (probabilities.length <= 1) {
            return 0f;
        }
        double entropy = 0.0;
        for (float p : probabilities) {
            if (p > LOG_EPSILON) {
                entropy -= p * Math.log(p);
            }
        }
        double maxEntropy = Math.log(probabilities.length);
        return (float) (maxEntropy > 0.0 ? Math.min(1.0, entropy / maxEntropy) : 0.0);
    }

    @NonNull
    public static ClassificationOutcome evaluateLogitsOutcome(
            @NonNull float[] logits,
            @NonNull List<String> labels,
            @NonNull String modelVersion) {
        return evaluateLogitsOutcome(
                logits,
                labels,
                Collections.singleton(ModelConfig.OOD_CLASS_LABEL),
                modelVersion,
                0L,
                0.0f);
    }

    @NonNull
    public static ClassificationOutcome evaluateLogitsOutcome(
            @NonNull float[] logits,
            @NonNull List<String> labels,
            @NonNull String modelVersion,
            @NonNull Set<String> nonDiagnosticClasses) {
        return evaluateLogitsOutcome(
                logits,
                labels,
                nonDiagnosticClasses,
                modelVersion,
                0L,
                0.0f);
    }

    /**
     * Evaluates model logits with explicit non-diagnostic class rejection (G1) and genuine
     * measured inference metadata propagation (G4).
     */
    @NonNull
    public static ClassificationOutcome evaluateLogitsOutcome(
            @NonNull float[] logits,
            @NonNull List<String> labels,
            @NonNull Set<String> nonDiagnosticClasses,
            @NonNull String modelVersion,
            long inferenceTimeMs,
            float leafPixelRatio) {
        Objects.requireNonNull(logits, "logits");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(nonDiagnosticClasses, "nonDiagnosticClasses");
        Objects.requireNonNull(modelVersion, "modelVersion");

        if (logits.length == 0 || labels.isEmpty()) {
            return new ClassificationOutcome.ModelUnavailable(
                    ClassificationOutcome.UnavailableReason.UNIFORM_OR_DEGENERATE_OUTPUT);
        }

        float[] probs = computeCalibratedProbabilities(logits, ModelConfig.TEMPERATURE_SCALE);
        List<ClassificationOutcome.Candidate> candidates = buildRankedCandidates(probs, labels);
        ClassificationOutcome.Candidate topCandidate = candidates.get(0);
        String topLabel = topCandidate.getClassId();
        float topProb = topCandidate.getConfidence();

        // 1. GAP G1 RULE: If top-1 is in the non-diagnostic set, REJECT IMMEDIATELY.
        if (nonDiagnosticClasses.contains(topLabel)) {
            return new ClassificationOutcome.Rejected(
                    ClassificationOutcome.RejectionReason.NON_LEAF_CLASS);
        }

        // 2. GAP G1 RULE: If any non-diagnostic class is in top-k with material probability,
        // classify as LowConfidence / Uncertain without disease diagnosis.
        if (hasMaterialNonDiagnosticClass(candidates, nonDiagnosticClasses)) {
            return new ClassificationOutcome.LowConfidence(
                    candidates,
                    ClassificationOutcome.LowConfidenceReason.CLOSE_TOP_MARGIN);
        }

        float normEntropy = computeNormalizedEntropy(probs);
        if (normEntropy > ModelConfig.MAX_NORMALIZED_ENTROPY) {
            return new ClassificationOutcome.LowConfidence(
                    candidates,
                    ClassificationOutcome.LowConfidenceReason.HIGH_ENTROPY);
        }
        if (topProb < ModelConfig.CONFIDENCE_THRESHOLD) {
            return new ClassificationOutcome.LowConfidence(
                    candidates,
                    ClassificationOutcome.LowConfidenceReason.CONFIDENCE_BELOW_THRESHOLD);
        }

        return new ClassificationOutcome.Diagnosis(
                topLabel, topProb, modelVersion, normEntropy, inferenceTimeMs, leafPixelRatio);
    }

    @NonNull
    private static List<ClassificationOutcome.Candidate> buildRankedCandidates(
            @NonNull float[] probs,
            @NonNull List<String> labels) {
        List<ClassificationOutcome.Candidate> candidates = new ArrayList<>(probs.length);
        for (int i = 0; i < probs.length; i++) {
            String label = (i < labels.size()) ? labels.get(i) : UNKNOWN_LABEL;
            candidates.add(new ClassificationOutcome.Candidate(label, probs[i]));
        }
        candidates.sort((a, b) -> Float.compare(b.getConfidence(), a.getConfidence()));
        return candidates;
    }

    private static boolean hasMaterialNonDiagnosticClass(
            @NonNull List<ClassificationOutcome.Candidate> candidates,
            @NonNull Set<String> nonDiagnosticClasses) {
        for (ClassificationOutcome.Candidate c : candidates) {
            if (nonDiagnosticClasses.contains(c.getClassId())
                    && c.getConfidence() >= MATERIAL_NON_LEAF_PROBABILITY_THRESHOLD) {
                return true;
            }
        }
        return false;
    }

    @NonNull
    public static DiagnosisResult evaluateLogits(
            @NonNull float[] logits,
            float leafRatio,
            long elapsedMs,
            @NonNull List<String> labels,
            @NonNull String modelVersion) {
        Objects.requireNonNull(logits, "logits");
        Objects.requireNonNull(labels, "labels");
        Objects.requireNonNull(modelVersion, "modelVersion");

        float[] probs = computeCalibratedProbabilities(logits, ModelConfig.TEMPERATURE_SCALE);
        int topIndex = 0;
        float topProb = probs.length > 0 ? probs[0] : 0f;
        for (int i = 1; i < probs.length; i++) {
            if (probs[i] > topProb) {
                topProb = probs[i];
                topIndex = i;
            }
        }

        String label = (topIndex < labels.size()) ? labels.get(topIndex) : UNKNOWN_LABEL;
        String cropId = extractCropId(label);
        float normEntropy = computeNormalizedEntropy(probs);
        DiagnosisOutcome outcome = resolveOutcome(label, topProb, normEntropy, leafRatio);

        return new DiagnosisResult(
                label,
                cropId,
                topProb,
                normEntropy,
                leafRatio,
                elapsedMs,
                modelVersion,
                outcome);
    }

    @NonNull
    public static DiagnosisOutcome resolveOutcome(
            @NonNull String label,
            float topProb,
            float normEntropy,
            float leafRatio) {
        if (leafRatio < ModelConfig.MIN_LEAF_PIXEL_RATIO) {
            return DiagnosisOutcome.NOT_A_SUPPORTED_LEAF;
        }
        if (ModelConfig.OOD_CLASS_LABEL.equals(label)) {
            return DiagnosisOutcome.NOT_A_SUPPORTED_LEAF;
        }
        if (topProb < ModelConfig.CONFIDENCE_THRESHOLD
                || normEntropy > ModelConfig.MAX_NORMALIZED_ENTROPY) {
            return DiagnosisOutcome.LOW_CONFIDENCE_RETAKE;
        }
        if (label.endsWith("_healthy")) {
            return DiagnosisOutcome.HEALTHY_LEAF;
        }
        return DiagnosisOutcome.CONFIDENT_DISEASE;
    }

    @NonNull
    public static String extractCropId(@NonNull String label) {
        if (label.contains("_")) {
            return label.substring(0, label.indexOf('_'));
        }
        return UNKNOWN_CROP;
    }
}
