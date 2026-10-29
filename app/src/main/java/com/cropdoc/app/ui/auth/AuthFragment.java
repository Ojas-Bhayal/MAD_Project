package com.cropdoc.app.ui.auth;

import android.os.Bundle;
import android.os.CancellationSignal;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.NoCredentialException;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.fragment.NavHostFragment;
import com.cropdoc.app.CropDocApplication;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.FragmentAuthBinding;
import com.cropdoc.app.ui.common.Result;
import com.cropdoc.app.ui.common.UiMotionHelper;
import com.cropdoc.app.ui.common.ViewModelFactory;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

/**
 * Authentication screen with state-aware Guest vs Signed-In sections, Material 3 TextInputLayouts,
 * collapsible DPDP notice, Credential Manager Google Sign-In, and confirmation-guarded deletion.
 */
public final class AuthFragment extends Fragment {

    @Nullable private FragmentAuthBinding binding;
    private AuthViewModel viewModel;

    @NonNull
    @Override
    public View onCreateView(@NonNull LayoutInflater inf, @Nullable ViewGroup c, @Nullable Bundle b) {
        binding = FragmentAuthBinding.inflate(inf, c, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        ViewModelFactory factory = ((CropDocApplication) requireActivity().getApplication())
                .getAppContainer().getViewModelFactory();
        viewModel = new ViewModelProvider(this, factory).get(AuthViewModel.class);
        refreshAccountStateUi();
        bindClicks();
        observeAuthState();
    }

    private void refreshAccountStateUi() {
        if (binding == null) {
            return;
        }
        boolean guest = viewModel.isGuestUser();
        binding.txtAccountStatus.setText(guest
                ? getString(R.string.status_guest_mode_active)
                : getString(R.string.status_signed_in_as, viewModel.getCurrentUserId()));
        binding.groupSignInControls.setVisibility(guest ? View.VISIBLE : View.GONE);
        binding.groupSignedInActions.setVisibility(guest ? View.GONE : View.VISIBLE);
    }

    private void bindClicks() {
        if (binding == null) {
            return;
        }
        UiMotionHelper.attachPressFeedback(
                binding.btnEmailSignIn, binding.btnGoogleSignIn, binding.btnEmailRegister,
                binding.btnContinueGuest, binding.btnSignOut, binding.btnDeleteAccount);
        binding.btnToggleDpdpNotice.setOnClickListener(v -> toggleDpdpNotice());
        binding.btnContinueGuest.setOnClickListener(v -> viewModel.continueAsGuest());
        binding.btnEmailSignIn.setOnClickListener(v -> {
            clearFieldErrors();
            viewModel.signInWithEmail(readEmail(), readPassword());
        });
        binding.btnEmailRegister.setOnClickListener(v -> {
            clearFieldErrors();
            viewModel.registerWithEmail(readEmail(), readPassword());
        });
        binding.btnForgotPassword.setOnClickListener(v -> {
            clearFieldErrors();
            viewModel.sendPasswordReset(readEmail());
        });
        binding.btnGoogleSignIn.setOnClickListener(v -> launchCredentialManagerGoogleSignIn());
        binding.btnSignOut.setOnClickListener(v -> {
            viewModel.signOut();
            refreshAccountStateUi();
        });
        binding.btnDeleteAccount.setOnClickListener(v -> showConfirmDeleteAccountDialog());
    }

    private void toggleDpdpNotice() {
        if (binding == null) {
            return;
        }
        boolean visible = binding.txtDpdpNoticeBody.getVisibility() == View.VISIBLE;
        UiMotionHelper.animateExpandCollapse(binding.txtDpdpNoticeBody, !visible);
    }

    private void showConfirmDeleteAccountDialog() {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.confirm_delete_account_title)
                .setMessage(R.string.confirm_delete_account_body)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_confirm_delete,
                        (dialog, which) -> viewModel.deleteAccountAndData())
                .show();
    }

    private void clearFieldErrors() {
        if (binding != null) {
            binding.layoutInputEmail.setError(null);
            binding.layoutInputPassword.setError(null);
        }
    }

    @NonNull
    private String readEmail() {
        return (binding != null && binding.inputEmail.getText() != null)
                ? binding.inputEmail.getText().toString() : "";
    }

    @NonNull
    private String readPassword() {
        return (binding != null && binding.inputPassword.getText() != null)
                ? binding.inputPassword.getText().toString() : "";
    }

    private void observeAuthState() {
        viewModel.getAuthState().observe(getViewLifecycleOwner(), result -> {
            if (binding == null || result == null) {
                return;
            }
            binding.authProgress.setVisibility(
                    result.getStatus() == Result.Status.LOADING ? View.VISIBLE : View.GONE);
            if (result.isSuccess()) {
                refreshAccountStateUi();
                viewModel.clearAuthState();
                navigateToScanSafely();
            } else if (result.getStatus() == Result.Status.ERROR
                     && result.getErrorMessage() != null) {
                renderAuthError(result.getErrorMessage());
            }
        });
        viewModel.getStatusMessage().observe(getViewLifecycleOwner(), code -> {
            if (binding == null || code == null) {
                return;
            }
            refreshAccountStateUi();
            int msgRes = R.string.msg_signed_out;
            if ("RESET_SENT".equals(code)) {
                msgRes = R.string.msg_password_reset_sent;
            } else if ("ACCOUNT_DELETED".equals(code)) {
                msgRes = R.string.msg_account_deleted;
            }
            Snackbar.make(binding.getRoot(), msgRes, Snackbar.LENGTH_SHORT).show();
        });
    }

    private void navigateToScanSafely() {
        if (isAdded()) {
            try {
                NavHostFragment.findNavController(this).navigate(R.id.scanFragment);
            } catch (RuntimeException ignored) {
                // Safe navigation guard
            }
        }
    }

    private void renderAuthError(@NonNull String rawCode) {
        if (binding == null) {
            return;
        }
        if ("ERROR_INVALID_EMAIL".equals(rawCode)) {
            binding.layoutInputEmail.setError(getString(R.string.error_invalid_email));
            return;
        } else if ("ERROR_SHORT_PASSWORD".equals(rawCode)) {
            binding.layoutInputPassword.setError(getString(R.string.error_short_password));
            return;
        }
        String text = "ERROR_GOOGLE_TOKEN".equals(rawCode)
                ? getString(R.string.error_google_sign_in) : rawCode;
        Snackbar.make(binding.getRoot(), text, Snackbar.LENGTH_SHORT).show();
    }

    private void launchCredentialManagerGoogleSignIn() {
        try {
            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(new GetGoogleIdOption.Builder()
                            .setFilterByAuthorizedAccounts(false)
                            .setServerClientId(getString(R.string.default_web_client_id))
                            .build())
                    .build();
            CredentialManager.create(requireContext()).getCredentialAsync(
                    requireActivity(), request, new CancellationSignal(),
                    ContextCompat.getMainExecutor(requireContext()),
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse response) {
                            if (isAdded()) {
                                handleCredentialResult(response.getCredential());
                            }
                        }

                        @Override
                        public void onError(@NonNull GetCredentialException e) {
                            if (e instanceof NoCredentialException) {
                                // User has no saved Google credentials; fallback gracefully without error banner
                                return;
                            }
                            showGoogleSignInError();
                        }
                    });
        } catch (Throwable e) {
            showGoogleSignInError();
        }
    }

    void handleCredentialResult(@NonNull Credential credential) {
        if (credential instanceof CustomCredential
                && GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                        .equals(credential.getType())) {
            GoogleIdTokenCredential tokenCred = GoogleIdTokenCredential.createFrom(credential.getData());
            viewModel.signInWithGoogleIdToken(tokenCred.getIdToken());
        } else {
            showGoogleSignInError();
        }
    }

    void showGoogleSignInError() {
        if (binding != null && isAdded()) {
            Snackbar.make(binding.getRoot(), R.string.error_google_sign_in, Snackbar.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}
