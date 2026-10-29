package com.cropdoc.app.ui.research;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentResearchDemoBinding;
import com.cropdoc.app.ml.policy.CropDomain;
import com.cropdoc.app.ml.policy.InferenceDecisionResult;
import com.cropdoc.app.ml.policy.PolicyOutcomeType;
import com.cropdoc.app.ml.policy.PolicyRejectionDetail;
import com.cropdoc.app.ml.policy.SixClassResearchMapping;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;

/**
 * UI presentation and localization formatting for the Research Demo screen.
 */
public final class ResearchUiFormatter {

    private ResearchUiFormatter() {
        // Utility class
    }

    public static void bindQualityStage(
            @NonNull FragmentResearchDemoBinding binding,
            @NonNull Context ctx,
            @NonNull ResearchHarnessOutcome outcome) {
        boolean passed = outcome.getTerminalStage() != ResearchHarnessOutcome.Stage.QUALITY_CHECK;
        int color = passed ? R.color.primary_green : R.color.error_red;
        binding.txtQualityStatus.setTextColor(ContextCompat.getColor(ctx, color));
        if (passed) {
            binding.txtQualityStatus.setText(R.string.research_status_quality_passed);
        } else {
            String expl = localizeExplanation(ctx, outcome.getDecisionResult());
            binding.txtQualityStatus.setText(ctx.getString(R.string.research_status_quality_rejected, expl));
        }
    }

    public static void bindFoliarStage(
            @NonNull FragmentResearchDemoBinding binding,
            @NonNull Context ctx,
            @NonNull ResearchHarnessOutcome outcome) {
        if (outcome.getTerminalStage() == ResearchHarnessOutcome.Stage.QUALITY_CHECK) {
            binding.txtFoliarStatus.setText(R.string.research_status_bypassed);
            binding.txtFoliarStatus.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
            return;
        }
        boolean passed = outcome.getTerminalStage() != ResearchHarnessOutcome.Stage.FOLIAR_GATE;
        int color = passed ? R.color.primary_green : R.color.error_red;
        binding.txtFoliarStatus.setTextColor(ContextCompat.getColor(ctx, color));
        if (passed) {
            binding.txtFoliarStatus.setText(R.string.research_status_foliar_passed);
        } else {
            String expl = localizeExplanation(ctx, outcome.getDecisionResult());
            binding.txtFoliarStatus.setText(ctx.getString(R.string.research_status_foliar_rejected, expl));
        }
    }

