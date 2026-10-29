package com.cropdoc.app.ui.result;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.bumptech.glide.Glide;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentResultBinding;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ui.common.Result;
import com.cropdoc.app.ui.common.UiMotionHelper;
import com.cropdoc.app.ui.common.ViewModelFactory;
import com.cropdoc.app.util.DiseaseLabelFormatter;
import com.cropdoc.app.util.LocaleHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import java.io.File;
import java.util.Locale;

/**
 * Displays the diagnosis hero card, visual confidence indicator, explicit Low-Confidence /
 * Not-a-Leaf safety banner, offline ICAR/CABI/CIB&RC advice, TTS voice readout, and feedback.
 */
public final class ResultFragment extends Fragment {

    public static final String ARG_SCAN_ID = "arg_scan_id";

    @Nullable private FragmentResultBinding binding;
    @Nullable private TextToSpeech textToSpeech;
    private ResultViewModel viewModel;
    @Nullable private String currentScanId;

    @NonNull @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        binding = FragmentResultBinding.inflate(inf, c, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewModelFactory factory = ((CropDocApplication) requireActivity().getApplication())
                .getAppContainer().getViewModelFactory();
        viewModel = new ViewModelProvider(this, factory).get(ResultViewModel.class);
        initTextToSpeechSafely();
        if (getArguments() != null) {
            currentScanId = getArguments().getString(ARG_SCAN_ID);
        }
        setupButtons();
        observeViewModel();
        if (currentScanId != null) {
            viewModel.loadScanResult(currentScanId, LocaleHelper.getCurrentLanguageCode());
        } else {
            navigateUpSafely();
        }
    }

    private void initTextToSpeechSafely() {
        Context ctx = getContext();
        if (ctx != null) {
            try {
                textToSpeech = new TextToSpeech(ctx.getApplicationContext(), status -> {
                    if (status == TextToSpeech.SUCCESS && textToSpeech != null) {
                        textToSpeech.setLanguage(Locale.forLanguageTag(LocaleHelper.getCurrentLanguageCode()));
                    }
                });
            } catch (Throwable ignored) {
                textToSpeech = null;
            }
        }
    }

    private void setupButtons() {
        if (binding == null) {
            return;
        }
        UiMotionHelper.attachPressFeedback(
                binding.btnRetakePhoto, binding.btnSpeakAdvice,
                binding.btnThumbUp, binding.btnThumbDown);
        binding.btnRetakePhoto.setOnClickListener(v -> navigateUpSafely());
        binding.btnThumbUp.setOnClickListener(v -> recordFeedbackAndNotify(true));
        binding.btnThumbDown.setOnClickListener(v -> showWrongDiagnosisDialog());
        binding.btnSpeakAdvice.setOnClickListener(v -> speakCurrentAdvice());
    }

    private void recordFeedbackAndNotify(boolean helpful) {
        if (currentScanId == null || binding == null) {
            return;
        }
        viewModel.submitFeedback(currentScanId, helpful);
        int msgRes = helpful ? R.string.feedback_saved_message : R.string.feedback_wrong_saved_message;
        binding.txtFeedbackThanks.setText(msgRes);
        UiMotionHelper.animateReveal(binding.txtFeedbackThanks);
        binding.btnThumbUp.setAlpha(helpful ? 1.0f : 0.55f);
        binding.btnThumbDown.setAlpha(helpful ? 0.55f : 1.0f);
        Snackbar.make(binding.getRoot(), msgRes, Snackbar.LENGTH_SHORT).show();
    }

    private void showWrongDiagnosisDialog() {
        if (currentScanId == null || binding == null || !isAdded()) {
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.feedback_wrong_dialog_title)
                .setMessage(R.string.feedback_wrong_dialog_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.btn_feedback_unhelpful,
                        (dialog, which) -> recordFeedbackAndNotify(false))
                .show();
    }

    private void navigateUpSafely() {
        if (isAdded()) {
            try {
                if (!NavHostFragment.findNavController(this).popBackStack(R.id.scanFragment, false)) {
                    NavHostFragment.findNavController(this).navigateUp();
                }
            } catch (RuntimeException ignored) {
                // Safe fallback if navigation controller is busy
            }
        }
    }

