package com.cropdoc.app.ml.policy;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Isolated class mapping and taxonomy metadata for the 6-class research prototype.
 * Kept completely decoupled from the production 16/22-class taxonomy.
 */
public final class SixClassResearchMapping {

    public static final int NUM_RESEARCH_CLASSES = 6;

    public static final class ClassEntry {
        public final int localIndex;
        public final int globalIndex;
        @NonNull public final String classId;
        @NonNull public final String crop;
        public final boolean isHealthy;

        public ClassEntry(
                int localIndex,
                int globalIndex,
                @NonNull String classId,
                @NonNull String crop,
                boolean isHealthy) {
            this.localIndex = localIndex;
            this.globalIndex = globalIndex;
            this.classId = classId;
            this.crop = crop;
            this.isHealthy = isHealthy;
        }
    }

    private static final List<ClassEntry> ENTRIES = Collections.unmodifiableList(Arrays.asList(
            new ClassEntry(0, 0, "rice_bacterial_leaf_blight", "rice", false),
            new ClassEntry(1, 1, "rice_brown_spot", "rice", false),
            new ClassEntry(2, 2, "rice_leaf_blast", "rice", false),
            new ClassEntry(3, 3, "rice_healthy", "rice", true),
            new ClassEntry(4, 16, "groundnut_early_leaf_spot", "groundnut", false),
            new ClassEntry(5, 17, "groundnut_healthy", "groundnut", true)
    ));

    public static final Set<String> SUPPORTED_CROPS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("rice", "groundnut"))
    );

    private SixClassResearchMapping() {
        // Static definition only
    }

    @NonNull
    public static List<ClassEntry> getEntries() {
        return ENTRIES;
    }

    @Nullable
    public static ClassEntry getByLocalIndex(int localIndex) {
        if (localIndex >= 0 && localIndex < ENTRIES.size()) {
            return ENTRIES.get(localIndex);
        }
        return null;
    }

    public static boolean isSupportedCrop(@Nullable String crop) {
        if (crop == null) {
            return false;
        }
        return SUPPORTED_CROPS.contains(crop.trim().toLowerCase(Locale.ROOT));
    }
}
