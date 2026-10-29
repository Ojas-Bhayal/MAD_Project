package com.cropdoc.app.ui.history;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.ui.scan.ScanResearchState;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local persistence for experimental Scan-screen attempts with optional user-scoped Firestore sync.
 * These entries remain separate from verified Room diagnosis records and production cloud sync.
 */
public final class ResearchScanHistoryStore {
    private static final String INDEX_NAME = "research_scan_history.json";
    private static final String IMAGE_DIR_NAME = "research_scan_history_images";
    private static final int MAX_ENTRIES = 100;
    private static final String TAG = "ResearchCloudHistory";
    private static final ExecutorService FILE_IO = Executors.newSingleThreadExecutor();
    private final Context appContext;
    private final File indexFile;
    private final File imageDirectory;
    private final ResearchCloudHistoryRepository cloudRepository;

    public ResearchScanHistoryStore(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        this.indexFile = new File(appContext.getFilesDir(), INDEX_NAME);
        this.imageDirectory = new File(appContext.getFilesDir(), IMAGE_DIR_NAME);
        this.cloudRepository = new ResearchCloudHistoryRepository(appContext);
    }

    /** Saves result/rejected/unavailable attempts with a small crop thumbnail. Must run off UI thread. */
    public synchronized void saveAttempt(@NonNull Bitmap crop, @NonNull ScanResearchState state)
            throws IOException, JSONException {
        ScanResearchState.Status status = state.getStatus();
        if (status != ScanResearchState.Status.RESULT
                && status != ScanResearchState.Status.REJECTED
                && status != ScanResearchState.Status.UNAVAILABLE) {
            return;
        }
        // Retry prior entries from this same authenticated UID before appending a new scan.
        retryPendingUploadsForCurrentUser();
        if (!imageDirectory.exists() && !imageDirectory.mkdirs()) {
            throw new IOException("Could not create local research history image directory.");
        }
        String id = UUID.randomUUID().toString();
        File image = new File(imageDirectory, "research_" + id + ".jpg");
        try (FileOutputStream output = new FileOutputStream(image)) {
            if (!crop.compress(Bitmap.CompressFormat.JPEG, 86, output)) {
                throw new IOException("Could not encode research scan thumbnail.");
            }
            output.flush();
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            image.delete();
            if (t instanceof IOException) throw (IOException) t;
            throw new IOException("Could not save research scan thumbnail.", t);
        }

        String signedInUid = cloudRepository.getSignedInUid();
        final String ownerUid = signedInUid == null ? ResearchCloudHistoryRepository.LOCAL_GUEST_OWNER : signedInUid;
        JSONArray existing = readArray();
        JSONArray updated = new JSONArray();
        JSONObject entry = new JSONObject();
        entry.put("id", id);
        entry.put("ownerUid", ownerUid);
        entry.put("cloudSynced", false);
        entry.put("cropKey", state.getCropKey());
        entry.put("status", status.name());
        entry.put("classId", state.getClassId() == null ? JSONObject.NULL : state.getClassId());
        entry.put("modelScore", state.getModelScore());
        entry.put("inferenceTimeMs", state.getInferenceTimeMs());
        long timestampMs = System.currentTimeMillis();
        entry.put("timestampMs", timestampMs);
        entry.put("message", state.getMessage() == null ? "" : state.getMessage());
        entry.put("imagePath", image.getAbsolutePath());
        updated.put(entry);
        int kept = 1;
        for (int i = 0; i < existing.length() && kept < MAX_ENTRIES; i++) {
            JSONObject old = existing.optJSONObject(i);
            if (old == null || id.equals(old.optString("id"))) continue;
            updated.put(old);
            kept++;
        }
        try {
            writeArrayAtomically(updated);
            pruneImages(updated);
        } catch (Throwable t) {
            //noinspection ResultOfMethodCallIgnored
            image.delete();
            if (t instanceof IOException) throw (IOException) t;
            if (t instanceof JSONException) throw (JSONException) t;
            throw new IOException("Could not update research scan history.", t);
        }
        Entry saved = new Entry(id, state.getCropKey(), status.name(),
                state.getClassId(), state.getModelScore(), state.getInferenceTimeMs(),
                timestampMs, state.getMessage() == null ? "" : state.getMessage(),
                image.getAbsolutePath(), ownerUid, false, false);
        cloudRepository.uploadAttempt(ownerUid, saved, new ResearchCloudHistoryRepository.UploadCallback() {
            @Override public void onSuccess() {
                FILE_IO.execute(() -> markCloudSynced(id, ownerUid));
            }
            @Override public void onFailure(@NonNull String message) {
                Log.w(TAG, "Scan is saved locally but not confirmed in Firestore: " + message);
            }
        });
    }

