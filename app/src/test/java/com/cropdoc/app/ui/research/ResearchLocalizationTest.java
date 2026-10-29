package com.cropdoc.app.ui.research;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Before;
import org.junit.Test;

/**
 * Regression test verifying 100% string coverage and parity for Research Demo
 * across English (values), Hindi (values-hi), and Telugu (values-te).
 */
public final class ResearchLocalizationTest {

    private static final String[] RESEARCH_KEYS = {
        "nav_research_demo",
        "research_disclaimer_title",
        "research_disclaimer_body",
        "research_disabled_in_release",
        "research_models_unavailable_warning",
        "btn_capture_research_photo",
        "btn_pick_research_image",
        "btn_sample_rice",
        "btn_sample_soil",
        "btn_run_research_inference",
        "btn_retry_research",
        "research_preview_placeholder",
        "research_loading_text",
        "research_card_results_title",
        "research_stage_quality_hdr",
        "research_stage_foliar_hdr",
        "research_stage_domain_hdr",
        "research_stage_disease_hdr",
        "research_stage_decision_hdr",
        "research_latency_summary_hdr",
        "research_taxonomy_title",
        "research_taxonomy_content",
        "research_status_passed",
        "research_status_rejected",
        "research_status_bypassed",
        "research_status_not_run",
        "research_status_inconclusive",
        "research_status_error",
        "research_status_quality_passed",
        "research_status_quality_rejected",
        "research_status_foliar_passed",
        "research_status_foliar_rejected",
        "research_status_domain_bypassed",
        "research_status_domain_passed_fmt",
        "research_status_domain_unsupported_fmt",
        "research_status_domain_uncertain_fmt",
        "research_status_domain_unavailable",
        "research_status_disease_bypassed",
        "research_status_disease_executed_fmt",
        "research_status_decision_accepted",
        "research_status_decision_rejected",
        "research_status_latency_fmt",
        "research_latency_ms_fmt",
        "crop_rice",
        "crop_groundnut",
        "crop_unsupported",
        "disease_rice_bacterial_blight",
        "disease_rice_brown_spot",
        "disease_rice_blast",
        "disease_rice_healthy",
        "disease_groundnut_early_leaf_spot",
        "disease_groundnut_healthy",
        "outcome_accepted_supported",
        "outcome_inconclusive_low_conf",
        "outcome_out_of_domain",
        "outcome_invalid_model_output",
        "outcome_model_unavailable",
        "rejection_detail_foliar_non_leaf",
        "rejection_detail_unsupported_crop",
        "rejection_detail_domain_uncertain",
        "rejection_detail_low_confidence",
        "rejection_detail_high_entropy",
        "rejection_detail_model_unavailable",
        "research_decision_accepted_explanation",
        "research_error_title",
        "research_error_open_image",
        "research_error_decode_failed",
        "research_error_inference_failed",
        "research_error_no_image_selected",
        "research_camera_permission_denied_title",
        "research_camera_permission_denied_msg"
    };

    private Map<String, String> englishStrings;
    private Map<String, String> hindiStrings;
    private Map<String, String> teluguStrings;

    @Before
    public void setUp() throws Exception {
        File resDir = new File("src/main/res");
        if (!resDir.exists()) {
            resDir = new File("app/src/main/res");
        }
        englishStrings = parseStringsXml(new File(resDir, "values/strings.xml"));
        hindiStrings = parseStringsXml(new File(resDir, "values-hi/strings.xml"));
        teluguStrings = parseStringsXml(new File(resDir, "values-te/strings.xml"));
    }

    @Test
    public void testEnglishStringsCoverage() {
        for (String key : RESEARCH_KEYS) {
            assertTrue("English missing key: " + key, englishStrings.containsKey(key));
            assertFalse("English empty value for key: " + key, englishStrings.get(key).trim().isEmpty());
        }
    }

    @Test
    public void testHindiStringsCoverage() {
        for (String key : RESEARCH_KEYS) {
            assertTrue("Hindi missing key: " + key, hindiStrings.containsKey(key));
            assertFalse("Hindi empty value for key: " + key, hindiStrings.get(key).trim().isEmpty());
        }
    }

    @Test
    public void testTeluguStringsCoverage() {
        for (String key : RESEARCH_KEYS) {
            assertTrue("Telugu missing key: " + key, teluguStrings.containsKey(key));
            assertFalse("Telugu empty value for key: " + key, teluguStrings.get(key).trim().isEmpty());
        }
    }

    @Test
    public void testFormatSpecifiersParityAcrossLocales() {
        Pattern placeholderPattern = Pattern.compile("%\\d*\\$?[a-zA-Z]");
        for (String key : RESEARCH_KEYS) {
            String enVal = englishStrings.get(key);
            String hiVal = hindiStrings.get(key);
            String teVal = teluguStrings.get(key);

            Set<String> enPlaceholders = extractPlaceholders(enVal, placeholderPattern);
            Set<String> hiPlaceholders = extractPlaceholders(hiVal, placeholderPattern);
            Set<String> tePlaceholders = extractPlaceholders(teVal, placeholderPattern);

            assertEquals("Format specifier mismatch in Hindi for key: " + key, enPlaceholders, hiPlaceholders);
            assertEquals("Format specifier mismatch in Telugu for key: " + key, enPlaceholders, tePlaceholders);
        }
    }

    @Test
    public void testDecisionAcceptedMatchesExperimentalTerminology() {
        String enAccepted = englishStrings.get("research_status_decision_accepted");
        assertNotNull(enAccepted);
        assertTrue("Accepted outcome must state 'Experimental'", enAccepted.contains("Experimental"));
        assertFalse("Accepted outcome must not state 'ACCEPTED DIAGNOSIS'", enAccepted.contains("ACCEPTED DIAGNOSIS"));
    }

    private static Set<String> extractPlaceholders(String text, Pattern pattern) {
        Set<String> placeholders = new HashSet<>();
        if (text == null) {
            return placeholders;
        }
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            placeholders.add(matcher.group());
        }
        return placeholders;
    }

    private static Map<String, String> parseStringsXml(File file) throws Exception {
        Map<String, String> map = new HashMap<>();
        String content = new String(Files.readAllBytes(file.toPath()));
        Pattern pattern = Pattern.compile("<string\\s+name=\"([^\"]+)\"[^>]*>([\\s\\S]*?)</string>");
        Matcher matcher = pattern.matcher(content);
        while (matcher.find()) {
            String key = matcher.group(1);
            String val = matcher.group(2);
            map.put(key, val);
        }
        return map;
    }
}
