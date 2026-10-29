package com.cropdoc.app.data.repository;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import com.cropdoc.app.data.local.AdviceSeedLoader;
import com.cropdoc.app.data.local.dao.AdviceDao;
import com.cropdoc.app.data.local.entity.AdviceEntity;
import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import org.junit.Before;
import org.junit.Test;

/**
 * Unit tests for AdviceRepository verifying localized advice lookup, English fallback,
 * and once-per-process seeding check (#8).
 */
public final class AdviceRepositoryTest {

    private Context mockContext;
    private AdviceDao mockDao;
    private AdviceRepository repository;

    @Before
    public void setUp() {
        AdviceSeedLoader.resetForTesting();
        mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockContext);
        mockDao = mock(AdviceDao.class);
        when(mockDao.countEntries()).thenReturn(AdviceSeedLoader.EXPECTED_CATALOG_SIZE);
        repository = new AdviceRepository(mockContext, mockDao);
    }

    @Test
    public void getAdviceSync_returnsTeluguAndCachesSeedCheckAcrossCalls() {
        AdviceEntity teEntity = new AdviceEntity(
                "rice_bacterial_leaf_blight",
                "te",
                "వరి (Paddy)",
                "బాక్టీరియా ఆకు ఎండు తెగులు",
                "జాంతోమోనాస్ ఒరైజే",
                "సూడోమోనాస్ ఫ్లోరెసెన్స్ 10 గ్రా/లీ",
                "కాపర్ ఆక్సీక్లోరైడ్ 50% WP",
                "తెగులు తట్టుకునే రకాలు",
                "ICAR-IIRR & PJTSAU");
        when(mockDao.findAdvice("rice_bacterial_leaf_blight", "te")).thenReturn(teEntity);
        CropDiseaseAdvice first = repository.getAdviceSync("rice_bacterial_leaf_blight", "te");
        CropDiseaseAdvice second = repository.getAdviceSync("rice_bacterial_leaf_blight", "te");
        assertNotNull(first);
        assertNotNull(second);
        assertEquals("వరి (Paddy)", first.getCropName());
        verify(mockDao, times(1)).countEntries();
    }

    @Test
    public void getAdviceSync_fallsBackToEnglishWhenRegionalEntryMissing() {
        AdviceEntity enEntity = new AdviceEntity(
                "maize_common_rust",
                "en",
                "Maize (Corn)",
                "Common Rust",
                "Puccinia sorghi",
                "Remove Oxalis weeds",
                "Mancozeb 75% WP",
                "Rust-tolerant hybrids",
                "ICAR-IIMR");
        when(mockDao.findAdvice("maize_common_rust", "te")).thenReturn(null);
        when(mockDao.findAdvice("maize_common_rust", "en")).thenReturn(enEntity);
        CropDiseaseAdvice advice = repository.getAdviceSync("maize_common_rust", "te");
        assertNotNull(advice);
        assertEquals("Maize (Corn)", advice.getCropName());
    }
}
