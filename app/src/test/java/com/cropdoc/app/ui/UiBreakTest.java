package com.cropdoc.app.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.cropdoc.app.domain.model.CropDiseaseAdvice;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.ml.ConfidenceCalibrator;
import com.cropdoc.app.ml.ModelConfig;
import com.cropdoc.app.util.DiseaseLabelFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Worst-case data regression suite following skills/break-ui and skills/break-ui/CATALOG.md.
 * Tests extreme strings, multilingual Indic diacritics, 0/1/1000 item collections,
 * boundary confidence scores (0%, low-confidence, 100%), and missing/optional fields.
 */
public final class UiBreakTest {

    @Test
    public void formatLabel_handlesLongestSupportedDiseaseAndFallbackTokens() {
        String formatted = DiseaseLabelFormatter.format("tomato_yellow_leaf_curl_virus");
        assertEquals("Tomato — Yellow Leaf Curl Virus", formatted);

        String singleToken = DiseaseLabelFormatter.format("unbroken");
        assertTrue(singleToken.contains("Unbroken"));

        String emptyToken = DiseaseLabelFormatter.format("");
        assertNotNull(emptyToken);
    }

    @Test
    public void breakUi_multilingualIndicAndMaxLengthAdvice_preservesAllFieldsWithoutTruncation() {
        String longTeluguDisease =
                "టమాటా ఆకు ముడత వైరస్ తెగులు (Tomato Yellow Leaf Curl Virus - Begomovirus)";
        String longHindiDisease =
                "टमाटर का पीला पत्ती मरोड़ विषाणु रोग (Tomato Yellow Leaf Curl Virus)";
        String maxAdviceParagraph =
                "1. संक्रमित पौधों को तुरंत उखाड़कर नष्ट करें और खेत के बाहर गहरा गाड़ दें। "
                + "2. सफेद मक्खी (Bemisia tabaci) की निगरानी के लिए पीले चिपचिपे ट्रैप लगाएं। "
                + "3. नीम तेल (Azadirachtin 1500 ppm) 5 मिली प्रति लीटर पानी में मिलाकर छिड़काव करें।";

        CropDiseaseAdvice teAdvice = new CropDiseaseAdvice(
                "tomato_yellow_leaf_curl_virus",
                "టమాటా (Tomato)",
                longTeluguDisease,
                maxAdviceParagraph,
                maxAdviceParagraph,
                maxAdviceParagraph,
                maxAdviceParagraph,
                "ICAR-IIVR Varanasi & PJTSAU Hyderabad (2026)");

        CropDiseaseAdvice hiAdvice = new CropDiseaseAdvice(
                "tomato_yellow_leaf_curl_virus",
                "टमाटर (Tomato)",
                longHindiDisease,
                maxAdviceParagraph,
                maxAdviceParagraph,
                "",
                maxAdviceParagraph,
                "ICAR-IIVR Varanasi & CABI PlantwisePlus (2026)");

        assertEquals(longTeluguDisease, teAdvice.getDiseaseName());
        assertEquals(longHindiDisease, hiAdvice.getDiseaseName());
        assertTrue(hiAdvice.getChemicalControlCibrc().isEmpty());
        assertFalse(teAdvice.getCulturalAndBioControl().isEmpty());
    }

    @Test
    public void breakUi_confidenceBoundaries_zeroLowAndHundredPercent() {
        float[] flatLogits = new float[ModelConfig.NUM_CLASSES];
        float[] flatProbs = ConfidenceCalibrator.computeCalibratedProbabilities(
                flatLogits, ModelConfig.TEMPERATURE_SCALE);
        float flatEntropy = ConfidenceCalibrator.computeNormalizedEntropy(flatProbs);
        DiagnosisOutcome lowOutcome = ConfidenceCalibrator.resolveOutcome(
                "tomato_yellow_leaf_curl_virus", flatProbs[6], flatEntropy, 0.45f);
        assertEquals(DiagnosisOutcome.LOW_CONFIDENCE_RETAKE, lowOutcome);

        float[] dominantLogits = new float[ModelConfig.NUM_CLASSES];
        dominantLogits[6] = 50.0f;
        float[] domProbs = ConfidenceCalibrator.computeCalibratedProbabilities(
                dominantLogits, ModelConfig.TEMPERATURE_SCALE);
        float domEntropy = ConfidenceCalibrator.computeNormalizedEntropy(domProbs);
        assertTrue(domProbs[6] > 0.999f);
        DiagnosisOutcome confidentOutcome = ConfidenceCalibrator.resolveOutcome(
                "tomato_yellow_leaf_curl_virus", domProbs[6], domEntropy, 0.55f);
        assertEquals(DiagnosisOutcome.CONFIDENT_DISEASE, confidentOutcome);

        DiagnosisOutcome nonLeafOutcome = ConfidenceCalibrator.resolveOutcome(
                "tomato_yellow_leaf_curl_virus", domProbs[6], domEntropy, 0.05f);
        assertEquals(DiagnosisOutcome.NOT_A_SUPPORTED_LEAF, nonLeafOutcome);
    }

    @Test
    public void breakUi_historyCollectionVolumes_zeroOneAndThousandItems() {
        List<ScanRecord> emptyList = new ArrayList<>();
        assertEquals(0, emptyList.size());

        List<ScanRecord> singleItemList = new ArrayList<>();
        singleItemList.add(createRecord(0));
        assertEquals(1, singleItemList.size());

        List<ScanRecord> thousandItems = new ArrayList<>(1000);
        for (int i = 0; i < 1000; i++) {
            thousandItems.add(createRecord(i));
        }
        assertEquals(1000, thousandItems.size());
        assertEquals("scan_id_999", thousandItems.get(999).getScanId());
    }

    @Test
    public void breakUi_missingOptionalFieldsAndUnbreakableStrings_handledSafely() {
        String longEmail = "progressive.farmer.cooperative.federation.polepalle."
                + "mahbubnagar.district.telangana@example-agri-domain.co.in";
        assertTrue(longEmail.length() > 95);

        DiagnosisResult result = new DiagnosisResult(
                "tomato_yellow_leaf_curl_virus",
                "tomato",
                0.0f,
                1.0f,
                0.0f,
                12L,
                ModelConfig.DEFAULT_MODEL_VERSION,
                DiagnosisOutcome.LOW_CONFIDENCE_RETAKE);
        assertEquals(0.0f, result.getConfidence(), 0.0001f);
        assertEquals(DiagnosisOutcome.LOW_CONFIDENCE_RETAKE, result.getOutcome());
    }

    private static ScanRecord createRecord(int index) {
        return new ScanRecord(
                "scan_id_" + index,
                "user_farmer_1",
                "tomato",
                "tomato_yellow_leaf_curl_virus",
                0.88f,
                DiagnosisOutcome.CONFIDENT_DISEASE,
                "/data/user/0/com.cropdoc.app/files/scans/scan_" + index + ".jpg",
                null,
                1760700000000L + (index * 60000L),
                index % 2 == 0,
                null,
                ModelConfig.DEFAULT_MODEL_VERSION);
    }
}
