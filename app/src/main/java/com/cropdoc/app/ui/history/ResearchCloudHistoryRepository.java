package com.cropdoc.app.ui.history;

import android.content.Context;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Cloud sync for metadata-only experimental scan history. Images remain on-device. */
public final class ResearchCloudHistoryRepository {
    private static final String TAG = "ResearchCloudHistory";
    public static final String LOCAL_GUEST_OWNER = "local_guest";
    public static final String COLLECTION = "research_scans";
    private static final int PAGE_SIZE = 100;

    private final Context appContext;

    public ResearchCloudHistoryRepository(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    @Nullable
    private FirebaseApp getAppOrNull() {
        try {
            if (FirebaseApp.getApps(appContext).isEmpty()) return null;
            return FirebaseApp.getInstance();
        } catch (Throwable t) {
            Log.e(TAG, "Firebase is not initialized for this application.", t);
            return null;
        }
    }

    /** Returns a UID only for a real email/Google account, never Guest/anonymous mode. */
    @Nullable
    public String getSignedInUid() {
        FirebaseApp app = getAppOrNull();
        if (app == null) return null;
        try {
            FirebaseUser user = FirebaseAuth.getInstance(app).getCurrentUser();
            return user != null && !user.isAnonymous() ? user.getUid() : null;
        } catch (Throwable t) {
            Log.e(TAG, "Could not resolve current Firebase account.", t);
            return null;
        }
    }

    public interface UploadCallback {
        void onSuccess();
        void onFailure(@NonNull String message);
    }

    /** Backwards-compatible fire-and-forget overload. */
    public void uploadAttempt(@NonNull String ownerUid, @NonNull ResearchScanHistoryStore.Entry entry) {
        uploadAttempt(ownerUid, entry, new UploadCallback() {
            @Override public void onSuccess() { }
            @Override public void onFailure(@NonNull String message) { }
        });
    }

    /**
     * Uploads a bounded metadata document to users/{uid}/research_scans/{scanId}.
     * This deliberately does not upload images, save a verified diagnosis, or request advice.
     */
    public void uploadAttempt(
            @NonNull String ownerUid,
            @NonNull ResearchScanHistoryStore.Entry entry,
            @NonNull UploadCallback callback) {
        if (LOCAL_GUEST_OWNER.equals(ownerUid)) {
            Log.i(TAG, "Research scan remains local: the scan was created in Guest mode.");
            callback.onFailure("Guest mode is local-only. Sign in with email or Google to sync scans.");
            return;
        }

        FirebaseApp app = getAppOrNull();
        if (app == null) {
            callback.onFailure("Firebase is not initialized. Check app Firebase configuration.");
            return;
        }
        String currentUid = getSignedInUid();
        if (currentUid == null) {
            Log.w(TAG, "Research scan remains local: no non-anonymous Firebase user is signed in.");
            callback.onFailure("No signed-in account. Guest mode cannot upload research history.");
            return;
        }
        if (!currentUid.equals(ownerUid)) {
            Log.w(TAG, "Skipping upload because the current account does not own this local entry.");
            callback.onFailure("The saved scan belongs to a different account; it was not uploaded.");
            return;
        }

        Map<String, Object> data = new HashMap<>();
        data.put("scanId", safe(entry.id, 64));
        data.put("userId", ownerUid);
        data.put("cropKey", safe(entry.cropKey, 32));
        data.put("status", safe(entry.status, 24));
        data.put("classId", entry.classId == null ? "" : safe(entry.classId, 64));
        data.put("modelScore", safeScore(entry.modelScore));
        data.put("scoreType", "uncalibrated_model_score");
        data.put("inferenceTimeMs", Math.max(0L, entry.inferenceTimeMs));
        data.put("timestampMs", Math.max(1L, entry.timestampMs));
        data.put("message", safe(entry.message, 500));
        data.put("modelType", "debug_research_scan");
        data.put("researchOnly", true);

        String path = "users/" + ownerUid + "/" + COLLECTION + "/" + entry.id;
        Log.i(TAG, "Attempting Firestore write to " + path
                + " (project=" + String.valueOf(app.getOptions().getProjectId()) + ").");
        try {
            FirebaseFirestore.getInstance(app).collection("users").document(ownerUid)
                    .collection(COLLECTION).document(entry.id).set(data)
                    .addOnSuccessListener(unused -> {
                        Log.i(TAG, "Firestore write acknowledged: " + path);
                        callback.onSuccess();
                    })
                    .addOnFailureListener(error -> {
                        String message = safe(error.getMessage(), 240);
                        Log.e(TAG, "Firestore write FAILED for " + path + ": " + message, error);
                        callback.onFailure(message.isEmpty()
                                ? "Firestore rejected the write. Check published rules and network."
                                : message);
                    });
        } catch (Throwable t) {
            String message = safe(t.getMessage(), 240);
            Log.e(TAG, "Could not start Firestore write for " + path + ": " + message, t);
            callback.onFailure(message.isEmpty() ? "Could not start Firestore write." : message);
        }
    }

    public interface CloudHistoryCallback {
        void onSuccess(@NonNull List<ResearchScanHistoryStore.Entry> entries);
        void onFailure(@NonNull String message);
    }

    /** Fetches only the current authenticated user's research-scans collection. */
    public void fetchCurrentUserHistory(@NonNull CloudHistoryCallback callback) {
        String uid = getSignedInUid();
        if (uid == null) {
            callback.onFailure("Sign in with email or Google to load cloud history. Guest history stays on this device.");
            return;
        }
        FirebaseApp app = getAppOrNull();
        if (app == null) {
            callback.onFailure("Firebase is not initialized.");
            return;
        }
        final String projectId = String.valueOf(app.getOptions().getProjectId());
        final com.google.firebase.firestore.CollectionReference collection =
                FirebaseFirestore.getInstance(app).collection("users").document(uid)
                        .collection(COLLECTION);
        try {
            collection.orderBy("timestampMs", Query.Direction.DESCENDING)
                    .limit(PAGE_SIZE).get()
                    .addOnSuccessListener(snapshot -> {
                        if (!snapshot.isEmpty()) {
                            finishHistoryFetch(uid, projectId, snapshot, false, callback);
                            return;
                        }
                        // Firestore orderBy omits documents that do not contain timestampMs.
                        Log.w(TAG, "Ordered research_scans query returned 0; retrying without orderBy."
                                + " projectId=" + projectId + ", uidSuffix=" + uidSuffix(uid));
                        collection.limit(PAGE_SIZE).get()
                                .addOnSuccessListener(fallback ->
                                        finishHistoryFetch(uid, projectId, fallback, true, callback))
                                .addOnFailureListener(error -> {
                                    String message = safe(error.getMessage(), 240);
                                    Log.e(TAG, "Unordered research_scans read failed. projectId="
                                            + projectId + ", uidSuffix=" + uidSuffix(uid)
                                            + ", message=" + message, error);
                                    callback.onFailure(message.isEmpty()
                                            ? "Could not load research history from Firestore."
                                            : message);
                                });
                    })
                    .addOnFailureListener(error -> {
                        String message = safe(error.getMessage(), 240);
                        Log.e(TAG, "Ordered research_scans read failed. projectId=" + projectId
                                + ", uidSuffix=" + uidSuffix(uid) + ", message=" + message, error);
                        callback.onFailure(message.isEmpty()
                                ? "Could not load research history from Firestore."
                                : message);
                    });
        } catch (Throwable t) {
            String message = safe(t.getMessage(), 240);
            Log.e(TAG, "Could not start research_scans query. projectId=" + projectId
                    + ", uidSuffix=" + uidSuffix(uid) + ", message=" + message, t);
            callback.onFailure(message.isEmpty() ? "Could not start cloud history read." : message);
        }
    }

    private void finishHistoryFetch(
            @NonNull String uid,
            @NonNull String projectId,
            @NonNull QuerySnapshot snapshot,
            boolean unorderedFallback,
            @NonNull CloudHistoryCallback callback) {
        List<ResearchScanHistoryStore.Entry> parsed = parseEntries(uid, snapshot);
        int skipped = Math.max(0, snapshot.size() - parsed.size());
        Log.i(TAG, "Research cloud read succeeded. projectId=" + projectId
                + ", uidSuffix=" + uidSuffix(uid)
                + ", path=users/{uid}/research_scans"
                + ", rawDocuments=" + snapshot.size()
                + ", parsed=" + parsed.size()
                + ", skipped=" + skipped
                + ", unorderedFallback=" + unorderedFallback);
        if (!snapshot.isEmpty() && parsed.isEmpty()) {
            callback.onFailure("Found " + snapshot.size()
                    + " documents in research_scans, but none match CropDoc's expected research-scan fields."
                    + " Check scanId, userId, cropKey, status, timestampMs, and researchOnly=true.");
            return;
        }
        callback.onSuccess(parsed);
    }

    @NonNull
    private static String uidSuffix(@Nullable String uid) {
        if (uid == null || uid.isEmpty()) return "<none>";
        return uid.length() <= 6 ? uid : uid.substring(uid.length() - 6);
    }

    @NonNull
    private static List<ResearchScanHistoryStore.Entry> parseEntries(
            @NonNull String uid, @NonNull QuerySnapshot snapshot) {
        List<ResearchScanHistoryStore.Entry> result = new ArrayList<>();
        for (DocumentSnapshot doc : snapshot.getDocuments()) {
            if (!Boolean.TRUE.equals(doc.getBoolean("researchOnly"))) continue;
            String id = doc.getString("scanId");
            String userId = doc.getString("userId");
            String crop = doc.getString("cropKey");
            String status = doc.getString("status");
            Long timestamp = doc.getLong("timestampMs");
            if (id == null || !id.equals(doc.getId()) || !uid.equals(userId)
                    || crop == null || status == null || timestamp == null) continue;
            String classId = doc.getString("classId");
            Double score = doc.getDouble("modelScore");
            Long inference = doc.getLong("inferenceTimeMs");
            String message = doc.getString("message");
            result.add(new ResearchScanHistoryStore.Entry(
                    id, crop, status, classId == null || classId.isEmpty() ? null : classId,
                    score == null ? 0f : safeScore(score.floatValue()),
                    inference == null ? 0L : Math.max(0L, inference), timestamp,
                    message == null ? "" : message, "", uid, true, true));
        }
        return Collections.unmodifiableList(result);
    }

    @NonNull
    private static String safe(@Nullable String value, int maxLength) {
        if (value == null) return "";
        String cleaned = value.replace('\u0000', ' ').trim();
        return cleaned.length() <= maxLength ? cleaned : cleaned.substring(0, maxLength);
    }

    private static float safeScore(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return 0f;
        return Math.max(0f, Math.min(1f, value));
    }
}
