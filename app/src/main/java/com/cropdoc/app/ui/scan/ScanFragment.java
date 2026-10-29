package com.cropdoc.app.ui.scan;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentScanBinding;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ui.common.UiMotionHelper;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
/** CameraX leaf scanner with reticle surround, torch toggle, permission card, and picker. */
public final class ScanFragment extends Fragment {
    @Nullable private FragmentScanBinding binding;
    @Nullable private ImageCapture imageCapture;
    @Nullable private Camera activeCamera;
    private boolean torchEnabled;
    @Nullable private ScanResearchViewModel modelBundleViewModel;
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) { startCameraPreview(); } else { showCameraPermissionDeniedFeedback(); }
            });
    private final ActivityResultLauncher<PickVisualMediaRequest> pickPhotoLauncher =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), this::handlePickedGalleryUri);
    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        binding = FragmentScanBinding.inflate(inf, c, false);
        return binding.getRoot();
    }
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        CropDocApplication application = (CropDocApplication) requireActivity().getApplication();
        AppExecutors executors = application.getAppContainer().getExecutors();
        modelBundleViewModel = new ViewModelProvider(
                this, ScanResearchViewModel.provideFactory(requireContext(), executors))
                .get(ScanResearchViewModel.class);
        setupModelBundleHeader();
        observeModelBundleResult();
        setupButtons();
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCameraPreview();
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }
    @Override
    public void onResume() {
        super.onResume();
        boolean idle = modelBundleViewModel == null || !modelBundleViewModel.isAnalyzing();
        setCaptureEnabled(idle);
    }

    private void setupModelBundleHeader() {
        if (binding == null) {
            return;
        }
        binding.scanResearchHeader.setVisibility(View.VISIBLE);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(
                requireContext(), R.array.scan_research_crop_options,
                android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        binding.spinnerScanCrop.setAdapter(adapter);
        binding.spinnerScanCrop.setSelection(0);
        binding.btnCaptureLeaf.setText(R.string.scan_research_capture_button);
        binding.btnPickGallery.setText(R.string.scan_research_gallery_button);
    }

    private boolean ensureCropSelected() {
        if (binding == null || getSelectedResearchCropKey().isEmpty()) {
            showShortSnackbar(R.string.scan_research_choose_crop_first);
            return false;
        }
        return true;
    }

    @NonNull
    private String getSelectedResearchCropKey() {
        if (binding == null) {
            return "";
        }
        // Spinner item 0 is a placeholder. Never silently default to Rice because that can
        // produce a plausible-looking but crop-inappropriate prediction for a different leaf.
        int position = binding.spinnerScanCrop.getSelectedItemPosition();
        if (position == 1) {
            return "rice";
        }
        if (position == 2) {
            return "groundnut";
        }
        if (position == 3) {
            return "cotton";
        }
        return "";
    }

    private boolean requireResearchCropSelected() {
        return ensureCropSelected();
    }
    private void setupButtons() {
        if (binding == null) { return; }
        UiMotionHelper.attachPressFeedback(
                binding.btnCaptureLeaf, binding.btnPickGallery, binding.btnToggleTorch);
        binding.btnCaptureLeaf.setOnClickListener(v -> captureLeafPhoto());
        binding.btnPickGallery.setOnClickListener(v -> {
            if (!requireResearchCropSelected()) {
                return;
            }
            try {
                setCaptureEnabled(false);
                pickPhotoLauncher.launch(new PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build());
            } catch (Throwable e) {
                setCaptureEnabled(true);
                showShortSnackbar(R.string.error_image_decode_failed);
            }
        });
        binding.btnToggleTorch.setOnClickListener(v -> toggleTorch());
    }
    void setCaptureEnabled(boolean enabled) {
        if (binding != null) {
            binding.btnCaptureLeaf.setEnabled(enabled);
            binding.btnPickGallery.setEnabled(enabled);
            binding.spinnerScanCrop.setEnabled(enabled);
        }
    }
    private void toggleTorch() {
        if (activeCamera != null && activeCamera.getCameraInfo().hasFlashUnit() && binding != null) {
            torchEnabled = !torchEnabled;
            activeCamera.getCameraControl().enableTorch(torchEnabled);
            binding.btnToggleTorch.setText(torchEnabled ? R.string.btn_toggle_torch_on : R.string.btn_toggle_torch_off);
        }
    }
    private void showCameraPermissionDeniedFeedback() {
        if (binding == null || !isAdded()) { return; }
        boolean canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.CAMERA);
        binding.cardCameraPermission.setVisibility(View.VISIBLE);
        binding.btnGrantCameraInline.setText(
                canAskAgain ? R.string.action_grant_camera : R.string.action_open_settings);
        binding.btnGrantCameraInline.setOnClickListener(v -> {
            if (canAskAgain) {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            } else {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", requireContext().getPackageName(), null)));
            }
        });
    }
    private void startCameraPreview() {
        if (binding != null) {
            binding.cardCameraPermission.setVisibility(View.GONE);
        }
        ListenableFuture<ProcessCameraProvider> fut = ProcessCameraProvider.getInstance(requireContext());
        fut.addListener(() -> bindCameraUseCases(fut), ContextCompat.getMainExecutor(requireContext()));
    }
    private void bindCameraUseCases(@NonNull ListenableFuture<ProcessCameraProvider> future) {
        try {
            ProcessCameraProvider provider = future.get();
            Preview preview = new Preview.Builder().build();
            preview.setSurfaceProvider(binding.cameraPreviewView.getSurfaceProvider());
            imageCapture = new ImageCapture.Builder().build();
            provider.unbindAll();
            CameraSelector selector = provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)
                    ? CameraSelector.DEFAULT_BACK_CAMERA : CameraSelector.DEFAULT_FRONT_CAMERA;
            activeCamera = provider.bindToLifecycle(
                    getViewLifecycleOwner(), selector, preview, imageCapture);
            binding.btnToggleTorch.setVisibility(
                    activeCamera.getCameraInfo().hasFlashUnit() ? View.VISIBLE : View.GONE);
        } catch (Throwable ignored) { }
    }
    private void captureLeafPhoto() {
        if (!requireResearchCropSelected()) {
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            showCameraPermissionDeniedFeedback();
            return;
        }
        if (imageCapture == null || binding == null) {
            showShortSnackbar(R.string.error_camera_capture);
            return;
        }
        setCaptureEnabled(false);
        final int pw = binding.cameraPreviewView.getWidth(), ph = binding.cameraPreviewView.getHeight();
        final int rw = binding.leafReticleBox.getWidth(), rh = binding.leafReticleBox.getHeight();
        try {
            File temp = new File(requireContext().getCacheDir(), "raw_" + System.currentTimeMillis() + ".jpg");
            ImageCapture.OutputFileOptions opts = new ImageCapture.OutputFileOptions.Builder(temp).build();
            imageCapture.takePicture(opts, ContextCompat.getMainExecutor(requireContext()),
                    new ImageCapture.OnImageSavedCallback() {
                        @Override
                        public void onImageSaved(@NonNull ImageCapture.OutputFileResults res) {
                            Context ctx = getContext();
                            if (isAdded() && ctx != null) {
                                if (modelBundleViewModel != null) {
                                    modelBundleViewModel.analyzeImageFile(
                                            temp, getSelectedResearchCropKey(), pw, ph, rw, rh);
                                } else {
                                    setCaptureEnabled(true);
                                    showShortSnackbar(R.string.scan_research_no_result_message);
                                }
                            } else {
                                setCaptureEnabled(true);
                            }
                        }
                        @Override
                        public void onError(@NonNull ImageCaptureException exception) {
                            onCaptureFailed();
                        }
                    });
        } catch (Throwable e) {
            onCaptureFailed();
        }
    }
    private void handlePickedGalleryUri(@Nullable Uri uri) {
        Context ctx = getContext();
        if (uri == null || !isAdded() || ctx == null) {
            setCaptureEnabled(true);
            return;
        }
        if (modelBundleViewModel != null) {
            modelBundleViewModel.analyzeGalleryUri(
                    ctx.getContentResolver(), uri, ctx.getCacheDir(), getSelectedResearchCropKey());
        } else {
            setCaptureEnabled(true);
            showShortSnackbar(R.string.scan_research_no_result_message);
        }
    }
    private void observeModelBundleResult() {
        if (modelBundleViewModel == null) {
            return;
        }
        modelBundleViewModel.getState().observe(getViewLifecycleOwner(), state -> {
            if (binding == null || state == null) {
                return;
            }
            boolean loading = state.isLoading();
            binding.scanProgressIndicator.setVisibility(loading ? View.VISIBLE : View.GONE);
            setCaptureEnabled(!loading);
            if (state.isTerminal()) {
                showModelBundleOutput(state);
                modelBundleViewModel.clearResult();
            }
        });
    }

    private void showModelBundleOutput(@NonNull ScanResearchState state) {
        if (!isAdded() || getContext() == null || binding == null) {
            return;
        }
        int titleRes;
        String message;
        if (state.getStatus() == ScanResearchState.Status.RESULT) {
            titleRes = R.string.scan_research_result_title;
            String cropName = getCropLabel(state.getCropKey());
            String className = getPredictedClassLabel(state.getClassId());
            message = getString(R.string.scan_research_result_body,
                    cropName, className,
                    Math.round(state.getModelScore() * 100f),
                    state.getInferenceTimeMs());
            if (state.getMessage() != null && !state.getMessage().trim().isEmpty()) {
                message += "\n\n" + state.getMessage();
            }
            message += "\n\n" + getString(R.string.scan_research_result_disclaimer);
        } else if (state.getStatus() == ScanResearchState.Status.UNAVAILABLE) {
            titleRes = R.string.scan_research_unavailable_title;
            message = safeModelMessage(state.getMessage()) + "\n\n"
                    + getString(R.string.scan_research_result_disclaimer);
        } else if (state.getStatus() == ScanResearchState.Status.ERROR) {
            titleRes = R.string.scan_research_error_title;
            message = safeModelMessage(state.getMessage()) + "\n\n"
                    + getString(R.string.scan_research_result_disclaimer);
        } else {
            titleRes = R.string.scan_research_rejected_title;
            message = safeModelMessage(state.getMessage()) + "\n\n"
                    + getString(R.string.scan_research_result_disclaimer);
        }

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(titleRes)
                .setMessage(message)
                .setPositiveButton(R.string.action_dismiss, null)
                .show();
    }

    @NonNull
    private String safeModelMessage(@Nullable String message) {
        return message == null || message.trim().isEmpty()
                ? getString(R.string.scan_research_no_result_message) : message;
    }

    @NonNull
    private String getCropLabel(@NonNull String cropKey) {
        switch (cropKey) {
            case "groundnut":
                return getString(R.string.scan_crop_groundnut);
            case "cotton":
                return getString(R.string.scan_crop_cotton);
            case "rice":
            default:
                return getString(R.string.scan_crop_rice);
        }
    }

    @NonNull
    private String getPredictedClassLabel(@Nullable String classId) {
        if (classId == null) {
            return getString(R.string.scan_research_no_result_message);
        }
        switch (classId) {
            case "rice_bacterial_leaf_blight":
                return getString(R.string.disease_rice_bacterial_blight);
            case "rice_brown_spot":
                return getString(R.string.disease_rice_brown_spot);
            case "rice_leaf_blast":
                return getString(R.string.disease_rice_blast);
            case "rice_healthy":
                return getString(R.string.disease_rice_healthy);
            case "groundnut_early_leaf_spot":
                return getString(R.string.disease_groundnut_early_leaf_spot);
            case "groundnut_healthy":
                return getString(R.string.disease_groundnut_healthy);
            case "cotton_bacterial_blight":
                return getString(R.string.scan_class_cotton_bacterial_blight);
            case "cotton_healthy":
                return getString(R.string.scan_class_cotton_healthy);
            default:
                return classId;
        }
    }

    void onCaptureFailed() {
        setCaptureEnabled(true);
        showShortSnackbar(R.string.error_camera_capture);
    }
    private void showShortSnackbar(int resId) {
        if (binding != null) {
            Snackbar.make(binding.getRoot(), resId, Snackbar.LENGTH_SHORT).show();
        }
    }
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        activeCamera = null;
        binding = null;
    }
}