    private void observeViewModel() {
        viewModel.getScreenState().observe(getViewLifecycleOwner(), result -> {
            if (binding == null || result == null) {
                return;
            }
            binding.resultProgress.setVisibility(
                    result.getStatus() == Result.Status.LOADING ? View.VISIBLE : View.GONE);
            if (result.getStatus() == Result.Status.ERROR) {
                navigateUpSafely();
                return;
            }
            binding.txtResultError.setVisibility(View.GONE);
            if (result.isSuccess() && result.getData() != null) {
                renderScreenState(result.getData());
            }
        });
        viewModel.getLastFeedbackHelpful().observe(getViewLifecycleOwner(), helpful -> {
            if (binding != null && helpful != null) {
                binding.txtFeedbackThanks.setText(helpful
                        ? R.string.feedback_saved_message : R.string.feedback_wrong_saved_message);
                binding.txtFeedbackThanks.setVisibility(View.VISIBLE);
                binding.btnThumbUp.setAlpha(helpful ? 1.0f : 0.55f);
                binding.btnThumbDown.setAlpha(helpful ? 0.55f : 1.0f);
            }
        });
    }

    private void renderScreenState(@NonNull ResultViewModel.ResultScreenState state) {
        if (binding == null) {
            return;
        }
        binding.cardHeroSummary.setVisibility(View.VISIBLE);
        ScanRecord record = state.getScanRecord();
        bindLeafImage(record.getLocalImagePath());
        int confPct = Math.max(0, Math.min(100, Math.round(record.getConfidence() * 100f)));
        binding.txtConfidenceValue.setText(getString(R.string.confidence_format, confPct));
        binding.progressConfidence.setProgress(confPct);
        binding.txtCropAndDiseaseTitle.setText(
                DiseaseLabelFormatter.formatDiseaseTitle(record.getCropId(), record.getDiseaseId()));
        DiagnosisOutcome outcome = record.getOutcome();
        if (outcome == DiagnosisOutcome.NOT_A_SUPPORTED_LEAF
                || outcome == DiagnosisOutcome.LOW_CONFIDENCE_RETAKE) {
            UiMotionHelper.animateReveal(binding.cardLowConfidenceBanner);
            binding.txtBannerMessage.setText(outcome == DiagnosisOutcome.NOT_A_SUPPORTED_LEAF
                    ? R.string.banner_not_a_leaf : R.string.banner_low_confidence);
            binding.cardAdviceContainer.setVisibility(View.GONE);
            binding.layoutFeedbackRow.setVisibility(View.GONE);
            binding.btnSpeakAdvice.setVisibility(View.GONE);
            return;
        }
        binding.cardLowConfidenceBanner.setVisibility(View.GONE);
        binding.layoutFeedbackRow.setVisibility(View.VISIBLE);
        binding.btnSpeakAdvice.setVisibility(View.VISIBLE);
        bindAdviceCard(state, state.getAdvice());
    }

    private void bindLeafImage(@NonNull String localPath) {
        if (binding == null) {
            return;
        }
        try {
            File imgFile = new File(localPath);
            if (!localPath.isEmpty() && imgFile.exists()) {
                Glide.with(binding.imgScannedLeaf).load(imgFile)
                        .placeholder(R.drawable.ic_leaf_scan).error(R.drawable.ic_leaf_scan)
                        .centerCrop().into(binding.imgScannedLeaf);
                return;
            }
        } catch (Throwable ignored) {
            // Fallback to vector placeholder
        }
        binding.imgScannedLeaf.setImageResource(R.drawable.ic_leaf_scan);
    }

    private void bindAdviceCard(@NonNull ResultViewModel.ResultScreenState state, @Nullable CropDiseaseAdvice advice) {
        if (binding == null || advice == null) {
            return;
        }
        UiMotionHelper.animateReveal(binding.cardAdviceContainer);
        binding.txtCropAndDiseaseTitle.setText(
                getString(R.string.crop_and_disease_format, advice.getCropName(), advice.getDiseaseName()));
        binding.txtCausesBody.setText(advice.getCauses());
        binding.txtCulturalControlBody.setText(advice.getCulturalAndBioControl());
        int chemVis = state.shouldShowChemicalAdvice() ? View.VISIBLE : View.GONE;
        binding.headingChemicalCibrc.setVisibility(chemVis);
        binding.txtChemicalControlBody.setVisibility(chemVis);
        binding.txtChemicalControlBody.setText(advice.getChemicalControlCibrc());
        binding.txtPreventionBody.setText(advice.getPreventionSteps());
        binding.txtSourceAttribution.setText(advice.getAuthoritativeSource());
    }

    private void speakCurrentAdvice() {
        if (binding != null && textToSpeech != null) {
            try {
                String speech = binding.txtCropAndDiseaseTitle.getText()
                        + ". " + binding.txtCulturalControlBody.getText();
                textToSpeech.speak(speech, TextToSpeech.QUEUE_FLUSH, null, "cropdoc_advice_tts");
            } catch (Throwable ignored) {
                // Ignore TTS engine failures
            }
        }
    }

    @Override
    public void onDestroyView() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        super.onDestroyView();
        binding = null;
    }
}
