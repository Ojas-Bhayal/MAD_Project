package com.cropdoc.app.domain.usecase;

import android.graphics.Bitmap;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ml.ClassificationOutcome;
import com.cropdoc.app.ml.ConfidenceCalibrator;
import com.cropdoc.app.ml.LeafClassifier;
import java.util.Objects;

/**
 * Domain use case coordinating on-device LiteRT inference, offline ICAR/CABI advice lookup,
 * and local Room scan persistence.
 *
 * Implements a strict boundary guard ensuring ONLY verified Diagnosis outcomes are persisted to
 * ScanRepository or retrieve localized advice. Non-diagnosis outcomes (Rejected, ModelUnavailable,
 * GateUnavailable, LowConfidence) save nothing, fetch no advice, and show clear status states.
 */
public final class AnalyzeLeafUseCase {

    public static final class AnalysisOutput {
        @NonNull
        private final ClassificationOutcome outcome;

        @Nullable
        private final ScanRecord scanRecord;

        @Nullable
        private final CropDiseaseAdvice advice;

        public AnalysisOutput(
                @NonNull ClassificationOutcome outcome,
                @Nullable ScanRecord scanRecord,
                @Nullable CropDiseaseAdvice advice) {
            this.outcome = Objects.requireNonNull(outcome, "outcome");
            this.scanRecord = scanRecord;
            this.advice = advice;
        }

        @NonNull
        public ClassificationOutcome getOutcome() {
            return outcome;
        }

        @Nullable
        public ScanRecord getScanRecord() {
            return scanRecord;
        }

        @Nullable
        public CropDiseaseAdvice getAdvice() {
            return advice;
        }
    }

    @NonNull
    private final LeafClassifier classifier;

    @NonNull
    private final AdviceRepository adviceRepository;

    @NonNull
    private final ScanRepository scanRepository;

    @NonNull
    private final AuthRepository authRepository;

    public AnalyzeLeafUseCase(
            @NonNull LeafClassifier classifier,
            @NonNull AdviceRepository adviceRepository,
            @NonNull ScanRepository scanRepository,
            @NonNull AuthRepository authRepository) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.adviceRepository = Objects.requireNonNull(adviceRepository, "adviceRepository");
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
    }

    @NonNull
    public AnalysisOutput execute(
            @NonNull Bitmap croppedLeafBitmap,
            @NonNull String savedImagePath,
            @NonNull String languageCode) {
        ClassificationOutcome outcome = classifier.classify(croppedLeafBitmap);

        // SINGLE BOUNDARY GUARD: Only confirmed Diagnosis outcomes may ever be persisted
        // to Room or fetch localized advice. Rejected, ModelUnavailable, and LowConfidence
        // outcomes NEVER touch the database or history storage.
        if (!(outcome instanceof ClassificationOutcome.Diagnosis)) {
            return new AnalysisOutput(outcome, null, null);
        }

        ClassificationOutcome.Diagnosis diagnosis = (ClassificationOutcome.Diagnosis) outcome;
        String labelId = diagnosis.getClassId();
        String cropId = ConfidenceCalibrator.extractCropId(labelId);
        DiagnosisOutcome domainOutcome = labelId.endsWith("_healthy")
                ? DiagnosisOutcome.HEALTHY_LEAF
                : DiagnosisOutcome.CONFIDENT_DISEASE;

        // Propagate genuine measured inference metrics (G4)
        DiagnosisResult domainDiagnosis = new DiagnosisResult(
                labelId,
                cropId,
                diagnosis.getConfidence(),
                diagnosis.getNormalizedEntropy(),
                diagnosis.getLeafPixelRatio(),
                diagnosis.getInferenceTimeMs(),
                diagnosis.getModelVersion(),
                domainOutcome);

        String userId = authRepository.getCurrentUserId();
        ScanRecord savedRecord = scanRepository.saveScanSync(userId, domainDiagnosis, savedImagePath);
        CropDiseaseAdvice advice = adviceRepository.getAdviceSync(labelId, languageCode);
        return new AnalysisOutput(outcome, savedRecord, advice);
    }
}
