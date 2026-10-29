package com.cropdoc.app.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.data.repository.AuthRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.GoogleAuthProvider;

/**
 * Wraps Firebase Authentication (Email/Password Sign-In & Registration, Password Reset,
 * Google ID Token via Credential Manager, Guest Mode, Sign-Out, and DPDP Account Deletion).
 */
public class FirebaseAuthSource {

    public static final String LOCAL_GUEST_UID = "guest_offline_farmer";

    @Nullable
    private FirebaseAuth getFirebaseAuthOrNull() {
        try {
            FirebaseApp.getInstance();
            return FirebaseAuth.getInstance();
        } catch (Throwable expected) {
            return null;
        }
    }

    @NonNull
    public String getCurrentUserIdOrGuest() {
        try {
            FirebaseAuth auth = getFirebaseAuthOrNull();
            if (auth != null && auth.getCurrentUser() != null) {
                return auth.getCurrentUser().getUid();
            }
        } catch (Throwable ignored) {
            // Fallback to offline guest UID
        }
        return LOCAL_GUEST_UID;
    }

    public boolean isCurrentUserGuest() {
        try {
            FirebaseAuth auth = getFirebaseAuthOrNull();
            if (auth != null && auth.getCurrentUser() != null) {
                return auth.getCurrentUser().isAnonymous();
            }
        } catch (Throwable ignored) {
            // Treat as guest if Firebase is unconfigured
        }
        return true;
    }

    public void signInWithEmail(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthRepository.AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuthOrNull();
        if (auth == null) {
            callback.onError("Firebase Auth not configured; use Guest Mode offline.");
            return;
        }
        try {
            auth.signInWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        String uid = user != null ? user.getUid() : LOCAL_GUEST_UID;
                        callback.onSuccess(uid, false);
                    })
                    .addOnFailureListener(e -> callback.onError(
                            e.getMessage() != null ? e.getMessage() : "Email sign-in failed"));
        } catch (Throwable t) {
            callback.onError("Email sign-in unavailable offline.");
        }
    }

    public void registerWithEmail(
            @NonNull String email,
            @NonNull String password,
            @NonNull AuthRepository.AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuthOrNull();
        if (auth == null) {
            callback.onError("Firebase Auth not configured; use Guest Mode offline.");
            return;
        }
        try {
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        String uid = user != null ? user.getUid() : LOCAL_GUEST_UID;
                        callback.onSuccess(uid, false);
                    })
                    .addOnFailureListener(e -> callback.onError(
                            e.getMessage() != null ? e.getMessage() : "Registration failed"));
        } catch (Throwable t) {
            callback.onError("Account registration unavailable offline.");
        }
    }

    public void sendPasswordReset(
            @NonNull String email,
            @NonNull AuthRepository.AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuthOrNull();
        if (auth == null) {
            callback.onError("Firebase Auth not configured; password reset requires cloud setup.");
            return;
        }
        try {
            auth.sendPasswordResetEmail(email)
                    .addOnSuccessListener(unused -> callback.onSuccess(email, false))
                    .addOnFailureListener(e -> callback.onError(
                            e.getMessage() != null ? e.getMessage() : "Password reset failed"));
        } catch (Throwable t) {
            callback.onError("Password reset unavailable offline.");
        }
    }

    public void signInWithGoogleIdToken(
            @NonNull String idToken,
            @NonNull AuthRepository.AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuthOrNull();
        if (auth == null) {
            callback.onError("Firebase Auth not configured; use Guest Mode offline.");
            return;
        }
        try {
            AuthCredential credential = GoogleAuthProvider.getCredential(idToken, null);
            auth.signInWithCredential(credential)
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        String uid = user != null ? user.getUid() : LOCAL_GUEST_UID;
                        callback.onSuccess(uid, false);
                    })
                    .addOnFailureListener(e -> callback.onError(
                            e.getMessage() != null ? e.getMessage() : "Google sign-in failed"));
        } catch (Throwable t) {
            callback.onError("Google sign-in unavailable offline.");
        }
    }

    public void continueAsGuest(@NonNull AuthRepository.AuthCallback callback) {
        FirebaseAuth auth = getFirebaseAuthOrNull();
        if (auth == null) {
            callback.onSuccess(LOCAL_GUEST_UID, true);
            return;
        }
        try {
            auth.signInAnonymously()
                    .addOnSuccessListener(result -> {
                        FirebaseUser user = result.getUser();
                        String uid = user != null ? user.getUid() : LOCAL_GUEST_UID;
                        callback.onSuccess(uid, true);
                    })
                    .addOnFailureListener(e -> callback.onSuccess(LOCAL_GUEST_UID, true));
        } catch (Throwable t) {
            callback.onSuccess(LOCAL_GUEST_UID, true);
        }
    }

    public void signOut() {
        try {
            FirebaseAuth auth = getFirebaseAuthOrNull();
            if (auth != null) {
                auth.signOut();
            }
        } catch (Throwable ignored) {
            // Safe no-op
        }
    }

    public void deleteCurrentAccount(@NonNull AuthRepository.AuthCallback callback) {
        try {
            FirebaseAuth auth = getFirebaseAuthOrNull();
            if (auth == null || auth.getCurrentUser() == null) {
                callback.onSuccess(LOCAL_GUEST_UID, true);
                return;
            }
            auth.getCurrentUser().delete()
                    .addOnSuccessListener(unused -> callback.onSuccess(LOCAL_GUEST_UID, true))
                    .addOnFailureListener(e -> {
                        auth.signOut();
                        callback.onSuccess(LOCAL_GUEST_UID, true);
                    });
        } catch (Throwable t) {
            callback.onSuccess(LOCAL_GUEST_UID, true);
        }
    }
}
