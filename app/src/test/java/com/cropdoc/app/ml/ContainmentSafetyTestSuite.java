package com.cropdoc.app.ml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Bitmap;
import androidx.exifinterface.media.ExifInterface;
import com.cropdoc.app.data.repository.AdviceRepository;
import com.cropdoc.app.data.repository.AuthRepository;
import com.cropdoc.app.data.repository.ScanRepository;
import com.cropdoc.app.domain.model.DiagnosisResult;
import com.cropdoc.app.domain.model.ScanRecord;
import com.cropdoc.app.domain.usecase.AnalyzeLeafUseCase;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.Test;
import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

/**
 * Comprehensive Phase D safety, containment, parity, and boundary unit test suite.
 */
public final class ContainmentSafetyTestSuite {

    private static final List<String> TEST_LABELS = Arrays.asList(
            "rice_bacterial_leaf_blight",
            "rice_brown_spot",
            "rice_healthy",
            "tomato_early_blight",
            "tomato_healthy",
            "potato_early_blight",
            "potato_healthy",
            "background_not_a_leaf");

    // ------------------------------------------------------------------------
    // 1. ClassificationOutcome Typed Result Branch Coverage
    // ------------------------------------------------------------------------

    @Test
    public void classificationOutcome_allBranchesInstantiateAndPreserveState() {
        // Diagnosis branch
        ClassificationOutcome.Diagnosis diag = new ClassificationOutcome.Diagnosis(
                "tomato_early_blight", 0.94f, "1.0.0-int8");
        assertEquals("tomato_early_blight", diag.getClassId());
        assertEquals(0.94f, diag.getConfidence(), 1e-4f);
        assertEquals("1.0.0-int8", diag.getModelVersion());
        assertTrue(diag.toString().contains("tomato_early_blight"));

        // LowConfidence branch
        List<ClassificationOutcome.Candidate> candidates = Arrays.asList(
                new ClassificationOutcome.Candidate("tomato_early_blight", 0.45f),
                new ClassificationOutcome.Candidate("potato_early_blight", 0.40f)
        );
        ClassificationOutcome.LowConfidence lowConf = new ClassificationOutcome.LowConfidence(
                candidates, ClassificationOutcome.LowConfidenceReason.CONFIDENCE_BELOW_THRESHOLD);
        assertEquals(ClassificationOutcome.LowConfidenceReason.CONFIDENCE_BELOW_THRESHOLD, lowConf.getReason());
        assertEquals(2, lowConf.getTopCandidates().size());
        assertEquals("tomato_early_blight", lowConf.getTopCandidates().get(0).getClassId());

        // Rejected branch
        ClassificationOutcome.Rejected rejected = new ClassificationOutcome.Rejected(
                ClassificationOutcome.RejectionReason.UNDER_EXPOSED);
        assertEquals(ClassificationOutcome.RejectionReason.UNDER_EXPOSED, rejected.getReason());
        assertTrue(rejected.toString().contains("UNDER_EXPOSED"));

        // ModelUnavailable branch
        ClassificationOutcome.ModelUnavailable unavail = new ClassificationOutcome.ModelUnavailable(
                ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED);
        assertEquals(ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED, unavail.getReason());
        assertTrue(unavail.toString().contains("MODEL_CORRUPT_OR_TRUNCATED"));
    }

    // ------------------------------------------------------------------------
    // 2. ModelValidator File Integrity, Header, Tensor Shapes & Labels Parity
    // ------------------------------------------------------------------------

    @Test
    public void modelValidator_rejectsTruncatedOrPlaceholderFiles() {
        ByteBuffer buf = ByteBuffer.allocate(16800);
        ModelValidator.ValidationResult result = ModelValidator.validateModel(
                16800L, buf, null, TEST_LABELS, "1.0.0-int8");

        assertFalse("Model below 512KB must be rejected", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED, result.getReason());
        assertTrue(result.getMessage().contains("below minimum threshold"));
    }

    @Test
    public void modelValidator_rejectsInvalidTfliteHeaderIdentifier() {
        ByteBuffer buf = ByteBuffer.allocate(600000);
        // Write bogus identifier instead of TFL3 at offset 4
        buf.position(4);
        buf.put("BOGU".getBytes(StandardCharsets.US_ASCII));
        buf.position(0);

        ModelValidator.ValidationResult result = ModelValidator.validateModel(
                600000L, buf, null, TEST_LABELS, "1.0.0-int8");

        assertFalse("Invalid header must be rejected", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED, result.getReason());
        assertTrue(result.getMessage().contains("Invalid TFLite header"));
    }

    @Test
    public void modelValidator_rejectsLabelCountMismatch() {
        ByteBuffer buf = ByteBuffer.allocate(600000);
        buf.position(4);
        buf.put("TFL3".getBytes(StandardCharsets.US_ASCII));
        buf.position(0);

        Interpreter mockInterpreter = mock(Interpreter.class);
        Tensor mockInTensor = mock(Tensor.class);
        Tensor mockOutTensor = mock(Tensor.class);

        when(mockInterpreter.getInputTensorCount()).thenReturn(1);
        when(mockInterpreter.getInputTensor(0)).thenReturn(mockInTensor);
        when(mockInTensor.shape()).thenReturn(new int[] {1, 224, 224, 3});

        when(mockInterpreter.getOutputTensorCount()).thenReturn(1);
        when(mockInterpreter.getOutputTensor(0)).thenReturn(mockOutTensor);
        // Model outputs 16 classes, but labels list has 8 classes
        when(mockOutTensor.shape()).thenReturn(new int[] {1, 16});

        ModelValidator.ValidationResult result = ModelValidator.validateModel(
                600000L, buf, mockInterpreter, TEST_LABELS, "1.0.0-int8");

        assertFalse("Mismatched class count must be rejected", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.LABELS_MISMATCH, result.getReason());
    }

    @Test
    public void modelValidator_computesDeterministicLabelOrderChecksum() {
        List<String> listA = Arrays.asList("rice_healthy", "tomato_healthy");
        List<String> listB = Arrays.asList("tomato_healthy", "rice_healthy");

        long checksumA = ModelValidator.computeLabelOrderChecksum(listA);
        long checksumA2 = ModelValidator.computeLabelOrderChecksum(listA);
        long checksumB = ModelValidator.computeLabelOrderChecksum(listB);

        assertEquals(checksumA, checksumA2);
        assertTrue("Reordering labels must change checksum", checksumA != checksumB);
    }

