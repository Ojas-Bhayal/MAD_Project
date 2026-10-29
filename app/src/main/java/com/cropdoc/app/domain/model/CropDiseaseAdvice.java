package com.cropdoc.app.domain.model;

import androidx.annotation.NonNull;
import java.util.Objects;

/**
 * Localized, source-backed agronomic guidance for a detected crop disease or healthy state.
 */
public final class CropDiseaseAdvice {

    @NonNull
    private final String diseaseId;

    @NonNull
    private final String cropName;

    @NonNull
    private final String diseaseName;

    @NonNull
    private final String causes;

    @NonNull
    private final String culturalAndBioControl;

    @NonNull
    private final String chemicalControlCibrc;

    @NonNull
    private final String preventionSteps;

    @NonNull
    private final String authoritativeSource;

    public CropDiseaseAdvice(
            @NonNull String diseaseId,
            @NonNull String cropName,
            @NonNull String diseaseName,
            @NonNull String causes,
            @NonNull String culturalAndBioControl,
            @NonNull String chemicalControlCibrc,
            @NonNull String preventionSteps,
            @NonNull String authoritativeSource) {
        this.diseaseId = Objects.requireNonNull(diseaseId, "diseaseId");
        this.cropName = Objects.requireNonNull(cropName, "cropName");
        this.diseaseName = Objects.requireNonNull(diseaseName, "diseaseName");
        this.causes = Objects.requireNonNull(causes, "causes");
        this.culturalAndBioControl = Objects.requireNonNull(
                culturalAndBioControl, "culturalAndBioControl");
        this.chemicalControlCibrc = Objects.requireNonNull(
                chemicalControlCibrc, "chemicalControlCibrc");
        this.preventionSteps = Objects.requireNonNull(preventionSteps, "preventionSteps");
        this.authoritativeSource = Objects.requireNonNull(
                authoritativeSource, "authoritativeSource");
    }

    @NonNull
    public String getDiseaseId() {
        return diseaseId;
    }

    @NonNull
    public String getCropName() {
        return cropName;
    }

    @NonNull
    public String getDiseaseName() {
        return diseaseName;
    }

    @NonNull
    public String getCauses() {
        return causes;
    }

    @NonNull
    public String getCulturalAndBioControl() {
        return culturalAndBioControl;
    }

    @NonNull
    public String getChemicalControlCibrc() {
        return chemicalControlCibrc;
    }

    @NonNull
    public String getPreventionSteps() {
        return preventionSteps;
    }

    @NonNull
    public String getAuthoritativeSource() {
        return authoritativeSource;
    }
}
