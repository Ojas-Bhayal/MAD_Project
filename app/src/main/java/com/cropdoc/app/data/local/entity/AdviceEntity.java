package com.cropdoc.app.data.local.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import java.util.Objects;

/**
 * Composite-keyed Room entity storing offline treatment guidance per disease and language code.
 */
@Entity(
        tableName = "advice_catalog",
        primaryKeys = {"disease_id", "language_code"})
public final class AdviceEntity {

    @NonNull
    @ColumnInfo(name = "disease_id")
    private final String diseaseId;

    @NonNull
    @ColumnInfo(name = "language_code")
    private final String languageCode;

    @NonNull
    @ColumnInfo(name = "crop_name")
    private final String cropName;

    @NonNull
    @ColumnInfo(name = "disease_name")
    private final String diseaseName;

    @NonNull
    @ColumnInfo(name = "causes")
    private final String causes;

    @NonNull
    @ColumnInfo(name = "cultural_bio_control")
    private final String culturalBioControl;

    @NonNull
    @ColumnInfo(name = "chemical_control_cibrc")
    private final String chemicalControlCibrc;

    @NonNull
    @ColumnInfo(name = "prevention_steps")
    private final String preventionSteps;

    @NonNull
    @ColumnInfo(name = "authoritative_source")
    private final String authoritativeSource;

    public AdviceEntity(
            @NonNull String diseaseId,
            @NonNull String languageCode,
            @NonNull String cropName,
            @NonNull String diseaseName,
            @NonNull String causes,
            @NonNull String culturalBioControl,
            @NonNull String chemicalControlCibrc,
            @NonNull String preventionSteps,
            @NonNull String authoritativeSource) {
        this.diseaseId = Objects.requireNonNull(diseaseId, "diseaseId");
        this.languageCode = Objects.requireNonNull(languageCode, "languageCode");
        this.cropName = Objects.requireNonNull(cropName, "cropName");
        this.diseaseName = Objects.requireNonNull(diseaseName, "diseaseName");
        this.causes = Objects.requireNonNull(causes, "causes");
        this.culturalBioControl = Objects.requireNonNull(culturalBioControl, "culturalBioControl");
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
    public String getLanguageCode() {
        return languageCode;
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
    public String getCulturalBioControl() {
        return culturalBioControl;
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