    // ------------------------------------------------------------------------
    // 3. LeafGate & ImageQualityGate Sanity Checks & Transparent Gating
    // ------------------------------------------------------------------------

    @Test
    public void imageQualityGate_rejectsExtremeUnderexposure() {
        ImageQualityGate gate = new ImageQualityGate();
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        // Fill pitch black tensor (all 0s)
        ByteBuffer tensorBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            tensorBuffer.put((byte) 0);
        }

        ImageQualityGate.QualityResult res = gate.evaluate(mockBitmap, tensorBuffer);
        assertFalse(res.isAccepted());
        assertEquals(ClassificationOutcome.RejectionReason.UNDER_EXPOSED, res.getRejectionReason());
    }

    @Test
    public void imageQualityGate_rejectsExtremeOverexposure() {
        ImageQualityGate gate = new ImageQualityGate();
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        // Fill blown out white tensor (all 255)
        ByteBuffer tensorBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.BYTE_BUFFER_SIZE; i++) {
            tensorBuffer.put((byte) 255);
        }

        ImageQualityGate.QualityResult res = gate.evaluate(mockBitmap, tensorBuffer);
        assertFalse(res.isAccepted());
        assertEquals(ClassificationOutcome.RejectionReason.OVER_EXPOSED, res.getRejectionReason());
    }

    @Test
    public void defaultLeafGate_isUnvalidatedAndReturnsUncertainForNormalQuality() {
        DefaultLeafGate gate = new DefaultLeafGate();
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        // Fill normal daylight green foliage pixels (R=70, G=140, B=60)
        ByteBuffer tensorBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.PIXEL_COUNT; i++) {
            tensorBuffer.put((byte) 70);
            tensorBuffer.put((byte) 140);
            tensorBuffer.put((byte) 60);
        }

        assertEquals(LeafGate.GateState.PRESENT_UNVALIDATED, gate.getState());
        LeafGate.GateResult res = gate.evaluate(mockBitmap, tensorBuffer);
        assertEquals(LeafGate.GateDecision.UNCERTAIN, res.getDecision());
        assertTrue(res.getExplanation().toLowerCase(java.util.Locale.US).contains("unvalidated"));
    }

    @Test
    public void validatedLeafGate_missingEvaluator_cannotDeclareValidatedOrAcceptImages() {
        // Phase F requirement: A gate without a genuine semantic leaf evaluator must NOT declare VALIDATED
        // and must NEVER return ACCEPT even on good-quality, well-lit foliage pixels.
        ValidatedLeafGate gate = ValidatedLeafGate.createWithoutEvaluator("leaf_gate_v1.0", new ImageQualityGate());
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        ByteBuffer tensorBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.PIXEL_COUNT; i++) {
            tensorBuffer.put((byte) 70);
            tensorBuffer.put((byte) 140);
            tensorBuffer.put((byte) 60);
        }

        // Must NOT be VALIDATED
        assertEquals(LeafGate.GateState.PRESENT_UNVALIDATED, gate.getState());
        assertEquals("leaf_gate_v1.0", gate.getVersion());

        // Must NEVER return ACCEPT simply because photographic exposure is acceptable
        LeafGate.GateResult res = gate.evaluate(mockBitmap, tensorBuffer);
        assertEquals(LeafGate.GateDecision.UNCERTAIN, res.getDecision());
        assertFalse("Unvalidated gate must never accept images", res.getDecision() == LeafGate.GateDecision.ACCEPT);
        assertTrue(res.getExplanation().contains("Semantic evaluator is missing"));
    }

    @Test
    public void validatedLeafGate_evaluatorThrowingException_failsClosedWithUncertain() {
        SemanticLeafEvaluator crashingEvaluator = mock(SemanticLeafEvaluator.class);
        when(crashingEvaluator.evaluateSemanticLeaf(any(Bitmap.class), any(ByteBuffer.class)))
                .thenThrow(new RuntimeException("LiteRT GPU delegate crash"));

        ModelManifest gateManifest = ModelManifest.parse("{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_gate_v1\",\n"
                + "  \"model_version\": \"1.0.0\",\n"
                + "  \"sha256\": \"11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 2,\n"
                + "  \"output_dtype\": \"float32\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \"12345678\",\n"
                + "  \"evidence_report_hash\": \"aaabbbccc00011122233344455566677889900aabbccddeeff11223344556677\",\n"
                + "  \"non_diagnostic_classes\": [\"not_a_leaf\"]\n"
                + "}");
        GateValidationEvidence evidence = new GateValidationEvidence(
                gateManifest, "report",
                "aaabbbccc00011122233344455566677889900aabbccddeeff11223344556677",
                0.98f, 0.0002f);

        ValidatedLeafGate gate = new ValidatedLeafGate("leaf_gate_v1.0", crashingEvaluator, evidence);
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        ByteBuffer tensorBuffer = ByteBuffer.allocate(ModelConfig.BYTE_BUFFER_SIZE);
        for (int i = 0; i < ModelConfig.PIXEL_COUNT; i++) {
            tensorBuffer.put((byte) 70);
            tensorBuffer.put((byte) 140);
            tensorBuffer.put((byte) 60);
        }

        assertEquals(LeafGate.GateState.VALIDATED, gate.getState());
        LeafGate.GateResult res = gate.evaluate(mockBitmap, tensorBuffer);

        // Crashing or uninferrable semantic evaluation must FAIL CLOSED
        assertEquals(LeafGate.GateDecision.UNCERTAIN, res.getDecision());
        assertTrue(res.getExplanation().contains("Semantic leaf evaluation failed"));
    }

    // ------------------------------------------------------------------------
    // 4. Preprocessing Parity (Aspect Ratio, EXIF Orientation, RGB Channel Order)
    // ------------------------------------------------------------------------

    @Test
    public void preprocessingParity_exifAndReticleBoundsPreserveGeometry() {
        assertEquals(90, LeafPreprocessor.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_90));
        assertEquals(180, LeafPreprocessor.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_180));
        assertEquals(270, LeafPreprocessor.exifOrientationToDegrees(ExifInterface.ORIENTATION_ROTATE_270));

        // Reticle bounds computation must be square and bounded
        int[] bounds = LeafPreprocessor.computeReticleCropBounds(1920, 1080, 1920, 1080, 600, 600);
        assertEquals(600, bounds[2]); // w == h == 600
        assertTrue(bounds[0] >= 0 && bounds[0] + bounds[2] <= 1920);
        assertTrue(bounds[1] >= 0 && bounds[1] + bounds[2] <= 1080);
    }

    // ------------------------------------------------------------------------
    // 5. ConfidenceCalibrator Temperature Scaling, Entropy & Low Confidence
    // ------------------------------------------------------------------------

    @Test
    public void confidenceCalibrator_evaluateLogitsOutcome_confidentReturnsDiagnosis() {
        float[] logits = new float[] {9.5f, 0.2f, -0.5f, 0.1f, -1.0f, -0.8f, -0.5f, 0.0f};
        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                logits, TEST_LABELS, "1.0.0-int8");

        assertTrue("High confidence logits must yield Diagnosis",
                outcome instanceof ClassificationOutcome.Diagnosis);
        ClassificationOutcome.Diagnosis diag = (ClassificationOutcome.Diagnosis) outcome;
        assertEquals("rice_bacterial_leaf_blight", diag.getClassId());
        assertTrue(diag.getConfidence() >= ModelConfig.CONFIDENCE_THRESHOLD);
    }

    @Test
    public void confidenceCalibrator_evaluateLogitsOutcome_flatLogitsReturnsLowConfidence() {
        float[] flatLogits = new float[] {1.05f, 1.02f, 1.01f, 1.00f, 0.99f, 0.98f, 0.97f, 0.96f};
        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                flatLogits, TEST_LABELS, "1.0.0-int8");

        assertTrue("Flat uniform logits must yield LowConfidence",
                outcome instanceof ClassificationOutcome.LowConfidence);
        ClassificationOutcome.LowConfidence lowConf = (ClassificationOutcome.LowConfidence) outcome;
        assertEquals(ClassificationOutcome.LowConfidenceReason.HIGH_ENTROPY, lowConf.getReason());
    }

    // ------------------------------------------------------------------------
    // 6. AnalyzeLeafUseCase Boundary Guard (Persistence Isolation)
    // ------------------------------------------------------------------------

    @Test
    public void analyzeLeafUseCase_onlyDiagnosisPersistsToScanRepository() {
        LeafClassifier mockClassifier = mock(LeafClassifier.class);
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);

        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_abc");
        when(mockScanRepo.saveScanSync(anyString(), any(DiagnosisResult.class), anyString()))
                .thenReturn(mock(ScanRecord.class));

        // 1. Rejected outcome -> NEVER saved
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(
                new ClassificationOutcome.Rejected(ClassificationOutcome.RejectionReason.UNDER_EXPOSED));

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(
                mockClassifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);

        AnalyzeLeafUseCase.AnalysisOutput out1 = useCase.execute(
                mock(Bitmap.class), "/storage/leaf1.jpg", "en");
        assertNull(out1.getScanRecord());
        assertNull(out1.getAdvice());
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());

        // 2. ModelUnavailable outcome -> NEVER saved
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(
                new ClassificationOutcome.ModelUnavailable(
                        ClassificationOutcome.UnavailableReason.MODEL_CORRUPT_OR_TRUNCATED));

        AnalyzeLeafUseCase.AnalysisOutput out2 = useCase.execute(
                mock(Bitmap.class), "/storage/leaf2.jpg", "en");
        assertNull(out2.getScanRecord());
        assertNull(out2.getAdvice());
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());

        // 3. Confirmed Diagnosis outcome -> ALWAYS saved
        when(mockClassifier.classify(any(Bitmap.class))).thenReturn(
                new ClassificationOutcome.Diagnosis("tomato_early_blight", 0.92f, "1.0.0-int8"));

        AnalyzeLeafUseCase.AnalysisOutput out3 = useCase.execute(
                mock(Bitmap.class), "/storage/leaf3.jpg", "en");
        assertNotNull(out3.getScanRecord());
        verify(mockScanRepo).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    // ------------------------------------------------------------------------
    // 7. Localized Strings Verification (EN, HI, TE)
    // ------------------------------------------------------------------------

    @Test
    public void localizedStrings_containRequiredStatusMessages() throws Exception {
        List<String> requiredKeys = Arrays.asList(
                "status_model_unavailable",
                "status_image_rejected",
                "status_low_confidence_retake"
        );

        List<File> stringFiles = Arrays.asList(
                new File("src/main/res/values/strings.xml"),
                new File("src/main/res/values-hi/strings.xml"),
                new File("src/main/res/values-te/strings.xml")
        );

        for (File f : stringFiles) {
            assertTrue("String resource file must exist: " + f.getPath(), f.exists());
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String key : requiredKeys) {
                assertTrue("File " + f.getName() + " missing key: " + key,
                        content.contains("name=\"" + key + "\""));
            }
        }
    }

    // ------------------------------------------------------------------------
    // 8. Phase E Safety Gaps (G1 Non-Leaf Class, G3 Manifest, G4 Metadata)
    // ------------------------------------------------------------------------

    @Test
    public void confidenceCalibrator_nonLeafClassTop1_returnsRejectedNonLeafClass() {
        // Last index is "background_not_a_leaf"
        float[] logits = new float[] {-2.0f, -1.0f, 0.0f, 0.5f, -1.0f, -2.0f, 0.0f, 10.0f};
        java.util.Set<String> nonDiag = java.util.Collections.singleton("background_not_a_leaf");

        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                logits, TEST_LABELS, "1.0.0-int8", nonDiag);

        assertTrue("Top-1 non-leaf class must yield Rejected",
                outcome instanceof ClassificationOutcome.Rejected);
        ClassificationOutcome.Rejected rej = (ClassificationOutcome.Rejected) outcome;
        assertEquals(ClassificationOutcome.RejectionReason.NON_LEAF_CLASS, rej.getReason());
    }

    @Test
    public void confidenceCalibrator_nonLeafClassMaterialProbability_returnsLowConfidence() {
        // Class 0 ("rice_bacterial_leaf_blight") and Class 7 ("background_not_a_leaf") have close logits
        float[] logits = new float[] {4.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 3.5f};
        java.util.Set<String> nonDiag = java.util.Collections.singleton("background_not_a_leaf");

        ClassificationOutcome outcome = ConfidenceCalibrator.evaluateLogitsOutcome(
                logits, TEST_LABELS, "1.0.0-int8", nonDiag);

        assertTrue("Material probability on non-leaf class must yield LowConfidence",
                outcome instanceof ClassificationOutcome.LowConfidence);
        ClassificationOutcome.LowConfidence lc = (ClassificationOutcome.LowConfidence) outcome;
        assertEquals(ClassificationOutcome.LowConfidenceReason.CLOSE_TOP_MARGIN, lc.getReason());
    }

    @Test
    public void modelManifest_parsesValidJsonSuccessfully() {
        String json = "{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_v1\",\n"
                + "  \"model_version\": \"1.0.0-int8\",\n"
                + "  \"sha256\": \"11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 8,\n"
                + "  \"output_dtype\": \"uint8\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \"12345678\",\n"
                + "  \"non_diagnostic_classes\": [\"background_not_a_leaf\"]\n"
                + "}";

        ModelManifest manifest = ModelManifest.parse(json);
        assertEquals("1.0.0", manifest.getSchemaVersion());
        assertEquals("cropdoc_leaf_v1", manifest.getModelId());
        assertEquals("1.0.0-int8", manifest.getModelVersion());
        assertEquals("11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff", manifest.getSha256());
        assertEquals(8, manifest.getOutputClassCount());
        assertEquals("12345678", manifest.getLabelsChecksum());
        assertTrue(manifest.getNonDiagnosticClasses().contains("background_not_a_leaf"));
    }

    @Test
    public void bundledModelManifest_hasValidFormatAndMatchesBundledLabelsCrc32() throws Exception {
        File manifestFile = new File("src/main/assets/models/cropdoc_leaf_v1_manifest.json");
        File labelsFile = new File("src/main/assets/models/labels.txt");
        assertTrue(manifestFile.exists());
        assertTrue(labelsFile.exists());

        String json = new String(java.nio.file.Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8);
        ModelManifest manifest = ModelManifest.parse(json);

        assertEquals("cropdoc_leaf_v1_int8", manifest.getModelId());
        assertEquals("1.0.0-int8", manifest.getModelVersion());
        assertEquals("e5591b7019326089c54400842b74447f341441c7e51e1e9a068f3fe898fd57b8", manifest.getSha256());
        assertEquals(16, manifest.getOutputClassCount());

        List<String> lines = java.nio.file.Files.readAllLines(labelsFile.toPath(), StandardCharsets.UTF_8);
        List<String> labels = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (!t.isEmpty()) {
                labels.add(t);
            }
        }

        long crc = ModelValidator.computeLabelOrderChecksum(labels);
        String expectedHex = Long.toHexString(crc).toLowerCase(Locale.US);
        assertEquals("547ca66a", expectedHex);
        assertEquals(expectedHex, manifest.getLabelsChecksum().toLowerCase(Locale.US));
    }

    @Test
    public void modelValidator_detectsSha256MismatchAgainstManifest() {
        String manifestJson = "{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_v1\",\n"
                + "  \"model_version\": \"1.0.0-int8\",\n"
                + "  \"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 8,\n"
                + "  \"output_dtype\": \"uint8\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \""
                + Long.toHexString(ModelValidator.computeLabelOrderChecksum(TEST_LABELS)) + "\",\n"
                + "  \"non_diagnostic_classes\": [\"background_not_a_leaf\"]\n"
                + "}";

        byte[] fakeModel = new byte[600000];
        fakeModel[4] = 'T';
        fakeModel[5] = 'F';
        fakeModel[6] = 'L';
        fakeModel[7] = '3';
        ByteBuffer buf = ByteBuffer.wrap(fakeModel);

        java.io.ByteArrayInputStream stream = new java.io.ByteArrayInputStream(fakeModel);

        ModelValidator.ValidationResult result = ModelValidator.validateModelWithManifest(
                fakeModel.length,
                buf,
                stream,
                null,
                TEST_LABELS,
                "1.0.0-int8",
                manifestJson,
                "test_mismatch_key"
        );

        assertFalse("Mismatched model digest must fail validation", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.MANIFEST_DIGEST_MISMATCH, result.getReason());
        assertTrue(result.getMessage().contains("SHA-256 digest mismatch"));
    }

    @Test
    public void modelValidator_hashesBufferDirectlyWhenStreamIsNull() {
        String manifestJson = "{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_v1\",\n"
                + "  \"model_version\": \"1.0.0-int8\",\n"
                + "  \"sha256\": \"0000000000000000000000000000000000000000000000000000000000000000\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 8,\n"
                + "  \"output_dtype\": \"uint8\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \""
                + Long.toHexString(ModelValidator.computeLabelOrderChecksum(TEST_LABELS)) + "\",\n"
                + "  \"non_diagnostic_classes\": [\"background_not_a_leaf\"]\n"
                + "}";

        byte[] fakeModel = new byte[600000];
        fakeModel[4] = 'T';
        fakeModel[5] = 'F';
        fakeModel[6] = 'L';
        fakeModel[7] = '3';
        ByteBuffer buf = ByteBuffer.wrap(fakeModel);

        // Phase F requirement: null modelStream must NOT bypass digest verification!
        ModelValidator.ValidationResult result = ModelValidator.validateModelWithManifest(
                fakeModel.length,
                buf,
                null, // modelStream is null
                null,
                TEST_LABELS,
                "1.0.0-int8",
                manifestJson,
                "test_null_stream_key"
        );

        assertFalse("Missing stream must fail closed by hashing model buffer", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.MANIFEST_DIGEST_MISMATCH, result.getReason());
        assertTrue(result.getMessage().contains("SHA-256 digest mismatch"));
    }

    @Test
    public void modelValidator_detectsLabelOrderChecksumMismatch() throws Exception {
        byte[] fakeModel = new byte[600000];
        fakeModel[4] = 'T';
        fakeModel[5] = 'F';
        fakeModel[6] = 'L';
        fakeModel[7] = '3';
        ByteBuffer buf = ByteBuffer.wrap(fakeModel);
        String matchingSha256 = ModelValidator.computeSha256(buf);

        // Manifest has matching model SHA-256, but wrong label checksum
        String manifestJson = "{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_v1\",\n"
                + "  \"model_version\": \"1.0.0-int8\",\n"
                + "  \"sha256\": \"" + matchingSha256 + "\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 8,\n"
                + "  \"output_dtype\": \"uint8\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \"ffffffff\",\n" // Wrong checksum
                + "  \"non_diagnostic_classes\": [\"background_not_a_leaf\"]\n"
                + "}";

        ModelValidator.ValidationResult result = ModelValidator.validateModelWithManifest(
                fakeModel.length,
                buf,
                null,
                null,
                TEST_LABELS,
                "1.0.0-int8",
                manifestJson,
                "test_labels_mismatch_key"
        );

        assertFalse("Mismatched label checksum must fail validation", result.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.LABEL_ORDER_CHECKSUM_MISMATCH, result.getReason());
    }

    @Test
    public void analyzeLeafUseCase_strictlyGuardsPersistenceAndAdviceForAllNonDiagnosisOutcomes() {
        LeafClassifier mockClassifier = mock(LeafClassifier.class);
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_test");

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(
                mockClassifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);

        List<ClassificationOutcome> nonDiagnosisOutcomes = Arrays.asList(
                new ClassificationOutcome.Rejected(ClassificationOutcome.RejectionReason.NON_LEAF_CLASS),
                new ClassificationOutcome.Rejected(ClassificationOutcome.RejectionReason.IMAGE_DECODE_FAILED),
                new ClassificationOutcome.ModelUnavailable(ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED),
                new ClassificationOutcome.ModelUnavailable(ClassificationOutcome.UnavailableReason.GATE_UNAVAILABLE),
                new ClassificationOutcome.ModelUnavailable(ClassificationOutcome.UnavailableReason.MANIFEST_MISSING),
                new ClassificationOutcome.LowConfidence(
                        java.util.Collections.emptyList(),
                        ClassificationOutcome.LowConfidenceReason.CLOSE_TOP_MARGIN)
        );

        for (ClassificationOutcome outcome : nonDiagnosisOutcomes) {
            when(mockClassifier.classify(any(Bitmap.class))).thenReturn(outcome);

            AnalyzeLeafUseCase.AnalysisOutput output = useCase.execute(
                    mock(Bitmap.class), "/tmp/test.jpg", "en");

            assertNull("Non-diagnosis must not produce ScanRecord", output.getScanRecord());
            assertNull("Non-diagnosis must not fetch Advice", output.getAdvice());
        }

        // Verify across all iterations that repositories were NEVER invoked
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(mockAdviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    // ------------------------------------------------------------------------
    // 9. Phase G Safety Gaps (Exhaustive Gate Switch, Evidence-Based Gate, Tensor Dtype/Shape)
    // ------------------------------------------------------------------------

    @Test
    public void liteRtLeafClassifier_gateDecisionUncertain_failsClosedWithoutRunningInterpreterAndCannotPersistScan() {
        ImageQualityGate qualityGate = mock(ImageQualityGate.class);
        when(qualityGate.evaluate(any(), any())).thenReturn(ImageQualityGate.QualityResult.accept("OK"));

        LeafGate gate = mock(LeafGate.class);
        when(gate.getState()).thenReturn(LeafGate.GateState.VALIDATED);
        when(gate.evaluate(any(), any())).thenReturn(LeafGate.GateResult.uncertain("Ambiguous foliar silhouette"));

        Interpreter mockInterpreter = mock(Interpreter.class);
        ModelValidator.ValidationResult valResult = ModelValidator.ValidationResult.success(mock(ModelManifest.class));

        LiteRtLeafClassifier classifier = new LiteRtLeafClassifier(
                qualityGate, gate, mockInterpreter, valResult, TEST_LABELS, "1.0.0-int8");

        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        ClassificationOutcome outcome = classifier.classify(mockBitmap);

        // 1. Outcome must be ModelUnavailable with GATE_UNVALIDATED
        assertTrue("UNCERTAIN gate must return ModelUnavailable",
                outcome instanceof ClassificationOutcome.ModelUnavailable);
        ClassificationOutcome.ModelUnavailable unavail = (ClassificationOutcome.ModelUnavailable) outcome;
        assertEquals(ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED, unavail.getReason());

        // 2. Interpreter must NEVER run
        verify(mockInterpreter, never()).run(any(), any());

        // 3. Integration with AnalyzeLeafUseCase: verify no scan saved and no advice fetched
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_123");

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(classifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);
        AnalyzeLeafUseCase.AnalysisOutput output = useCase.execute(mockBitmap, "/storage/uncertain.jpg", "en");

        assertNull("UNCERTAIN gate outcome must not yield ScanRecord", output.getScanRecord());
        assertNull("UNCERTAIN gate outcome must not yield Advice", output.getAdvice());
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(mockAdviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    @Test
    public void liteRtLeafClassifier_gateDecisionReject_failsClosedWithoutRunningInterpreterAndCannotPersistScan() {
        ImageQualityGate qualityGate = mock(ImageQualityGate.class);
        when(qualityGate.evaluate(any(), any())).thenReturn(ImageQualityGate.QualityResult.accept("OK"));

        LeafGate gate = mock(LeafGate.class);
        when(gate.getState()).thenReturn(LeafGate.GateState.VALIDATED);
        when(gate.evaluate(any(), any())).thenReturn(
                LeafGate.GateResult.reject(
                        ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED, "Skin texture detected"));

        Interpreter mockInterpreter = mock(Interpreter.class);
        ModelValidator.ValidationResult valResult = ModelValidator.ValidationResult.success(mock(ModelManifest.class));

        LiteRtLeafClassifier classifier = new LiteRtLeafClassifier(
                qualityGate, gate, mockInterpreter, valResult, TEST_LABELS, "1.0.0-int8");

        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        ClassificationOutcome outcome = classifier.classify(mockBitmap);

        // 1. Outcome must be Rejected with NON_LEAF_DETECTED
        assertTrue("REJECT gate must return Rejected", outcome instanceof ClassificationOutcome.Rejected);
        ClassificationOutcome.Rejected rej = (ClassificationOutcome.Rejected) outcome;
        assertEquals(ClassificationOutcome.RejectionReason.NON_LEAF_DETECTED, rej.getReason());

        // 2. Interpreter must NEVER run
        verify(mockInterpreter, never()).run(any(), any());

        // 3. Integration with AnalyzeLeafUseCase: verify no scan saved and no advice fetched
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_123");

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(classifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);
        AnalyzeLeafUseCase.AnalysisOutput output = useCase.execute(mockBitmap, "/storage/reject.jpg", "en");

        assertNull("REJECT gate outcome must not yield ScanRecord", output.getScanRecord());
        assertNull("REJECT gate outcome must not yield Advice", output.getAdvice());
        verify(mockScanRepo, never()).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
        verify(mockAdviceRepo, never()).getAdviceSync(anyString(), anyString());
    }

    @Test
    public void liteRtLeafClassifier_gateDecisionAcceptWithUnvalidatedGate_failsClosedWithoutRunningInterpreter() {
        ImageQualityGate qualityGate = mock(ImageQualityGate.class);
        when(qualityGate.evaluate(any(), any())).thenReturn(ImageQualityGate.QualityResult.accept("OK"));

        LeafGate gate = mock(LeafGate.class);
        when(gate.getState()).thenReturn(LeafGate.GateState.PRESENT_UNVALIDATED);
        when(gate.evaluate(any(), any())).thenReturn(
                LeafGate.GateResult.accept("Unvalidated gate erroneously accepted"));

        Interpreter mockInterpreter = mock(Interpreter.class);
        ModelValidator.ValidationResult valResult = ModelValidator.ValidationResult.success(mock(ModelManifest.class));

        LiteRtLeafClassifier classifier = new LiteRtLeafClassifier(
                qualityGate, gate, mockInterpreter, valResult, TEST_LABELS, "1.0.0-int8");

        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);

        ClassificationOutcome outcome = classifier.classify(mockBitmap);

        assertTrue("Unvalidated gate must fail closed even if returning ACCEPT",
                outcome instanceof ClassificationOutcome.ModelUnavailable);
        assertEquals(ClassificationOutcome.UnavailableReason.GATE_UNVALIDATED,
                ((ClassificationOutcome.ModelUnavailable) outcome).getReason());
        verify(mockInterpreter, never()).run(any(), any());
    }

    @Test
    public void liteRtLeafClassifier_gateDecisionAcceptWithValidatedGate_runsInterpreterAndPermitsDiagnosis() {
        ImageQualityGate qualityGate = mock(ImageQualityGate.class);
        when(qualityGate.evaluate(any(), any())).thenReturn(ImageQualityGate.QualityResult.accept("OK"));

        LeafGate gate = mock(LeafGate.class);
        when(gate.getState()).thenReturn(LeafGate.GateState.VALIDATED);
        when(gate.evaluate(any(), any())).thenReturn(LeafGate.GateResult.accept("Valid leaf confirmed"));

        Interpreter mockInterpreter = createConfidentInterpreter();
        ModelManifest manifest = mock(ModelManifest.class);
        when(manifest.getNonDiagnosticClasses()).thenReturn(java.util.Collections.singleton("background_not_a_leaf"));
        ModelValidator.ValidationResult valResult = ModelValidator.ValidationResult.success(manifest);

        LiteRtLeafClassifier classifier = new LiteRtLeafClassifier(
                qualityGate, gate, mockInterpreter, valResult, TEST_LABELS, "1.0.0-int8");
        Bitmap mockBitmap = createMockGreenLeafBitmap();

        ClassificationOutcome outcome = classifier.classify(mockBitmap);
        assertTrue("Validated gate ACCEPT must produce Diagnosis", outcome instanceof ClassificationOutcome.Diagnosis);
        ClassificationOutcome.Diagnosis diag = (ClassificationOutcome.Diagnosis) outcome;
        assertEquals("rice_bacterial_leaf_blight", diag.getClassId());
        verify(mockInterpreter).run(any(), any());

        // Integration with AnalyzeLeafUseCase
        AdviceRepository mockAdviceRepo = mock(AdviceRepository.class);
        ScanRepository mockScanRepo = mock(ScanRepository.class);
        AuthRepository mockAuthRepo = mock(AuthRepository.class);
        when(mockAuthRepo.getCurrentUserId()).thenReturn("user_123");
        when(mockScanRepo.saveScanSync(anyString(), any(DiagnosisResult.class), anyString()))
                .thenReturn(mock(ScanRecord.class));

        AnalyzeLeafUseCase useCase = new AnalyzeLeafUseCase(classifier, mockAdviceRepo, mockScanRepo, mockAuthRepo);
        AnalyzeLeafUseCase.AnalysisOutput output = useCase.execute(mockBitmap, "/storage/confirmed.jpg", "en");

        assertNotNull("Validated diagnosis must yield ScanRecord", output.getScanRecord());
        verify(mockScanRepo).saveScanSync(anyString(), any(DiagnosisResult.class), anyString());
    }

    private Interpreter createConfidentInterpreter() {
        Interpreter mockInterpreter = mock(Interpreter.class);
        org.mockito.Mockito.doAnswer(invocation -> {
            float[][] out = invocation.getArgument(1);
            out[0][0] = 9.5f;
            out[0][1] = -1.0f;
            out[0][2] = -1.0f;
            out[0][3] = -2.0f;
            return null;
        }).when(mockInterpreter).run(any(), any());
        return mockInterpreter;
    }

    private Bitmap createMockGreenLeafBitmap() {
        Bitmap mockBitmap = mock(Bitmap.class);
        when(mockBitmap.getWidth()).thenReturn(224);
        when(mockBitmap.getHeight()).thenReturn(224);
        org.mockito.Mockito.doAnswer(invocation -> {
            int[] pixels = invocation.getArgument(0);
            Arrays.fill(pixels, 0xFF468C3C);
            return null;
        }).when(mockBitmap).getPixels(any(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
        return mockBitmap;
    }

    private String createBaseGateManifestJson() {
        return "{\n"
                + "  \"schema_version\": \"1.0.0\",\n"
                + "  \"model_id\": \"cropdoc_leaf_gate_v1\",\n"
                + "  \"model_version\": \"1.0.0\",\n"
                + "  \"sha256\": \"11223344556677889900aabbccddeeff11223344556677889900aabbccddeeff\",\n"
                + "  \"input_shape\": [1, 224, 224, 3],\n"
                + "  \"input_dtype\": \"uint8\",\n"
                + "  \"output_class_count\": 2,\n"
                + "  \"output_dtype\": \"float32\",\n"
                + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                + "  \"labels_checksum\": \"12345678\",\n"
                + "  \"evidence_report_hash\": \"trusted_report_hash_123\",\n"
                + "  \"non_diagnostic_classes\": [\"not_a_leaf\"]\n"
                + "}";
    }

    @Test
    public void gateValidationEvidence_failsClosedOnMissingStaleOrMismatchedEvidence() {
        String baseManifestJson = createBaseGateManifestJson();
        ModelManifest baseManifest = ModelManifest.parse(baseManifestJson);
        SemanticLeafEvaluator mockEvaluator = mock(SemanticLeafEvaluator.class);
        ImageQualityGate mockQuality = mock(ImageQualityGate.class);
        when(mockQuality.evaluate(any(), any())).thenReturn(ImageQualityGate.QualityResult.accept("OK"));

        // 1. Missing evidence -> PRESENT_UNVALIDATED
        ValidatedLeafGate gateNoEvidence = new ValidatedLeafGate("v1", mockQuality, mockEvaluator, null);
        assertEquals(LeafGate.GateState.PRESENT_UNVALIDATED, gateNoEvidence.getState());
        assertEquals(LeafGate.GateDecision.UNCERTAIN,
                gateNoEvidence.evaluate(mock(Bitmap.class), ByteBuffer.allocate(100)).getDecision());

        // 2. Report hash mismatch
        GateValidationEvidence evidenceHashMismatch = new GateValidationEvidence(
                baseManifest, "Report", "wrong_hash_456", 0.98f, 0.0005f);
        assertFalse("Hash mismatch must fail certification", evidenceHashMismatch.isCertified());
        ValidatedLeafGate gateHashMismatch = new ValidatedLeafGate("v1", mockEvaluator, evidenceHashMismatch);
        assertEquals(LeafGate.GateState.PRESENT_UNVALIDATED, gateHashMismatch.getState());

        // 3. FAR exceeds threshold (> 0.001)
        GateValidationEvidence evidenceHighFar = new GateValidationEvidence(
                baseManifest, "Report", "trusted_report_hash_123", 0.98f, 0.005f);
        assertFalse("High FAR must fail certification", evidenceHighFar.isCertified());

        // 4. TAR below threshold (< 0.95)
        GateValidationEvidence evidenceLowTar = new GateValidationEvidence(
                baseManifest, "Report", "trusted_report_hash_123", 0.92f, 0.0005f);
        assertFalse("Low TAR must fail certification", evidenceLowTar.isCertified());
    }

    @Test
    public void gateValidationEvidence_failsClosedOnContractMismatches() {
        String baseManifestJson = createBaseGateManifestJson();
        ModelManifest baseManifest = ModelManifest.parse(baseManifestJson);
        SemanticLeafEvaluator mockEvaluator = mock(SemanticLeafEvaluator.class);

        // 1. Input shape mismatch
        String wrongShapeJson = baseManifestJson.replace("[1, 224, 224, 3]", "[1, 128, 128, 3]");
        GateValidationEvidence evidenceWrongShape = new GateValidationEvidence(
                ModelManifest.parse(wrongShapeJson), "Report", "trusted_report_hash_123", 0.98f, 0.0005f);
        assertFalse("Wrong input shape must fail certification", evidenceWrongShape.isCertified());

        // 2. Input dtype mismatch
        String wrongDtypeJson = baseManifestJson.replace(
                "\"input_dtype\": \"uint8\"", "\"input_dtype\": \"float32\"");
        GateValidationEvidence evidenceWrongDtype = new GateValidationEvidence(
                ModelManifest.parse(wrongDtypeJson), "Report", "trusted_report_hash_123", 0.98f, 0.0005f);
        assertFalse("Wrong input dtype must fail certification", evidenceWrongDtype.isCertified());

        // 3. Output class count mismatch (must be binary 2)
        String wrongClassCountJson = baseManifestJson.replace(
                "\"output_class_count\": 2", "\"output_class_count\": 16");
        GateValidationEvidence evidenceWrongClassCount = new GateValidationEvidence(
                ModelManifest.parse(wrongClassCountJson), "Report", "trusted_report_hash_123", 0.98f, 0.0005f);
        assertFalse("Output class count != 2 must fail certification", evidenceWrongClassCount.isCertified());

        // 4. Fully certified evidence -> VALIDATED
        GateValidationEvidence certifiedEvidence = new GateValidationEvidence(
                baseManifest, "Report", "trusted_report_hash_123", 0.97f, 0.0008f);
        assertTrue("Valid evidence must pass certification", certifiedEvidence.isCertified());
        ValidatedLeafGate certifiedGate = new ValidatedLeafGate("v1", mockEvaluator, certifiedEvidence);
        assertEquals(LeafGate.GateState.VALIDATED, certifiedGate.getState());
    }

    private static final class TensorValidationFixture {
        final byte[] fakeModel;
        final ByteBuffer buf;
        final String manifestJson;

        TensorValidationFixture() throws Exception {
            fakeModel = new byte[600000];
            fakeModel[4] = 'T';
            fakeModel[5] = 'F';
            fakeModel[6] = 'L';
            fakeModel[7] = '3';
            buf = ByteBuffer.wrap(fakeModel);
            String matchingSha256 = ModelValidator.computeSha256(buf);
            manifestJson = "{\n"
                    + "  \"schema_version\": \"1.0.0\",\n"
                    + "  \"model_id\": \"cropdoc_leaf_v1\",\n"
                    + "  \"model_version\": \"1.0.0-int8\",\n"
                    + "  \"sha256\": \"" + matchingSha256 + "\",\n"
                    + "  \"input_shape\": [1, 224, 224, 3],\n"
                    + "  \"input_dtype\": \"uint8\",\n"
                    + "  \"output_class_count\": 8,\n"
                    + "  \"output_dtype\": \"float32\",\n"
                    + "  \"preprocessing_contract_id\": \"cropdoc-preprocess-v1\",\n"
                    + "  \"labels_checksum\": \""
                    + Long.toHexString(ModelValidator.computeLabelOrderChecksum(TEST_LABELS)) + "\",\n"
                    + "  \"non_diagnostic_classes\": [\"background_not_a_leaf\"]\n"
                    + "}";
        }
    }

    @Test
    public void modelValidator_tensorValidation_detectsDtypeMismatches() throws Exception {
        TensorValidationFixture fix = new TensorValidationFixture();

        // Subtest A: Wrong input dtype (expected uint8, got FLOAT32)
        Interpreter mockInDtypeInterp = mock(Interpreter.class);
        Tensor badInDtypeTensor = mock(Tensor.class);
        when(badInDtypeTensor.shape()).thenReturn(new int[] {1, 224, 224, 3});
        when(badInDtypeTensor.dataType()).thenReturn(DataType.FLOAT32);
        when(mockInDtypeInterp.getInputTensorCount()).thenReturn(1);
        when(mockInDtypeInterp.getInputTensor(0)).thenReturn(badInDtypeTensor);

        ModelValidator.ValidationResult resA = ModelValidator.validateModelWithManifest(
                fix.fakeModel.length, fix.buf, null, mockInDtypeInterp, TEST_LABELS,
                "1.0.0-int8", fix.manifestJson, "keyA");
        assertFalse(resA.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE, resA.getReason());
        assertTrue(resA.getMessage().contains("Input tensor dtype mismatch"));

        // Subtest B: Wrong output dtype (expected float32, got UINT8)
        Interpreter mockOutDtypeInterp = mock(Interpreter.class);
        Tensor goodInTensor = mock(Tensor.class);
        when(goodInTensor.shape()).thenReturn(new int[] {1, 224, 224, 3});
        when(goodInTensor.dataType()).thenReturn(DataType.UINT8);
        when(mockOutDtypeInterp.getInputTensorCount()).thenReturn(1);
        when(mockOutDtypeInterp.getInputTensor(0)).thenReturn(goodInTensor);

        Tensor badOutDtypeTensor = mock(Tensor.class);
        when(badOutDtypeTensor.shape()).thenReturn(new int[] {1, 8});
        when(badOutDtypeTensor.dataType()).thenReturn(DataType.UINT8);
        when(mockOutDtypeInterp.getOutputTensorCount()).thenReturn(1);
        when(mockOutDtypeInterp.getOutputTensor(0)).thenReturn(badOutDtypeTensor);

        ModelValidator.ValidationResult resB = ModelValidator.validateModelWithManifest(
                fix.fakeModel.length, fix.buf, null, mockOutDtypeInterp, TEST_LABELS,
                "1.0.0-int8", fix.manifestJson, "keyB");
        assertFalse(resB.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE, resB.getReason());
        assertTrue(resB.getMessage().contains("Output tensor dtype mismatch"));
    }

    @Test
    public void modelValidator_tensorValidation_detectsShapeAndClassCountMismatches() throws Exception {
        TensorValidationFixture fix = new TensorValidationFixture();
        Tensor goodInTensor = mock(Tensor.class);
        when(goodInTensor.shape()).thenReturn(new int[] {1, 224, 224, 3});
        when(goodInTensor.dataType()).thenReturn(DataType.UINT8);

        // Subtest C: Wrong input shape ([1, 128, 128, 3] instead of [1, 224, 224, 3])
        Interpreter mockInShapeInterp = mock(Interpreter.class);
        Tensor badInShapeTensor = mock(Tensor.class);
        when(badInShapeTensor.shape()).thenReturn(new int[] {1, 128, 128, 3});
        when(badInShapeTensor.dataType()).thenReturn(DataType.UINT8);
        when(mockInShapeInterp.getInputTensorCount()).thenReturn(1);
        when(mockInShapeInterp.getInputTensor(0)).thenReturn(badInShapeTensor);

        ModelValidator.ValidationResult resC = ModelValidator.validateModelWithManifest(
                fix.fakeModel.length, fix.buf, null, mockInShapeInterp, TEST_LABELS,
                "1.0.0-int8", fix.manifestJson, "keyC");
        assertFalse(resC.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.INCOMPATIBLE_TENSOR_SHAPE, resC.getReason());
        assertTrue(resC.getMessage().contains("Input tensor shape mismatch"));

        // Subtest D: Wrong output class count ([1, 16] instead of 8)
        Interpreter mockOutCountInterp = mock(Interpreter.class);
        when(mockOutCountInterp.getInputTensorCount()).thenReturn(1);
        when(mockOutCountInterp.getInputTensor(0)).thenReturn(goodInTensor);

        Tensor badOutCountTensor = mock(Tensor.class);
        when(badOutCountTensor.shape()).thenReturn(new int[] {1, 16});
        when(badOutCountTensor.dataType()).thenReturn(DataType.FLOAT32);
        when(mockOutCountInterp.getOutputTensorCount()).thenReturn(1);
        when(mockOutCountInterp.getOutputTensor(0)).thenReturn(badOutCountTensor);

        ModelValidator.ValidationResult resD = ModelValidator.validateModelWithManifest(
                fix.fakeModel.length, fix.buf, null, mockOutCountInterp, TEST_LABELS,
                "1.0.0-int8", fix.manifestJson, "keyD");
        assertFalse(resD.isValid());
        assertEquals(ClassificationOutcome.UnavailableReason.LABELS_MISMATCH, resD.getReason());
        assertTrue(resD.getMessage().contains("Output tensor class count"));
    }

    @Test
    public void modelValidator_tensorValidation_passesMatchingTensors() throws Exception {
        TensorValidationFixture fix = new TensorValidationFixture();
        Tensor goodInTensor = mock(Tensor.class);
        when(goodInTensor.shape()).thenReturn(new int[] {1, 224, 224, 3});
        when(goodInTensor.dataType()).thenReturn(DataType.UINT8);

        Interpreter goodInterp = mock(Interpreter.class);
        when(goodInterp.getInputTensorCount()).thenReturn(1);
        when(goodInterp.getInputTensor(0)).thenReturn(goodInTensor);

        Tensor goodOutTensor = mock(Tensor.class);
        when(goodOutTensor.shape()).thenReturn(new int[] {1, 8});
        when(goodOutTensor.dataType()).thenReturn(DataType.FLOAT32);
        when(goodInterp.getOutputTensorCount()).thenReturn(1);
        when(goodInterp.getOutputTensor(0)).thenReturn(goodOutTensor);

        ModelValidator.ValidationResult resE = ModelValidator.validateModelWithManifest(
                fix.fakeModel.length, fix.buf, null, goodInterp, TEST_LABELS,
                "1.0.0-int8", fix.manifestJson, "keyE");
        assertTrue("Matching tensors must pass validation", resE.isValid());
    }
}
