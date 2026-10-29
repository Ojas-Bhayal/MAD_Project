package com.cropdoc.app.ml;

import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Strongly-typed domain/ML result representing all mutually exclusive outcomes of leaf analysis.
 * Ensures failed inference, image rejection, unvalidated gates, low confidence, and confirmed
 * diagnoses are distinct types and cannot be confused with valid diagnoses.
 */
public abstract class ClassificationOutcome {

    public enum RejectionReason {
        BLURRY,
        UNDER_EXPOSED,
        OVER_EXPOSED,
        INSUFFICIENT_RETICLE_COVERAGE,
        IMAGE_DECODE_FAILED,
        NON_LEAF_DETECTED,
        NON_LEAF_CLASS
    }

    public enum UnavailableReason {
        MODEL_FILE_MISSING,
        MODEL_CORRUPT_OR_TRUNCATED,
        INCOMPATIBLE_TENSOR_SHAPE,
        LABELS_MISMATCH,
        LABEL_ORDER_CHECKSUM_MISMATCH,
        UNIFORM_OR_DEGENERATE_OUTPUT,
        INTERPRETER_INIT_FAILED,
        INFERENCE_EXCEPTION,
        MANIFEST_MISSING,
        MANIFEST_CORRUPT,
        MANIFEST_DIGEST_MISMATCH,
        GATE_UNAVAILABLE,
        GATE_UNVALIDATED
    }

    public enum LowConfidenceReason {
        HIGH_ENTROPY,
        CONFIDENCE_BELOW_THRESHOLD,
        CLOSE_TOP_MARGIN
    }

    public static final class Candidate {
        @NonNull
        private final String classId;
        private final float confidence;

        public Candidate(@NonNull String classId, float confidence) {
            this.classId = Objects.requireNonNull(classId, "classId");
            this.confidence = confidence;
        }

        @NonNull
        public String getClassId() {
            return classId;
        }

        public float getConfidence() {
            return confidence;
        }
    }

    public static final class Diagnosis extends ClassificationOutcome {
        @NonNull
        private final String classId;
        private final float confidence;
        @NonNull
        private final String modelVersion;
        private final float normalizedEntropy;
        private final long inferenceTimeMs;
        private final float leafPixelRatio;

        public Diagnosis(
                @NonNull String classId,
                float confidence,
                @NonNull String modelVersion) {
            this(classId, confidence, modelVersion, 0.0f, 0L, 0.0f);
        }

        public Diagnosis(
                @NonNull String classId,
                float confidence,
                @NonNull String modelVersion,
                float normalizedEntropy,
                long inferenceTimeMs,
                float leafPixelRatio) {
            this.classId = Objects.requireNonNull(classId, "classId");
            this.confidence = confidence;
            this.modelVersion = Objects.requireNonNull(modelVersion, "modelVersion");
            this.normalizedEntropy = normalizedEntropy;
            this.inferenceTimeMs = inferenceTimeMs;
            this.leafPixelRatio = leafPixelRatio;
        }

        @NonNull
        public String getClassId() {
            return classId;
        }

        public float getConfidence() {
            return confidence;
        }

        @NonNull
        public String getModelVersion() {
            return modelVersion;
        }

        public float getNormalizedEntropy() {
            return normalizedEntropy;
        }

        public long getInferenceTimeMs() {
            return inferenceTimeMs;
        }

        public float getLeafPixelRatio() {
            return leafPixelRatio;
        }

        @NonNull
        @Override
        public String toString() {
            return "Diagnosis{classId='" + classId + "', confidence=" + confidence
                    + ", modelVersion='" + modelVersion + "', entropy=" + normalizedEntropy
                    + ", timeMs=" + inferenceTimeMs + ", leafRatio=" + leafPixelRatio + "}";
        }
    }

    public static final class LowConfidence extends ClassificationOutcome {
        @NonNull
        private final List<Candidate> topCandidates;
        @NonNull
        private final LowConfidenceReason reason;

        public LowConfidence(
                @NonNull List<Candidate> topCandidates,
                @NonNull LowConfidenceReason reason) {
            this.topCandidates = Collections.unmodifiableList(
                    Objects.requireNonNull(topCandidates, "topCandidates"));
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        @NonNull
        public List<Candidate> getTopCandidates() {
            return topCandidates;
        }

        @NonNull
        public LowConfidenceReason getReason() {
            return reason;
        }

        @NonNull
        @Override
        public String toString() {
            return "LowConfidence{reason=" + reason + ", candidates=" + topCandidates.size() + "}";
        }
    }

    public static final class Rejected extends ClassificationOutcome {
        @NonNull
        private final RejectionReason reason;

        public Rejected(@NonNull RejectionReason reason) {
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        @NonNull
        public RejectionReason getReason() {
            return reason;
        }

        @NonNull
        @Override
        public String toString() {
            return "Rejected{reason=" + reason + "}";
        }
    }

    public static final class ModelUnavailable extends ClassificationOutcome {
        @NonNull
        private final UnavailableReason reason;

        public ModelUnavailable(@NonNull UnavailableReason reason) {
            this.reason = Objects.requireNonNull(reason, "reason");
        }

        @NonNull
        public UnavailableReason getReason() {
            return reason;
        }

        @NonNull
        @Override
        public String toString() {
            return "ModelUnavailable{reason=" + reason + "}";
        }
    }
}
