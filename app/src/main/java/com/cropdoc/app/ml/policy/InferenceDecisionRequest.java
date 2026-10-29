package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Immutable request encapsulation supplied to the inference decision policy.
 */
public final class InferenceDecisionRequest {

    @Nullable private final float[] rawOutput;
    private final boolean isLogits;
    private final boolean isModelAvailable;
    @Nullable private final LeafGateValidationStatus leafGateStatus;
    @Nullable private final CropDomainResult cropDomainResult;
    @Nullable private final String cropContext;
    @Nullable private final Float oodScore;

    /* package-private */ InferenceDecisionRequest(@NonNull Builder builder) {
        this.rawOutput = builder.rawOutput != null ? builder.rawOutput.clone() : null;
        this.isLogits = builder.isLogits;
        this.isModelAvailable = builder.isModelAvailable;
        this.leafGateStatus = builder.leafGateStatus;
        this.cropDomainResult = builder.cropDomainResult;
        this.cropContext = builder.cropContext;
        this.oodScore = builder.oodScore;
    }

    @Nullable
    public float[] getRawOutput() {
        return rawOutput != null ? rawOutput.clone() : null;
    }

    public boolean isLogits() {
        return isLogits;
    }

    public boolean isModelAvailable() {
        return isModelAvailable;
    }

    @Nullable
    public LeafGateValidationStatus getLeafGateStatus() {
        return leafGateStatus;
    }

    @Nullable
    public CropDomainResult getCropDomainResult() {
        return cropDomainResult;
    }

    @Nullable
    public String getCropContext() {
        return cropContext;
    }

    @Nullable
    public Float getOodScore() {
        return oodScore;
    }

    public static final class Builder {
        @Nullable float[] rawOutput;
        boolean isLogits = true;
        boolean isModelAvailable = true;
        @Nullable LeafGateValidationStatus leafGateStatus;
        @Nullable CropDomainResult cropDomainResult;
        @Nullable String cropContext;
        @Nullable Float oodScore;

        @NonNull
        public Builder setRawOutput(@Nullable float[] rawOutput) {
            this.rawOutput = rawOutput != null ? rawOutput.clone() : null;
            return this;
        }

        @NonNull
        public Builder setIsLogits(boolean isLogits) {
            this.isLogits = isLogits;
            return this;
        }

        @NonNull
        public Builder setModelAvailable(boolean isModelAvailable) {
            this.isModelAvailable = isModelAvailable;
            return this;
        }

        @NonNull
        public Builder setLeafGateStatus(@Nullable LeafGateValidationStatus leafGateStatus) {
            this.leafGateStatus = leafGateStatus;
            return this;
        }

        @NonNull
        public Builder setCropDomainResult(@Nullable CropDomainResult cropDomainResult) {
            this.cropDomainResult = cropDomainResult;
            return this;
        }

        @NonNull
        public Builder setCropContext(@Nullable String cropContext) {
            this.cropContext = cropContext;
            return this;
        }

        @NonNull
        public Builder setOodScore(@Nullable Float oodScore) {
            this.oodScore = oodScore;
            return this;
        }

        @NonNull
        public InferenceDecisionRequest build() {
            return new InferenceDecisionRequest(this);
        }
    }
}
