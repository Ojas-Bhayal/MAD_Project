package com.cropdoc.app.data.repository;

import androidx.annotation.NonNull;
import com.cropdoc.app.data.remote.FirebaseAuthSource;
import java.util.Objects;

/**
 * Repository coordinating user identity, registration, password reset, guest sessions, and
 * DPDP account deletion without exposing Firebase types to UI classes.
 */
public class AuthRepository {

    public interface AuthCallback {
        void onSuccess(@NonNull String userId, boolean isGuest);

        void onError(@NonNull String message);
    }

    @NonNull
    private final FirebaseAuthSource authSource;

    public AuthRepository(@NonNull FirebaseAuthSource authSource) {
        this.authSource = Objects.requireNonNull(authSource, "authSource");
    }

    @NonNull
    public String getCurrentUserId() {
        return authSource.getCurrentUserIdOrGuest();
    }

    public boolean isGuestUser() {
        return authSource.isCurrentUserGuest();
    }

    public void signInWithEmail(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthCallback callback) {
        authSource.signInWithEmail(email, password, callback);
    }

    public void registerWithEmail(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthCallback callback) {
        authSource.registerWithEmail(email, password, callback);
    }

    public void sendPasswordReset(
            @NonNull String email,
            @NonNull AuthCallback callback) {
        authSource.sendPasswordReset(email, callback);
    }

    public void signInWithGoogleToken(
            @NonNull String idToken,
            @NonNull AuthCallback callback) {
        authSource.signInWithGoogleIdToken(idToken, callback);
    }

    public void continueAsGuest(@NonNull AuthCallback callback) {
        authSource.continueAsGuest(callback);
    }

    public void signOut() {
        authSource.signOut();
    }

    public void deleteCurrentAccount(@NonNull AuthCallback callback) {
        authSource.deleteCurrentAccount(callback);
    }
}
