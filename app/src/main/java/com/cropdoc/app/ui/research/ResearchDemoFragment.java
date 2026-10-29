package com.cropdoc.app.ui.research;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.cropdoc.app.BuildConfig;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentResearchDemoBinding;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ml.research.ResearchHarnessOutcome;
import java.io.File;

/**
 * Dedicated debug-only Research Demo UI for isolated evaluation of FP32 research models.
 */
public final class ResearchDemoFragment extends Fragment {

    private FragmentResearchDemoBinding binding;
    private ResearchDemoViewModel viewModel;
    @Nullable private Uri currentCameraPhotoUri;
    @Nullable private File currentCameraPhotoFile;

    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    launchCameraCapture();
                } else {
                    handleCameraPermissionDenied();
                }
            });

    private final ActivityResultLauncher<Uri> takePictureLauncher =
            registerForActivityResult(new ActivityResultContracts.TakePicture(), success -> {
                if (Boolean.TRUE.equals(success)) {
                    if (currentCameraPhotoUri != null && isAdded() && viewModel != null && getContext() != null) {
                        viewModel.analyzeUri(getContext().getContentResolver(), currentCameraPhotoUri);
                    }
                } else if (currentCameraPhotoFile != null && currentCameraPhotoFile.exists()
                        && currentCameraPhotoFile.length() == 0) {
                    currentCameraPhotoFile.delete();
                }
            });

    private final ActivityResultLauncher<PickVisualMediaRequest> imagePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> {
                if (uri != null && isAdded() && viewModel != null && getContext() != null) {
                    viewModel.analyzeUri(getContext().getContentResolver(), uri);
                }
            });

    @NonNull
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        binding = FragmentResearchDemoBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (!BuildConfig.DEBUG) {
            binding.layoutResearchContent.setVisibility(View.GONE);
            binding.cardModelsUnavailable.setVisibility(View.VISIBLE);
            binding.txtModelsUnavailable.setText(R.string.research_disabled_in_release);
            try {
                NavHostFragment.findNavController(this).popBackStack();
            } catch (Exception ignored) {
                // Not attached to NavHost
            }
            return;
        }

        Context appCtx = requireContext().getApplicationContext();
        AppExecutors executors = ((CropDocApplication) requireActivity().getApplication())
                .getAppContainer().getExecutors();
        viewModel = new ViewModelProvider(
                this, ResearchDemoViewModel.provideFactory(appCtx, executors))
                .get(ResearchDemoViewModel.class);

        setupButtons();
        observeState();
    }

    private void setupButtons() {
        binding.btnCaptureResearchPhoto.setOnClickListener(v -> onCapturePhotoClicked());
        binding.btnPickResearchImage.setOnClickListener(v -> launchPicker());
        binding.researchModeGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked || viewModel == null) {
                return;
            }
            if (checkedId == R.id.btn_mode_cotton_pilot) {
                viewModel.setResearchMode(ResearchDemoViewModel.ResearchMode.COTTON_BINARY_PILOT);
            } else if (checkedId == R.id.btn_mode_six_class) {
                viewModel.setResearchMode(ResearchDemoViewModel.ResearchMode.SIX_CLASS_RICE_GROUNDNUT);
            }
        });
        binding.researchModeGroup.check(viewModel.getResearchMode()
                == ResearchDemoViewModel.ResearchMode.COTTON_BINARY_PILOT
                ? R.id.btn_mode_cotton_pilot : R.id.btn_mode_six_class);
        binding.btnSampleRice.setOnClickListener(v -> {
            binding.researchModeGroup.check(R.id.btn_mode_six_class);
            viewModel.loadSampleRiceLeaf();
        });
        binding.btnSampleSoil.setOnClickListener(v -> viewModel.loadSampleNonLeafSoil());
        binding.btnRunResearchInference.setOnClickListener(v -> {
            Bitmap b = getPreviewBitmap();
            if (b != null) {
                viewModel.analyzeBitmap(b);
            }
        });
        binding.btnRetryResearch.setOnClickListener(v -> {
            Bitmap b = getPreviewBitmap();
            if (b != null) {
                viewModel.analyzeBitmap(b);
            } else {
                launchPicker();
            }
        });
    }

    private void onCapturePhotoClicked() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            launchCameraCapture();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchCameraCapture() {
        Context ctx = getContext();
        if (ctx == null) {
            return;
        }
        try {
            currentCameraPhotoFile = new File(
                    ctx.getCacheDir(), "research_cam_" + System.currentTimeMillis() + ".jpg");
            currentCameraPhotoUri = FileProvider.getUriForFile(
                    ctx, ctx.getPackageName() + ".fileprovider", currentCameraPhotoFile);
            takePictureLauncher.launch(currentCameraPhotoUri);
        } catch (Throwable t) {
            if (binding != null) {
                binding.cardResearchError.setVisibility(View.VISIBLE);
                binding.txtResearchErrorTitle.setText(R.string.research_error_title);
                binding.txtResearchErrorMessage.setText(t.getMessage());
            }
        }
    }

    private void handleCameraPermissionDenied() {
        if (binding != null) {
            binding.cardResearchError.setVisibility(View.VISIBLE);
            binding.txtResearchErrorTitle.setText(R.string.research_camera_permission_denied_title);
            binding.txtResearchErrorMessage.setText(R.string.research_camera_permission_denied_msg);
        }
    }

    private void launchPicker() {
        imagePickerLauncher.launch(new PickVisualMediaRequest.Builder()
                .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE)
                .build());
    }

    @Nullable
    private Bitmap getPreviewBitmap() {
        ResearchDemoState s = viewModel != null ? viewModel.getState().getValue() : null;
        return s != null ? s.getPreviewBitmap() : null;
    }

    private void observeState() {
        viewModel.getState().observe(getViewLifecycleOwner(), state -> {
            if (binding == null || state == null) {
                return;
            }
            renderLoadingAndButtons(state);
            renderPreview(state);
            renderOutcome(state);
        });
    }

    private void renderLoadingAndButtons(@NonNull ResearchDemoState state) {
        boolean loading = (state.getStatus() == ResearchDemoState.Status.ANALYZING);
        binding.progressResearch.setVisibility(loading ? View.VISIBLE : View.GONE);
        binding.btnCaptureResearchPhoto.setEnabled(!loading);
        binding.btnPickResearchImage.setEnabled(!loading);
        binding.btnSampleRice.setEnabled(!loading);
        binding.btnSampleSoil.setEnabled(!loading);
        binding.btnRunResearchInference.setEnabled(!loading && state.getPreviewBitmap() != null);
        binding.btnModeSixClass.setEnabled(!loading);
        binding.btnModeCottonPilot.setEnabled(!loading);
        boolean activeModelAvailable = state.isModelsAvailable();
        binding.cardModelsUnavailable.setVisibility(activeModelAvailable ? View.GONE : View.VISIBLE);
        if (!activeModelAvailable && viewModel != null) {
            binding.txtModelsUnavailable.setText(viewModel.getCurrentModelUnavailableMessage());
        }

        boolean isError = (state.getStatus() == ResearchDemoState.Status.ERROR);
        binding.cardResearchError.setVisibility(isError ? View.VISIBLE : View.GONE);
        if (isError) {
            binding.txtResearchErrorMessage.setText(state.getMessage());
        }
    }

    private void renderPreview(@NonNull ResearchDemoState state) {
        if (state.getPreviewBitmap() != null) {
            binding.imgResearchPreview.setImageBitmap(state.getPreviewBitmap());
            binding.txtPreviewPlaceholder.setVisibility(View.GONE);
        } else {
            binding.imgResearchPreview.setImageDrawable(null);
            binding.txtPreviewPlaceholder.setVisibility(View.VISIBLE);
            int placeholderText = R.string.research_preview_placeholder;
            if (state.getStatus() == ResearchDemoState.Status.ANALYZING) {
                placeholderText = viewModel != null && viewModel.isCottonMode()
                        ? R.string.research_loading_text_cotton : R.string.research_loading_text;
            }
            binding.txtPreviewPlaceholder.setText(placeholderText);
        }
    }

    private void renderOutcome(@NonNull ResearchDemoState state) {
        Context ctx = requireContext();
        com.cropdoc.app.ml.research.CottonBinaryResearchClassifier.Prediction cottonPrediction =
                state.getCottonPrediction();
        if (cottonPrediction != null) {
            binding.cardResults.setVisibility(View.GONE);
            binding.cardCottonResearchResult.setVisibility(View.VISIBLE);
            binding.txtCottonPilotResult.setText(ResearchUiFormatter.getLocalizedDiseaseName(
                    ctx, cottonPrediction.getClassId()));
            binding.txtCottonPilotScore.setText(ctx.getString(
                    R.string.research_cotton_pilot_score_fmt,
                    Math.round(cottonPrediction.getModelScore() * 100f),
                    cottonPrediction.getInferenceTimeMs()));
            binding.txtCottonPilotDisclaimer.setText(R.string.research_cotton_pilot_disclaimer);
            return;
        }
        binding.cardCottonResearchResult.setVisibility(View.GONE);
        ResearchHarnessOutcome outcome = state.getOutcome();
        if (outcome == null) {
            binding.cardResults.setVisibility(View.GONE);
            return;
        }
        binding.cardResults.setVisibility(View.VISIBLE);
        ResearchUiFormatter.bindQualityStage(binding, ctx, outcome);
        ResearchUiFormatter.bindFoliarStage(binding, ctx, outcome);
        ResearchUiFormatter.bindDomainStage(binding, ctx, outcome);
        ResearchUiFormatter.bindDiseaseStage(binding, ctx, outcome);
        ResearchUiFormatter.bindDecisionStage(binding, ctx, outcome);
        binding.txtLatencySummary.setText(ResearchUiFormatter.formatLatency(ctx, state));
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        currentCameraPhotoUri = null;
        currentCameraPhotoFile = null;
        binding = null;
    }
}
