package com.cropdoc.app.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.exifinterface.media.ExifInterface;
import com.cropdoc.app.domain.model.DiagnosisOutcome;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.util.DiseaseLabelFormatter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Unit tests for ConfidenceCalibrator, LeafSpectralAnalyzer, EXIF rotation, square reticle crop
 * math (#5, #7), OTA label consistency (#1, #2), and single-pass DiseaseLabelFormatter (#14).
 */
public final class LiteRtLeafClassifierTest {

    private static final List<String> TEST_LABELS = Arrays.asList(
            "rice_bacterial_leaf_blight",
            "rice_healthy",
            "tomato_early_blight",
            "background_not_a_leaf");

    @Test
    public void computeCalibratedProbabilities_sumsToOneAndPreservesOrder() {
        float[] logits = new float[] {5.2f, 1.0f, -0.5f, 0.2f};
        float[] probs = ConfidenceCalibrator.computeCalibratedProbabilities(
                logits, ModelConfig.TEMPERATURE_SCALE);
        float sum = 0f;
        for (float p : probs) {
            sum += p;
        }
        assertEquals(1.0f, sum, 1e-5f);
        assertTrue(probs[0] > probs[1]);
        assertTrue(probs[1] > probs[3]);
    }

    @Test
    public void evaluateLogits_confidentDisease_returnsConfidentOutcome() {
        float[] logits = new float[] {8.5f, 0.1f, 0.2f, -1.0f};
        DiagnosisResult res = ConfidenceCalibrator.evaluateLogits(
                logits, 0.72f, 24L, TEST_LABELS, "1.0.0-int8");
        assertEquals("rice_bacterial_leaf_blight", res.getLabelId());
        assertEquals("rice", res.getCropId());
        assertEquals(DiagnosisOutcome.CONFIDENT_DISEASE, res.getOutcome());
        assertTrue(res.getConfidence() >= ModelConfig.CONFIDENCE_THRESHOLD);
    }

    @Test
    public void evaluateLogits_flatLogits_triggersLowConfidenceRetake() {
        float[] flatLogits = new float[] {1.1f, 1.0f, 0.95f, 0.9f};
        DiagnosisResult res = ConfidenceCalibrator.evaluateLogits(
                flatLogits, 0.65f, 22L, TEST_LABELS, "1.0.0-int8");
        assertEquals(DiagnosisOutcome.LOW_CONFIDENCE_RETAKE, res.getOutcome());
        assertTrue(res.getNormalizedEntropy() > ModelConfig.MAX_NORMALIZED_ENTROPY);
    }

    @Test
    public void evaluateLogits_lowLeafPixelRatio_triggersNotASupportedLeaf() {
        float[] strongLogits = new float[] {9.0f, 0.0f, 0.0f, 0.0f};
        DiagnosisResult res = ConfidenceCalibrator.evaluateLogits(
                strongLogits, 0.05f, 19L, TEST_LABELS, "1.0.0-int8");
        assertEquals(DiagnosisOutcome.NOT_A_SUPPORTED_LEAF, res.getOutcome());
    }

    @Test
    public void leafPreprocessor_exifSubsamplingAndSquareCropBounds_workCorrectly() {
        int sampleSize = LeafPreprocessor.calculateInSampleSize(8000, 6000, 448, 448);
        assertEquals(8, sampleSize);
        assertEquals(90, LeafPreprocessor.exifOrientationToDegrees(
                ExifInterface.ORIENTATION_ROTATE_90));
        assertEquals(180, LeafPreprocessor.exifOrientationToDegrees(
                ExifInterface.ORIENTATION_ROTATE_180));
        assertEquals(270, LeafPreprocessor.exifOrientationToDegrees(
                ExifInterface.ORIENTATION_ROTATE_270));
        assertTrue(LeafPreprocessor.isLeafOrLesionPixel(60, 155, 50));
        assertFalse(LeafPreprocessor.isLeafOrLesionPixel(200, 200, 200));

        int[] squareBounds = LeafPreprocessor.computeReticleCropBounds(
                1000, 1000, 1000, 1000, 500, 500);
        assertEquals(250, squareBounds[0]);
        assertEquals(250, squareBounds[1]);
        assertEquals(500, squareBounds[2]);

        int[] rectBounds = LeafPreprocessor.computeReticleCropBounds(
                1920, 1080, 1920, 1080, 800, 500);
        assertEquals(500, rectBounds[2]);
        assertTrue(rectBounds[0] >= 0 && rectBounds[0] + rectBounds[2] <= 1920);
        assertTrue(rectBounds[1] >= 0 && rectBounds[1] + rectBounds[2] <= 1080);
    }

    @Test
    public void leafSpectralAnalyzer_andDiseaseFormatter_produceValidOutput() {
        float[] zeroLogits = new float[ModelConfig.NUM_CLASSES];
        assertTrue(LeafSpectralAnalyzer.isAllZeroOrUniform(zeroLogits));

        float[] uniformLogits = new float[ModelConfig.NUM_CLASSES];
        Arrays.fill(uniformLogits, 2.5f);
        assertTrue(LeafSpectralAnalyzer.isAllZeroOrUniform(uniformLogits));

        float[] nonUniformLogits = new float[ModelConfig.NUM_CLASSES];
        nonUniformLogits[0] = 5.0f;
        assertFalse(LeafSpectralAnalyzer.isAllZeroOrUniform(nonUniformLogits));

        assertEquals(
                "Rice — Bacterial Leaf Blight",
                DiseaseLabelFormatter.formatDiseaseTitle("rice", "rice_bacterial_leaf_blight"));
        assertEquals("Early Blight", DiseaseLabelFormatter.capitalizeWords("early__blight_"));
    }

    @Test
    public void dynamicClassExpansion_supportsAppendedV2ClassesAndRejectsMismatch()
            throws IOException {
        List<String> v2Labels = new ArrayList<>();
        for (int i = 0; i < 15; i++) {
            v2Labels.add("crop_v1_class_" + i);
        }
        v2Labels.add(ModelConfig.OOD_CLASS_LABEL);
        v2Labels.add("groundnut_early_leaf_spot");
        v2Labels.add("groundnut_healthy");
        v2Labels.add("chilli_leaf_curl_virus");
        v2Labels.add("chilli_healthy");
        v2Labels.add("soybean_rust");
        v2Labels.add("soybean_healthy");
        assertEquals(22, LiteRtLeafClassifier.resolveDynamicClassCount(v2Labels, 22));
        assertTrue(LiteRtLeafClassifier.isLabelTensorCountConsistent(v2Labels, 22));
        assertFalse(LiteRtLeafClassifier.isLabelTensorCountConsistent(v2Labels, 16));

        String rawStream = String.join("\n", v2Labels);
        List<String> parsed = LiteRtLeafClassifier.readLabelsStream(
                new ByteArrayInputStream(rawStream.getBytes(StandardCharsets.UTF_8)));
        assertEquals(22, parsed.size());

        float[] v2Logits = new float[22];
        Arrays.fill(v2Logits, -1.2f);
        v2Logits[16] = 8.4f;
        DiagnosisResult res = ConfidenceCalibrator.evaluateLogits(
                v2Logits, 0.78f, 21L, parsed, "2.0.0-int8");
        assertEquals("groundnut_early_leaf_spot", res.getLabelId());
        assertEquals("groundnut", res.getCropId());
        assertEquals(DiagnosisOutcome.CONFIDENT_DISEASE, res.getOutcome());
    }
}
