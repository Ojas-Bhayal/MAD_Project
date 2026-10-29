package com.cropdoc.app.util;

import androidx.annotation.NonNull;

/**
 * Formats internal snake_case crop and disease identifiers (e.g., "rice_bacterial_leaf_blight")
 * into clean, farmer-friendly display titles ("Rice — Bacterial Leaf Blight") using a single-pass
 * character loop (#14).
 */
public final class DiseaseLabelFormatter {

    private DiseaseLabelFormatter() {
        // Utility class
    }

    @NonNull
    public static String format(@NonNull String diseaseId) {
        String trimmed = diseaseId.trim();
        if (trimmed.isEmpty()) {
            return "Unknown Crop Leaf";
        }
        int underscore = trimmed.indexOf('_');
        if (underscore > 0 && underscore < trimmed.length() - 1) {
            String crop = trimmed.substring(0, underscore);
            return formatDiseaseTitle(crop, trimmed);
        }
        return capitalizeWords(trimmed);
    }

    @NonNull
    public static String formatDiseaseTitle(
            @NonNull String cropId,
            @NonNull String diseaseId) {
        if ("background_not_a_leaf".equals(diseaseId)) {
            return "Not a Supported Crop Leaf";
        }
        String rawDisease = diseaseId;
        String prefix = cropId + "_";
        if (diseaseId.startsWith(prefix) && diseaseId.length() > prefix.length()) {
            rawDisease = diseaseId.substring(prefix.length());
        }
        return capitalizeWords(cropId) + " — " + capitalizeWords(rawDisease);
    }

    @NonNull
    public static String capitalizeWords(@NonNull String snakeCase) {
        String trimmed = snakeCase.trim();
        int len = trimmed.length();
        if (len == 0) {
            return snakeCase;
        }
        StringBuilder sb = new StringBuilder(len);
        boolean capitalizeNext = true;
        for (int i = 0; i < len; i++) {
            char ch = trimmed.charAt(i);
            if (ch == '_') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
                    sb.append(' ');
                }
                capitalizeNext = true;
            } else if (capitalizeNext) {
                sb.append(Character.toUpperCase(ch));
                capitalizeNext = false;
            } else {
                sb.append(Character.toLowerCase(ch));
            }
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() > 0 ? sb.toString() : snakeCase;
    }
}
