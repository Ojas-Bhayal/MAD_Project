package com.cropdoc.app.data.remote;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.util.Log;
import com.cropdoc.app.data.local.entity.ScanEntity;
import com.google.android.gms.tasks.Tasks;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.FirebaseFirestoreSettings;
import com.google.firebase.firestore.PersistentCacheSettings;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.Source;
import com.google.firebase.firestore.WriteBatch;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Handles bounded Firestore pagination, idempotent offline cache configuration,
 * scan & feedback batch writes, and DPDP Act cloud data deletion.
 */
public class FirestoreSyncSource {

    public static final int PAGE_SIZE = 20;
    private static final String TAG = "CropDocFirestoreSync";
    private static final long CACHE_SIZE_BYTES = 50L * 1024L * 1024L;
    private static final long TIMEOUT_SECONDS = 15L;
    private static volatile boolean settingsConfigured = false;

    /** Result of a remote history read. Keeps empty history distinct from a failed read. */
    public static final class ScanFetchResult {
        @NonNull public final List<ScanEntity> records;
        @Nullable public final String error;
        public final int rawDocumentCount;
        public final int skippedDocumentCount;

        ScanFetchResult(@NonNull List<ScanEntity> records, @Nullable String error,
                        int rawDocumentCount, int skippedDocumentCount) {
            this.records = records;
            this.error = error;
            this.rawDocumentCount = rawDocumentCount;
            this.skippedDocumentCount = skippedDocumentCount;
        }
    }

    @Nullable
    private synchronized FirebaseFirestore getFirestoreOrNull() {
        try {
            FirebaseApp.getInstance();
            FirebaseFirestore db = FirebaseFirestore.getInstance();
            if (!settingsConfigured) {
                try {
                    FirebaseFirestoreSettings settings = new FirebaseFirestoreSettings.Builder()
                            .setLocalCacheSettings(PersistentCacheSettings.newBuilder()
                                    .setSizeBytes(CACHE_SIZE_BYTES)
                                    .build())
                            .build();
                    db.setFirestoreSettings(settings);
                } catch (Throwable ignored) {
                    // Firestore instance already started earlier in this process
                }
                settingsConfigured = true;
            }
            return db;
        } catch (Throwable expected) {
            return null;
        }
    }

