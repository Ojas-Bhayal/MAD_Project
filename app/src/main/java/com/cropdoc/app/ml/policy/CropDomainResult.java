package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Objects;

/**
 * Result of evaluating an input image through the botanical Crop Domain Gate.
 */
public final class CropDomainResult {

    public enum Status {
        ACCEPTED_RICE,
        ACCEPTED_GROUNDNUT,
        REJECTED_UNSUPPORTED_CROP,
        REJECTED_UNCERTAIN,
        GATE_NOT_PRESENT,
        GATE_UNVALIDATED,
        GATE_ERROR
    }

    @NonNull private final Status status;
    @Nullable private final CropDomain domain;
    private final float confidence;
    private final float normalizedEntropy;
    @NonNull private final String explanation;

    public CropDomainResult(
            @NonNull Status status,
            @Nullable CropDomain domain,
            float confidence,
            float normalizedEntropy,
            @NonNull String explanation) {
        this.status = Objects.requireNonNull(status, "status");
        this.domain = domain;
        this.confidence = confidence;
        this.normalizedEntropy = normalizedEntropy;
        this.explanation = Objects.requireNonNull(explanation, "explanation");
    }

    @NonNull
    public static CropDomainResult acceptedRice(float confidence, float entropy, @NonNull String explanation) {
        return new CropDomainResult(
                Status.ACCEPTED_RICE, CropDomain.SUPPORTED_RICE, confidence, entropy, explanation);
    }

    @NonNull
    public static CropDomainResult acceptedGroundnut(float confidence, float entropy, @NonNull String explanation) {
        return new CropDomainResult(
                Status.ACCEPTED_GROUNDNUT, CropDomain.SUPPORTED_GROUNDNUT, confidence, entropy, explanation);
    }

    @NonNull
    public static CropDomainResult rejectedUnsupported(float confidence, float entropy, @NonNull String explanation) {
        return new CropDomainResult(
                Status.REJECTED_UNSUPPORTED_CROP, CropDomain.UNSUPPORTED_CROP, confidence, entropy, explanation);
    }

    @NonNull
    public static CropDomainResult rejectedUncertain(float confidence, float entropy, @NonNull String explanation) {
        return new CropDomainResult(Status.REJECTED_UNCERTAIN, null, confidence, entropy, explanation);
    }

    @NonNull
    public static CropDomainResult notPresent() {
        return new CropDomainResult(Status.GATE_NOT_PRESENT, null, 0f, 0f, "Crop domain gate is not present.");
    }

    @NonNull
    public static CropDomainResult unvalidated(@NonNull String explanation) {
        return new CropDomainResult(Status.GATE_UNVALIDATED, null, 0f, 0f, explanation);
    }

    @NonNull
    public static CropDomainResult error(@NonNull String explanation) {
        return new CropDomainResult(Status.GATE_ERROR, null, 0f, 0f, explanation);
    }

    @NonNull public Status getStatus() { return status; }
    @Nullable public CropDomain getDomain() { return domain; }
    public float getConfidence() { return confidence; }
    public float getNormalizedEntropy() { return normalizedEntropy; }
    @NonNull public String getExplanation() { return explanation; }

    public boolean isAccepted() {
        return status == Status.ACCEPTED_RICE || status == Status.ACCEPTED_GROUNDNUT;
    }

    public boolean isRice() {
        return status == Status.ACCEPTED_RICE;
    }

    public boolean isGroundnut() {
        return status == Status.ACCEPTED_GROUNDNUT;
    }

    public boolean isRejectedUnsupported() {
        return status == Status.REJECTED_UNSUPPORTED_CROP;
    }

    public boolean isUncertain() {
        return status == Status.REJECTED_UNCERTAIN;
    }

    public boolean isGateCertified() {
        return status != Status.GATE_NOT_PRESENT && status != Status.GATE_UNVALIDATED && status != Status.GATE_ERROR;
    }
}
