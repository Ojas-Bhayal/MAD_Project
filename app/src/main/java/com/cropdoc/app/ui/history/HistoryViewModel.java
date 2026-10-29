package com.cropdoc.app.ui.history;

import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModel;
import com.cropdoc.app.data.remote.FirebaseAuthSource;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.di.AppExecutors;
import com.cropdoc.app.domain.model.ScanRecord;
import java.util.List;
import java.util.Objects;

/**
 * Loads local Room history for the currently active Firebase UID and refreshes it from Firestore.
 * The selected UID is switchable so the History screen cannot stay bound to a former guest/account.
 */
public final class HistoryViewModel extends ViewModel {

    private static final String TAG = "CropDocCloudHistory";

    @NonNull private final ScanRepository scanRepository;
    @NonNull private final AuthRepository authRepository;
    @NonNull private final AppExecutors executors;
    @NonNull private final MutableLiveData<String> activeHistoryUserId = new MutableLiveData<>();
    @NonNull private final MutableLiveData<String> cloudHistoryStatus = new MutableLiveData<>();

    public HistoryViewModel(
            @NonNull ScanRepository scanRepository,
            @NonNull AuthRepository authRepository,
            @NonNull AppExecutors executors) {
        this.scanRepository = Objects.requireNonNull(scanRepository, "scanRepository");
        this.authRepository = Objects.requireNonNull(authRepository, "authRepository");
        this.executors = Objects.requireNonNull(executors, "executors");
        activeHistoryUserId.setValue(authRepository.getCurrentUserId());
    }

    @NonNull
    public LiveData<List<ScanRecord>> observeHistory() {
        return Transformations.switchMap(activeHistoryUserId, userId ->
                scanRepository.observeRecentScans(userId == null
                        ? FirebaseAuthSource.LOCAL_GUEST_UID : userId));
    }

    @NonNull
    public LiveData<String> observeCloudHistoryStatus() {
        return cloudHistoryStatus;
    }

    /** Pull remote records for the currently signed-in account; Guest mode remains local-only. */
    public void refreshCloudHistory() {
        final String userId = authRepository.getCurrentUserId();
        final boolean guest = authRepository.isGuestUser();
        // Rebind Room observation on every resume, in case sign-in changed while History was alive.
        activeHistoryUserId.setValue(userId);
        Log.i(TAG, "History refresh requested. guest=" + guest
                + ", uidSuffix=" + uidSuffix(userId));
        if (guest || FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) {
            cloudHistoryStatus.setValue("Cloud history is available after signing in to your account.");
            return;
        }
        scanRepository.refreshCloudHistoryAsync(userId, (raw, parsed, imported, error) -> {
            final String message;
            if (error != null) {
                message = "Cloud history sync failed: " + error;
            } else if (raw == 0) {
                // An empty production collection is normal when the account's records are
                // experimental and live in users/{uid}/research_scans instead.
                cloudHistoryStatus.postValue(null);
                return;
            } else if (parsed == 0) {
                message = "Cloud scans were found, but their fields could not be read.";
            } else {
                message = "Cloud history refreshed: found " + parsed
                        + " scan(s), restored " + imported + " to this device.";
            }
            cloudHistoryStatus.postValue(message);
        });
    }

    private static String uidSuffix(@Nullable String uid) {
        if (uid == null || uid.isEmpty()) return "<none>";
        return uid.length() <= 6 ? uid : uid.substring(uid.length() - 6);
    }

    public void deleteAllHistoryForCurrentUser() {
        String userId = authRepository.getCurrentUserId();
        executors.diskIo().execute(() -> {
            try {
                scanRepository.deleteAllUserScansSync(userId);
            } catch (Throwable ignored) {
                // Safe background history deletion
            }
        });
    }
}
