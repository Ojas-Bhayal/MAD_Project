package com.cropdoc.app.ml.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import org.junit.Test;

/**
 * Validates TFLite research model assets, flatbuffer magic headers, cryptographic digests,
 * and input NCHW tensor buffer layout contracts.
 */
public final class TfliteModelAssetIntegrityTest {

    private static final String DOMAIN_MODEL_PATH =
            "src/debug/assets/research_models/crop_domain_gate_fp32.tflite";
    private static final String SIX_CLASS_MODEL_PATH =
            "src/debug/assets/research_models/unified_six_class_fp32.tflite";
    private static final String PROD_MODEL_PATH =
            "src/main/assets/models/cropdoc_leaf_v1_int8.tflite";

    private static final String DOMAIN_MODEL_SHA256 =
            "a8c39823dbb1cbe5b81acccc65412c4a230283ee22539aec359602215be2fe3d";
    private static final String SIX_CLASS_MODEL_SHA256 =
            "1d3b8b2773c3b0580d77e54419a99f594c0ef21f04a8b098f22f6f756938bb96";

    @Test
    public void researchModels_existAndPreserveCryptographicDigests() throws Exception {
        File domainFile = new File(DOMAIN_MODEL_PATH);
        File sixClassFile = new File(SIX_CLASS_MODEL_PATH);

        assertTrue("Domain gate model must exist in research assets", domainFile.exists());
        assertTrue("Unified 6-class model must exist in research assets", sixClassFile.exists());

        assertTrue("Domain gate file size must exceed 5MB", domainFile.length() > 5 * 1024 * 1024);
        assertTrue("6-class model file size must exceed 5MB", sixClassFile.length() > 5 * 1024 * 1024);

        assertEquals("Domain gate SHA-256 mismatch",
                DOMAIN_MODEL_SHA256, computeFileSha256(domainFile));
        assertEquals("Unified 6-class SHA-256 mismatch",
                SIX_CLASS_MODEL_SHA256, computeFileSha256(sixClassFile));
    }

    @Test
    public void productionModel_remainsIsolatedAndUntouched() {
        File prodModel = new File(PROD_MODEL_PATH);
        assertTrue("Production model must remain intact in assets/models/", prodModel.exists());
        assertTrue("Production model must not be empty", prodModel.length() > 0);
    }

    @Test
    public void researchModels_containValidTfliteFlatBufferMagic() throws Exception {
        assertTfliteMagicHeader(new File(DOMAIN_MODEL_PATH));
        assertTfliteMagicHeader(new File(SIX_CLASS_MODEL_PATH));
    }

    @Test
    public void prepareNormalizedFloatBuffer_strictlyFollowsNchwLayout() {
        int[] pixels = new int[224 * 224];
        // Pixel 0 is pure Red (0xFFFF0000)
        pixels[0] = 0xFFFF0000;
        // Pixel 1 is pure Green (0xFF00FF00)
        pixels[1] = 0xFF00FF00;
        // Pixel 2 is pure Blue (0xFF0000FF)
        pixels[2] = 0xFF0000FF;

        ByteBuffer buf = ResearchInferenceHarness.prepareNormalizedFloatBuffer(pixels);
        assertNotNull(buf);
        assertEquals(LiteRtResearchModelExecutor.EXPECTED_NCHW_BYTES, buf.remaining());

        // Channel plane offsets for 224x224 (50,176 floats = 200,704 bytes per channel)
        int planeFloats = 224 * 224;

        // Verify Red channel of pixel 0: (1.0 - 0.485) / 0.229 = 2.2489f
        buf.position(0);
        float r0 = buf.getFloat();
        assertEquals(2.2489f, r0, 1e-3f);

        // Verify Green channel of pixel 0 in green plane: (0.0 - 0.456) / 0.224 = -2.0357f
        buf.position(planeFloats * 4);
        float g0 = buf.getFloat();
        assertEquals(-2.0357f, g0, 1e-3f);

        // Verify Blue channel of pixel 0 in blue plane: (0.0 - 0.406) / 0.225 = -1.8044f
        buf.position(planeFloats * 8);
        float b0 = buf.getFloat();
        assertEquals(-1.8044f, b0, 1e-3f);
    }

    @Test
    public void prepareNormalizedFloatBuffer_channelPlanesAreSeparated() {
        int[] pixels = new int[224 * 224];
        // Fill all pixels with pure red
        java.util.Arrays.fill(pixels, 0xFFFF0000);

        ByteBuffer buf = ResearchInferenceHarness.prepareNormalizedFloatBuffer(pixels);
        int planeFloats = 224 * 224;

        // Check first and last float in Red plane
        buf.position(0);
        assertEquals(2.2489f, buf.getFloat(), 1e-3f);
        buf.position((planeFloats - 1) * 4);
        assertEquals(2.2489f, buf.getFloat(), 1e-3f);

        // Check first and last float in Green plane: should all be black normalized value
        buf.position(planeFloats * 4);
        assertEquals(-2.0357f, buf.getFloat(), 1e-3f);
        buf.position((2 * planeFloats - 1) * 4);
        assertEquals(-2.0357f, buf.getFloat(), 1e-3f);

        // Check first and last float in Blue plane: should all be black normalized value
        buf.position(2 * planeFloats * 4);
        assertEquals(-1.8044f, buf.getFloat(), 1e-3f);
        buf.position((3 * planeFloats - 1) * 4);
        assertEquals(-1.8044f, buf.getFloat(), 1e-3f);
    }

    private static void assertTfliteMagicHeader(File file) throws Exception {
        byte[] magic = new byte[4];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(4);
            raf.readFully(magic);
        }
        String id = new String(magic, StandardCharsets.US_ASCII);
        assertEquals("TFLite flatbuffer magic identifier at offset 4 must be 'TFL3'",
                "TFL3", id);
    }

    private static String computeFileSha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read;
            while ((read = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        byte[] hash = digest.digest();
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format(Locale.US, "%02x", b));
        }
        return sb.toString();
    }
}
