package com.cropdoc.app.data.repository;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.cropdoc.app.data.local.AdviceSeedLoader;
import com.cropdoc.app.data.local.dao.AdviceDao;
import com.cropdoc.app.data.local.entity.AdviceEntity;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import java.util.Objects;

/**
 * Provides localized ICAR/CABI/CIB&RC disease advice from the offline Room cache, falling back
 * to English if a specific regional translation entry is not present.
 */
public class AdviceRepository {

    private static final String DEFAULT_LANG = "en";

    @NonNull
    private final Context appContext;

    @NonNull
    private final AdviceDao adviceDao;

    public AdviceRepository(@NonNull Context context, @NonNull AdviceDao adviceDao) {
        this.appContext = context.getApplicationContext();
        this.adviceDao = Objects.requireNonNull(adviceDao, "adviceDao");
    }

    @Nullable
    public CropDiseaseAdvice getAdviceSync(
            @NonNull String diseaseId,
            @NonNull String languageCode) {
        try {
            AdviceSeedLoader.ensureSeeded(appContext, adviceDao);
        } catch (Throwable ignored) {
            // Proceed with existing cached records
        }
        try {
            AdviceEntity entity = adviceDao.findAdvice(diseaseId, languageCode);
            if (entity == null && !DEFAULT_LANG.equals(languageCode)) {
                entity = adviceDao.findAdvice(diseaseId, DEFAULT_LANG);
            }
            if (entity == null) {
                return null;
            }
            return new CropDiseaseAdvice(
                    entity.getDiseaseId(),
                    entity.getCropName(),
                    entity.getDiseaseName(),
                    entity.getCauses(),
                    entity.getCulturalBioControl(),
                    entity.getChemicalControlCibrc(),
                    entity.getPreventionSteps(),
                    entity.getAuthoritativeSource());
        } catch (Throwable e) {
            return null;
        }
    }
}