    public boolean uploadScanBatch(
            @NonNull String userId,
            @NonNull List<ScanEntity> entities) {
        FirebaseFirestore db = getFirestoreOrNull();
        if (db == null || entities.isEmpty()) {
            return false;
        }
        try {
            WriteBatch batch = db.batch();
            for (ScanEntity entity : entities) {
                batch.set(
                        db.collection("users")
                                .document(userId)
                                .collection("scans")
                                .document(entity.getScanId()),
                        toScanMap(userId, entity));
                if (entity.getUserFeedbackHelpful() != null) {
                    batch.set(
                            db.collection("users")
                                    .document(userId)
                                    .collection("feedback")
                                    .document(entity.getScanId()),
                            toFeedbackMap(userId, entity));
                }
            }
            Tasks.await(batch.commit(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    public boolean deleteAllUserCloudData(@NonNull String userId) {
        FirebaseFirestore db = getFirestoreOrNull();
        if (db == null || FirebaseAuthSource.LOCAL_GUEST_UID.equals(userId)) {
            return false;
        }
        try {
            QuerySnapshot scans = Tasks.await(
                    db.collection("users").document(userId).collection("scans").get(),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            QuerySnapshot feedback = Tasks.await(
                    db.collection("users").document(userId).collection("feedback").get(),
                    TIMEOUT_SECONDS,
                    TimeUnit.SECONDS);
            WriteBatch batch = db.batch();
            for (DocumentSnapshot doc : scans.getDocuments()) {
                batch.delete(doc.getReference());
            }
            for (DocumentSnapshot doc : feedback.getDocuments()) {
                batch.delete(doc.getReference());
            }
            Tasks.await(batch.commit(), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    @NonNull
    public ScanFetchResult fetchPaginatedScans(
            @NonNull String userId,
            @Nullable DocumentSnapshot lastVisible) {
        FirebaseFirestore db = getFirestoreOrNull();
        if (db == null) {
            String message = "Firebase Firestore is not initialized.";
            Log.e(TAG, message);
            return new ScanFetchResult(new ArrayList<>(), message, 0, 0);
        }

        String authenticatedUid = null;
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() != null) {
                authenticatedUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            }
        } catch (Throwable authError) {
            Log.e(TAG, "Unable to read Firebase Auth identity for cloud history.", authError);
        }
        String projectId = null;
        try { projectId = FirebaseApp.getInstance().getOptions().getProjectId(); }
        catch (Throwable ignored) { }
        Log.i(TAG, "Cloud history read start. projectId=" + projectId
                + ", requestedUidSuffix=" + uidSuffix(userId)
                + ", authUidSuffix=" + uidSuffix(authenticatedUid)
                + ", path=users/{uid}/scans, pageSize=" + PAGE_SIZE);

        if (authenticatedUid == null || !userId.equals(authenticatedUid)) {
            String message = authenticatedUid == null
                    ? "No Firebase user is currently signed in."
                    : "History UID does not match the active Firebase account.";
            Log.e(TAG, message + " requestedUidSuffix=" + uidSuffix(userId)
                    + ", authUidSuffix=" + uidSuffix(authenticatedUid));
            return new ScanFetchResult(new ArrayList<>(), message, 0, 0);
        }

        try {
            Query query = db.collection("users")
                    .document(userId)
                    .collection("scans")
                    .orderBy("timestampMs", Query.Direction.DESCENDING)
                    .limit(PAGE_SIZE);
            if (lastVisible != null) {
                query = query.startAfter(lastVisible);
            }
            // Force a server read so account/rules failures cannot be disguised as an empty cache.
            QuerySnapshot snapshot = Tasks.await(
                    query.get(Source.SERVER), TIMEOUT_SECONDS, TimeUnit.SECONDS);
            boolean usedLegacyFallback = false;

            // Firestore orderBy(field) silently excludes documents that do not contain that field.
            // Some older CropDoc records may use `timestamp` instead of `timestampMs`. If the
            // ordered query is empty, retry the same owner's exact scans collection without orderBy.
            if (snapshot.isEmpty() && lastVisible == null) {
                QuerySnapshot fallback = Tasks.await(
                        db.collection("users").document(userId).collection("scans")
                                .get(Source.SERVER),
                        TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!fallback.isEmpty()) {
                    snapshot = fallback;
                    usedLegacyFallback = true;
                    Log.w(TAG, "Ordered scan query returned zero documents; legacy-schema "
                            + "fallback found " + fallback.size() + " raw document(s).");
                }
            }

            List<ScanEntity> list = new ArrayList<>(snapshot.size());
            int skipped = 0;
            for (DocumentSnapshot doc : snapshot.getDocuments()) {
                ScanEntity parsed = parseDocument(userId, doc);
                if (parsed != null) {
                    list.add(parsed);
                } else {
                    skipped++;
                    Log.w(TAG, "Skipped cloud scan document id=" + doc.getId()
                            + " because required fields are missing or malformed. fields="
                            + (doc.getData() == null ? "[]" : doc.getData().keySet()));
                }
            }
            if (usedLegacyFallback) {
                list.sort((left, right) -> Long.compare(
                        right.getTimestampMs(), left.getTimestampMs()));
                if (list.size() > PAGE_SIZE) {
                    list = new ArrayList<>(list.subList(0, PAGE_SIZE));
                }
            }
            Log.i(TAG, "Cloud history server read succeeded. rawDocuments="
                    + snapshot.size() + ", parsed=" + list.size() + ", skipped=" + skipped
                    + ", legacyFallback=" + usedLegacyFallback);
            return new ScanFetchResult(list, null, snapshot.size(), skipped);
        } catch (Throwable e) {
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.e(TAG, "Cloud history server read failed for users/{uid}/scans: " + detail, e);
            return new ScanFetchResult(new ArrayList<>(), detail, 0, 0);
        }
    }

    @NonNull
    private static String uidSuffix(@Nullable String uid) {
        if (uid == null || uid.isEmpty()) return "<none>";
        return uid.length() <= 6 ? uid : uid.substring(uid.length() - 6);
    }

    @Nullable
    private static ScanEntity parseDocument(
            @NonNull String userId,
            @NonNull DocumentSnapshot doc) {
        String scanId = firstString(doc, "scanId", "scan_id");
        if (scanId == null || scanId.trim().isEmpty()) scanId = doc.getId();
        String cropId = firstString(doc, "cropId", "crop_id", "crop");
        String diseaseId = firstString(doc, "diseaseId", "disease_id", "disease");
        Long ts = firstLong(doc, "timestampMs", "timestamp_ms");
        if (ts == null) {
            Object timestamp = doc.get("timestamp");
            if (timestamp instanceof com.google.firebase.Timestamp) {
                ts = ((com.google.firebase.Timestamp) timestamp).toDate().getTime();
            }
        }
        if (scanId == null || cropId == null || diseaseId == null || ts == null || ts <= 0) {
            return null;
        }
        Object confidenceValue = doc.get("confidence");
        float confidence = confidenceValue instanceof Number
                ? ((Number) confidenceValue).floatValue() : 0f;
        confidence = Math.max(0f, Math.min(1f, confidence));
        String outcome = firstString(doc, "outcome", "status");
        String modelVer = firstString(doc, "modelVersion", "model_version");
        String owner = doc.getString("userId");
        if (owner != null && !userId.equals(owner)) {
            Log.w(TAG, "Skipped document with mismatched userId; docId=" + doc.getId());
            return null;
        }
        Boolean feedback = doc.getBoolean("userFeedbackHelpful");
        return new ScanEntity(
                scanId,
                userId,
                cropId,
                diseaseId,
                confidence,
                outcome != null ? outcome : "CONFIDENT_DISEASE",
                "",
                firstString(doc, "remoteImageUrl", "remote_image_url"),
                ts,
                true,
                feedback,
                modelVer != null ? modelVer : "1.0.0-int8");
    }

    @Nullable
    private static String firstString(@NonNull DocumentSnapshot doc, @NonNull String... keys) {
        for (String key : keys) {
            Object value = doc.get(key);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                return (String) value;
            }
        }
        return null;
    }

    @Nullable
    private static Long firstLong(@NonNull DocumentSnapshot doc, @NonNull String... keys) {
        for (String key : keys) {
            Object value = doc.get(key);
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
        }
        return null;
    }

    @NonNull
    private static Map<String, Object> toScanMap(
            @NonNull String userId,
            @NonNull ScanEntity entity) {
        Map<String, Object> map = new HashMap<>();
        map.put("scanId", entity.getScanId());
        map.put("userId", userId);
        map.put("cropId", entity.getCropId());
        map.put("diseaseId", entity.getDiseaseId());
        map.put("confidence", (double) entity.getConfidence());
        map.put("outcome", entity.getOutcome());
        map.put("remoteImageUrl", entity.getRemoteImageUrl());
        map.put("timestampMs", entity.getTimestampMs());
        map.put("modelVersion", entity.getModelVersion());
        if (entity.getUserFeedbackHelpful() != null) {
            map.put("userFeedbackHelpful", entity.getUserFeedbackHelpful());
        }
        return map;
    }

    @NonNull
    private static Map<String, Object> toFeedbackMap(
            @NonNull String userId,
            @NonNull ScanEntity entity) {
        Map<String, Object> fb = new HashMap<>();
        fb.put("userId", userId);
        fb.put("scanId", entity.getScanId());
        fb.put("helpful", Boolean.TRUE.equals(entity.getUserFeedbackHelpful()));
        fb.put("modelVersion", entity.getModelVersion());
        fb.put("timestampMs", entity.getTimestampMs());
        return fb;
    }
}
