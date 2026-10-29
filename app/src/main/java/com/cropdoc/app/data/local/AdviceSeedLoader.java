package com.cropdoc.app.data.local;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import com.cropdoc.app.data.local.dao.AdviceDao;
import com.cropdoc.app.data.local.entity.AdviceEntity;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Seeds the Room advice_catalog table from assets/advice/advice_catalog.json once per process
 * (#8), ensuring all 63 localized entries (15 v1 + 6 v2 classes x 3 languages: en, hi, te) exist.
 */
public final class AdviceSeedLoader {

    public static final int EXPECTED_CATALOG_SIZE = 63;
    private static final String ASSET_PATH = "advice/advice_catalog.json";
    private static volatile boolean seeded = false;

    private AdviceSeedLoader() {
        // Utility class
    }

    public static void ensureSeeded(
            @NonNull Context context,
            @NonNull AdviceDao adviceDao) throws IOException, JSONException {
        if (seeded) {
            return;
        }
        synchronized (AdviceSeedLoader.class) {
            if (seeded) {
                return;
            }
            if (adviceDao.countEntries() >= EXPECTED_CATALOG_SIZE) {
                seeded = true;
                return;
            }
            String jsonText = readAssetText(context, ASSET_PATH);
            List<AdviceEntity> entities = parseAdviceJson(jsonText);
            adviceDao.insertAll(entities);
            seeded = entities.size() >= EXPECTED_CATALOG_SIZE;
        }
    }

    @VisibleForTesting
    public static void resetForTesting() {
        seeded = false;
    }

    @NonNull
    public static List<AdviceEntity> parseAdviceJson(
            @NonNull String jsonText) throws JSONException {
        JSONArray array = new JSONArray(jsonText);
        List<AdviceEntity> result = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            result.add(new AdviceEntity(
                    obj.getString("diseaseId"),
                    obj.getString("languageCode"),
                    obj.getString("cropName"),
                    obj.getString("diseaseName"),
                    obj.getString("causes"),
                    obj.getString("culturalBioControl"),
                    obj.getString("chemicalControlCibrc"),
                    obj.getString("preventionSteps"),
                    obj.getString("authoritativeSource")));
        }
        return result;
    }

    @NonNull
    private static String readAssetText(
            @NonNull Context context,
            @NonNull String assetPath) throws IOException {
        StringBuilder builder = new StringBuilder();
        try (InputStream is = context.getAssets().open(assetPath);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
        }
        return builder.toString();
    }
}