    public static void bindDomainStage(
            @NonNull FragmentResearchDemoBinding binding,
            @NonNull Context ctx,
            @NonNull ResearchHarnessOutcome outcome) {
        ResearchHarnessOutcome.Stage stage = outcome.getTerminalStage();
        if (stage == ResearchHarnessOutcome.Stage.QUALITY_CHECK
                || stage == ResearchHarnessOutcome.Stage.FOLIAR_GATE) {
            binding.txtDomainStatus.setText(R.string.research_status_domain_bypassed);
            binding.txtDomainStatus.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
            return;
        }
        CropDomain dom = outcome.getDetectedDomain();
        int pct = Math.round(outcome.getDomainConfidence() * 100f);
        if (dom == CropDomain.SUPPORTED_RICE || dom == CropDomain.SUPPORTED_GROUNDNUT) {
            String name = (dom == CropDomain.SUPPORTED_RICE)
                    ? ctx.getString(R.string.crop_rice) : ctx.getString(R.string.crop_groundnut);
            binding.txtDomainStatus.setText(ctx.getString(R.string.research_status_domain_passed_fmt, name, pct));
            binding.txtDomainStatus.setTextColor(ContextCompat.getColor(ctx, R.color.primary_green));
        } else if (dom == CropDomain.UNSUPPORTED_CROP) {
            binding.txtDomainStatus.setText(ctx.getString(R.string.research_status_domain_unsupported_fmt, pct));
            binding.txtDomainStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error_red));
        } else if (outcome.getDecisionResult().getRejectionDetail() == PolicyRejectionDetail.CROP_DOMAIN_UNCERTAIN) {
            binding.txtDomainStatus.setText(ctx.getString(R.string.research_status_domain_uncertain_fmt, pct));
            binding.txtDomainStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error_red));
        } else {
            binding.txtDomainStatus.setText(R.string.research_status_domain_unavailable);
            binding.txtDomainStatus.setTextColor(ContextCompat.getColor(ctx, R.color.error_red));
        }
    }

    public static void bindDiseaseStage(
            @NonNull FragmentResearchDemoBinding binding,
            @NonNull Context ctx,
            @NonNull ResearchHarnessOutcome outcome) {
        if (outcome.getTerminalStage() != ResearchHarnessOutcome.Stage.DECISION_POLICY) {
            binding.txtDiseaseStatus.setText(R.string.research_status_disease_bypassed);
            binding.txtDiseaseStatus.setTextColor(ContextCompat.getColor(ctx, R.color.on_surface_variant));
            return;
        }
        String classId = outcome.getPredictedClassId();
        int pct = Math.round(outcome.getDiseaseConfidence() * 100f);
        String name = getLocalizedDiseaseName(ctx, classId);
        // Cotton output belongs to the separate unvalidated binary research pilot. Never render
        // it as a six-class global taxonomy result or as a calibrated diagnostic confidence.
        if (classId != null && classId.startsWith("cotton_")) {
            binding.txtDiseaseStatus.setText(ctx.getString(
                    R.string.research_status_cotton_experimental_fmt, name, pct));
            binding.txtDiseaseStatus.setTextColor(
                    ContextCompat.getColor(ctx, R.color.on_surface_variant));
            return;
        }
        SixClassResearchMapping.ClassEntry e = findEntry(classId);
        int localIdx = e != null ? e.localIndex : -1;
        int globalIdx = e != null ? e.globalIndex : -1;
        binding.txtDiseaseStatus.setText(ctx.getString(
                R.string.research_status_disease_executed_fmt, name, localIdx, globalIdx, pct));
        binding.txtDiseaseStatus.setTextColor(ContextCompat.getColor(ctx, R.color.primary_green));
    }

    public static void bindDecisionStage(
            @NonNull FragmentResearchDemoBinding binding,
            @NonNull Context ctx,
            @NonNull ResearchHarnessOutcome outcome) {
        InferenceDecisionResult dec = outcome.getDecisionResult();
        String typeName = localizeOutcomeType(ctx, dec.getOutcomeType());
        int resId = outcome.isAccepted()
                ? R.string.research_status_decision_accepted
                : R.string.research_status_decision_rejected;
        int color = outcome.isAccepted() ? R.color.primary_green : R.color.error_red;
        binding.txtDecisionStatus.setText(ctx.getString(resId, typeName));
        binding.txtDecisionStatus.setTextColor(ContextCompat.getColor(ctx, color));
        binding.txtDecisionExplanation.setText(localizeExplanation(ctx, dec));
    }

    @NonNull
    public static String getLocalizedDiseaseName(@NonNull Context context, @Nullable String classId) {
        if (classId == null) {
            return context.getString(R.string.research_status_inconclusive);
        }
        switch (classId) {
            case "rice_bacterial_leaf_blight":
                return context.getString(R.string.disease_rice_bacterial_blight);
            case "rice_brown_spot":
                return context.getString(R.string.disease_rice_brown_spot);
            case "rice_leaf_blast":
                return context.getString(R.string.disease_rice_blast);
            case "rice_healthy":
                return context.getString(R.string.disease_rice_healthy);
            case "groundnut_early_leaf_spot":
                return context.getString(R.string.disease_groundnut_early_leaf_spot);
            case "groundnut_healthy":
                return context.getString(R.string.disease_groundnut_healthy);
            case "cotton_bacterial_blight":
                return context.getString(R.string.disease_cotton_bacterial_blight);
            case "cotton_healthy":
                return context.getString(R.string.disease_cotton_healthy);
            default:
                return classId;
        }
    }

    @NonNull
    public static String localizeOutcomeType(
            @NonNull Context context, @NonNull PolicyOutcomeType outcomeType) {
        switch (outcomeType) {
            case ACCEPTED_SUPPORTED_CLASS:
                return context.getString(R.string.outcome_accepted_supported);
            case INCONCLUSIVE_LOW_CONFIDENCE:
                return context.getString(R.string.outcome_inconclusive_low_conf);
            case OUT_OF_DOMAIN:
                return context.getString(R.string.outcome_out_of_domain);
            case INVALID_MODEL_OUTPUT:
                return context.getString(R.string.outcome_invalid_model_output);
            case MODEL_UNAVAILABLE:
                return context.getString(R.string.outcome_model_unavailable);
            default:
                return outcomeType.name();
        }
    }

    @NonNull
    public static String localizeExplanation(
            @NonNull Context context, @NonNull InferenceDecisionResult dec) {
        if (dec.isAccepted()) {
            return context.getString(R.string.research_decision_accepted_explanation);
        }
        PolicyRejectionDetail detail = dec.getRejectionDetail();
        switch (detail) {
            case LEAF_GATE_REJECTED_NON_LEAF:
                return context.getString(R.string.rejection_detail_foliar_non_leaf);
            case UNSUPPORTED_CROP_DOMAIN:
                return context.getString(R.string.rejection_detail_unsupported_crop);
            case CROP_DOMAIN_UNCERTAIN:
                return context.getString(R.string.rejection_detail_domain_uncertain);
            case CONFIDENCE_BELOW_THRESHOLD:
                return context.getString(R.string.rejection_detail_low_confidence);
            case ENTROPY_ABOVE_THRESHOLD:
                return context.getString(R.string.rejection_detail_high_entropy);
            case MODEL_NOT_AVAILABLE:
            case CROP_DOMAIN_GATE_NOT_PRESENT:
                return context.getString(R.string.rejection_detail_model_unavailable);
            default:
                String expl = dec.getExplanation();
                return (expl != null && !expl.isEmpty()) ? expl : detail.name();
        }
    }

    @NonNull
    public static String formatLatency(
            @NonNull Context context, @NonNull ResearchDemoState state) {
        String domLatStr = state.getDomainLatencyMs() >= 0
                ? context.getString(R.string.research_latency_ms_fmt, state.getDomainLatencyMs())
                : context.getString(R.string.research_status_not_run);
        String disLatStr = state.getDiseaseLatencyMs() >= 0
                ? context.getString(R.string.research_latency_ms_fmt, state.getDiseaseLatencyMs())
                : context.getString(R.string.research_status_not_run);

        return context.getString(
                R.string.research_status_latency_fmt,
                state.getTotalLatencyMs(),
                domLatStr,
                disLatStr);
    }

    @Nullable
    private static SixClassResearchMapping.ClassEntry findEntry(@Nullable String classId) {
        if (classId == null) {
            return null;
        }
        for (SixClassResearchMapping.ClassEntry e : SixClassResearchMapping.getEntries()) {
            if (e.classId.equals(classId)) {
                return e;
            }
        }
        return null;
    }

    @NonNull
    public static String getStageStatusText(@NonNull Context ctx, boolean passed) {
        return passed ? ctx.getString(R.string.research_status_passed)
                : ctx.getString(R.string.research_status_rejected);
    }

    @NonNull
    public static String getErrorStatusText(@NonNull Context ctx) {
        return ctx.getString(R.string.research_status_error);
    }
}
