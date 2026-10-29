package com.cropdoc.app.ui.auth;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.ui.common.Result;
import java.util.Objects;

/**
 * ViewModel handling Guest Mode, Email/Password sign-in & registration, Password Reset,
 * Google ID Token authentication, Sign-Out, and DPDP Account Deletion.
 */
public final class AuthViewModel extends ViewModel {

    private static final int MIN_PASSWORD_LENGTH = 6;

    @NonNull
    private final AuthRepository authRepository;

    @NonNull
    private final ScanRepository scanRepository;

    @NonNull
    private final AppExecutors executors;

    final MutableLiveData<Result<String>> authState = new MutableLiveData<>();
    final MutableLiveData<String> statusMessage = new MutableLiveData<>();

    public AuthViewModel(
            @NonNull AuthRepository authRepository,
            @NonNull ScanRepository scanRepository,
            @NonNull AppExecutors executors) {
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    @NonNull
    public LiveData<Result<String>> getAuthState() {
        return authState;
    }

    @NonNull
    public LiveData<String> getStatusMessage() {
        return statusMessage;
    }

    @NonNull
    public String getCurrentUserId() {
        return authRepository.getCurrentUserId();
    }

    public boolean isGuestUser() {
        return authRepository.isGuestUser();
    }

    public void signInWithEmail(@NonNull String email, @NonNull String password) {
        String trimmedEmail = email.trim();
        if (!isValidEmail(trimmedEmail)) {
            authState.setValue(Result.error("ERROR_INVALID_EMAIL"));
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            authState.setValue(Result.error("ERROR_SHORT_PASSWORD"));
            return;
        }
        authState.setValue(Result.loading());
        authRepository.signInWithEmail(trimmedEmail, password, createCallback());
    }

    public void registerWithEmail(@NonNull String email, @NonNull String password) {
        String trimmedEmail = email.trim();
        if (!isValidEmail(trimmedEmail)) {
            authState.setValue(Result.error("ERROR_INVALID_EMAIL"));
            return;
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            authState.setValue(Result.error("ERROR_SHORT_PASSWORD"));
            return;
        }
        authState.setValue(Result.loading());
        authRepository.registerWithEmail(trimmedEmail, password, createCallback());
    }

    public void sendPasswordReset(@NonNull String email) {
        String trimmedEmail = email.trim();
        if (!isValidEmail(trimmedEmail)) {
            authState.setValue(Result.error("ERROR_INVALID_EMAIL"));
            return;
        }
        authState.setValue(Result.loading());
        authRepository.sendPasswordReset(trimmedEmail, new AuthRepository.AuthCallback() {
            @Override
            public void onSuccess(@NonNull String userId, boolean isGuest) {
                authState.postValue(null);
                statusMessage.postValue("RESET_SENT");
            }

            @Override
            public void onError(@NonNull String message) {
                authState.postValue(Result.error(message));
            }
        });
    }

    public void signInWithGoogleIdToken(@NonNull String idToken) {
        if (idToken.trim().isEmpty()) {
            authState.setValue(Result.error("ERROR_GOOGLE_TOKEN"));
            return;
        }
        authState.setValue(Result.loading());
        authRepository.signInWithGoogleToken(idToken, createCallback());
    }

    public void continueAsGuest() {
        authState.setValue(Result.loading());
        authRepository.continueAsGuest(createCallback());
    }

    public void signOut() {
        authRepository.signOut();
        authState.setValue(null);
        statusMessage.setValue("SIGNED_OUT");
    }

    public void deleteAccountAndData() {
        String uid = authRepository.getCurrentUserId();
        authState.setValue(Result.loading());
        executors.diskIo().execute(() -> {
            try {
                scanRepository.deleteAllUserScansSync(uid);
                authRepository.deleteCurrentAccount(new AuthRepository.AuthCallback() {
                    @Override
                    public void onSuccess(@NonNull String userId, boolean isGuest) {
                        authState.postValue(null);
                        statusMessage.postValue("ACCOUNT_DELETED");
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        authState.postValue(Result.error(message));
                    }
                });
            } catch (Throwable e) {
                authState.postValue(Result.error("Account deletion failed"));
            }
        });
    }

    public void clearAuthState() {
        authState.setValue(null);
    }

    private static boolean isValidEmail(@NonNull String email) {
        int at = email.indexOf('@');
        int dot = email.lastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < email.length() - 1;
    }

    @NonNull
    private AuthRepository.AuthCallback createCallback() {
        return new AuthRepository.AuthCallback() {
            @Override
            public void onSuccess(@NonNull String userId, boolean isGuest) {
                authState.postValue(Result.success(userId));
            }

            @Override
            public void onError(@NonNull String message) {
                authState.postValue(Result.error(message));
            }
        };
    }
}