    @NonNull
    public synchronized List<Entry> loadRecent() {
        JSONArray array = readArray();
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id", "");
            String crop = obj.optString("cropKey", "unknown");
            String status = obj.optString("status", ScanResearchState.Status.ERROR.name());
            String classId = obj.isNull("classId") ? null : obj.optString("classId", "");
            String message = obj.optString("message", "");
            String path = obj.optString("imagePath", "");
            String ownerUid = obj.optString("ownerUid", ResearchCloudHistoryRepository.LOCAL_GUEST_OWNER);
            float score = (float) obj.optDouble("modelScore", 0d);
            long time = obj.optLong("inferenceTimeMs", 0L);
            long timestamp = obj.optLong("timestampMs", 0L);
            boolean cloudSynced = obj.optBoolean("cloudSynced", false);
            entries.add(new Entry(id, crop, status, classId, score, time, timestamp,
                    message, path, ownerUid, false, cloudSynced));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Retries pending uploads only for the currently authenticated owner's entries.
     * Legacy entries without an owner UID are intentionally not migrated implicitly.
     */
    public void retryPendingUploadsForCurrentUser() {
        String uid = cloudRepository.getSignedInUid();
        if (uid == null) {
            Log.i(TAG, "Cloud sync skipped: sign in with email/Google; Guest mode is local-only.");
            return;
        }
        for (Entry entry : loadRecentForOwner(uid)) {
            if (entry.cloudSynced) continue;
            cloudRepository.uploadAttempt(uid, entry, new ResearchCloudHistoryRepository.UploadCallback() {
                @Override public void onSuccess() {
                    FILE_IO.execute(() -> markCloudSynced(entry.id, uid));
                }
                @Override public void onFailure(@NonNull String message) {
                    Log.w(TAG, "Pending research scan " + entry.id + " is still local: " + message);
                }
            });
        }
    }

    private synchronized void markCloudSynced(@NonNull String id, @NonNull String ownerUid) {
        JSONArray array = readArray();
        boolean changed = false;
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null || !id.equals(obj.optString("id"))) continue;
            String recordedOwner = obj.optString("ownerUid", ResearchCloudHistoryRepository.LOCAL_GUEST_OWNER);
            if (!ownerUid.equals(recordedOwner)) return;
            try {
                obj.put("cloudSynced", true);
                changed = true;
            } catch (JSONException e) {
                Log.w(TAG, "Could not mark local research scan as cloud-synced.", e);
            }
            break;
        }
        if (changed) {
            try {
                writeArrayAtomically(array);
            } catch (IOException e) {
                Log.w(TAG, "Cloud write succeeded but local sync marker could not be saved.", e);
            }
        }
    }

    public interface HistoryLoadCallback {
        void onLoaded(@NonNull List<Entry> entries, boolean cloudLoaded, @Nullable String message);
    }

    /**
     * Loads only this account's local entries and its own Firestore collection. Legacy entries
     * without an owner are not exposed to signed-in accounts, avoiding cross-account leakage.
     * Call from the History screen; callback is delivered on the main thread.
     */
    public void loadForCurrentUserAsync(@NonNull HistoryLoadCallback callback) {
        String signedInUid = cloudRepository.getSignedInUid();
        String localOwner = signedInUid == null
                ? ResearchCloudHistoryRepository.LOCAL_GUEST_OWNER : signedInUid;
        List<Entry> local = loadRecentForOwner(localOwner);
        if (signedInUid == null) {
            callback.onLoaded(local, false,
                    "Guest mode: history is stored locally on this device. Sign in to sync history across devices.");
            return;
        }
        cloudRepository.fetchCurrentUserHistory(new ResearchCloudHistoryRepository.CloudHistoryCallback() {
            @Override public void onSuccess(@NonNull List<Entry> cloudEntries) {
                callback.onLoaded(merge(local, cloudEntries), true, null);
            }
            @Override public void onFailure(@NonNull String message) {
                callback.onLoaded(local, false, message);
            }
        });
    }

    @NonNull
    private synchronized List<Entry> loadRecentForOwner(@NonNull String ownerUid) {
        List<Entry> filtered = new ArrayList<>();
        for (Entry entry : loadRecent()) {
            if (ownerUid.equals(entry.ownerUid)) filtered.add(entry);
        }
        return Collections.unmodifiableList(filtered);
    }

    @NonNull
    private static List<Entry> merge(@NonNull List<Entry> local, @NonNull List<Entry> remote) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry e : remote) byId.put(e.id, e);
        for (Entry e : local) {
            Entry cloud = byId.get(e.id);
            if (cloud == null) {
                byId.put(e.id, e);
            } else if (e.imagePath != null && !e.imagePath.isEmpty() && new File(e.imagePath).exists()) {
                byId.put(e.id, new Entry(cloud.id, cloud.cropKey, cloud.status, cloud.classId,
                        cloud.modelScore, cloud.inferenceTimeMs, cloud.timestampMs, cloud.message,
                        e.imagePath, cloud.ownerUid, true, true));
            }
        }
        List<Entry> merged = new ArrayList<>(byId.values());
        merged.sort((a, b) -> Long.compare(b.timestampMs, a.timestampMs));
        if (merged.size() > MAX_ENTRIES) merged = new ArrayList<>(merged.subList(0, MAX_ENTRIES));
        return Collections.unmodifiableList(merged);
    }

    public synchronized void clear() {
        if (imageDirectory.exists()) {
            File[] files = imageDirectory.listFiles();
            if (files != null) {
                for (File file : files) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            imageDirectory.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        indexFile.delete();
        File temp = new File(indexFile.getParentFile(), INDEX_NAME + ".tmp");
        //noinspection ResultOfMethodCallIgnored
        temp.delete();
    }

    private JSONArray readArray() {
        if (!indexFile.exists() || indexFile.length() == 0) return new JSONArray();
        try (FileInputStream input = new FileInputStream(indexFile);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return new JSONArray(new String(output.toByteArray(), StandardCharsets.UTF_8));
        } catch (Throwable ignored) {
            return new JSONArray();
        }
    }

    private void writeArrayAtomically(@NonNull JSONArray array) throws IOException {
        File parent = indexFile.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs())) {
            throw new IOException("Could not create application files directory.");
        }
        File temp = new File(parent, INDEX_NAME + ".tmp");
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (indexFile.exists() && !indexFile.delete()) {
            // Continue to rename; Android filesystems usually permit replacement after delete.
        }
        if (!temp.renameTo(indexFile)) {
            try (FileInputStream input = new FileInputStream(temp);
                 FileOutputStream output = new FileOutputStream(indexFile)) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.flush();
            }
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }
    }

    private void pruneImages(@NonNull JSONArray retained) {
        if (!imageDirectory.exists()) return;
        java.util.HashSet<String> paths = new java.util.HashSet<>();
        for (int i = 0; i < retained.length(); i++) {
            JSONObject obj = retained.optJSONObject(i);
            if (obj != null) paths.add(obj.optString("imagePath", ""));
        }
        File[] files = imageDirectory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (!paths.contains(file.getAbsolutePath())) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    public static final class Entry {
        public final String id;
        public final String cropKey;
        public final String status;
        @Nullable public final String classId;
        public final float modelScore;
        public final long inferenceTimeMs;
        public final long timestampMs;
        @NonNull public final String message;
        @NonNull public final String imagePath;
        @NonNull public final String ownerUid;
        public final boolean cloudBacked;
        public final boolean cloudSynced;

        Entry(@NonNull String id, @NonNull String cropKey, @NonNull String status,
              @Nullable String classId, float modelScore, long inferenceTimeMs, long timestampMs,
              @NonNull String message, @NonNull String imagePath,
              @NonNull String ownerUid, boolean cloudBacked, boolean cloudSynced) {
            this.id = id; this.cropKey = cropKey; this.status = status; this.classId = classId;
            this.modelScore = modelScore; this.inferenceTimeMs = inferenceTimeMs;
            this.timestampMs = timestampMs; this.message = message; this.imagePath = imagePath;
            this.ownerUid = ownerUid; this.cloudBacked = cloudBacked; this.cloudSynced = cloudSynced;
        }
    }
}
