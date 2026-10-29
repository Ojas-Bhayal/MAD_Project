# Phase H: English, Hindi, and Telugu Localization and Accessibility Audit

**Audit Date:** 2026-10-24T23:38:00+05:30  
**Project:** CropDoc (`com.cropdoc.app`)  
**Auditor:** Mobile Localization Specialist, Accessibility (a11y) & UX Auditor  

---

## 1. Executive Summary: Localization Parity & In-App Language Architecture

CropDoc provides trilingual support tailored for Indian agricultural smallholder farmers across:
1. **English (`en`)**
2. **Hindi (`hi`)** — Northern & Central agricultural belts
3. **Telugu (`te`)** — Andhra Pradesh & Telangana rice and groundnut farming regions

### Core Verification Findings:
- **100% Translatable Key Parity:**
  - `values/strings.xml`: 144 keys total (139 translatable user-facing strings + 5 declared `translatable="false"` system/format keys).
  - `values-hi/strings.xml`: Exactly 139 translated Hindi keys (0 missing, 0 untranslated).
  - `values-te/strings.xml`: Exactly 139 translated Telugu keys (0 missing, 0 untranslated).
- **Automated Test Verification:**
  - [`ResearchLocalizationTest.java`](file:///C:/Users/ojas/OneDrive/Desktop/MajorProjectMAD/CropDoc/app/src/test/java/com/cropdoc/app/ui/research/ResearchLocalizationTest.java) executes key existence, placeholder format matching (`%s`, `%d`), and non-empty translation checks across all three language files. All tests passed (`BUILD SUCCESSFUL`).
- **Dynamic In-App Locale Switching:**
  - Uses modern AndroidX AppCompat 1.8.0 API: `AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(selectedTag))`.
  - Seamlessly recreates active activities without killing the application process or destroying the backstack.
  - Retains language selection offline across process restarts via `AppLocalesMetadataHolderService` in `AndroidManifest.xml`.
  - Android App Bundle split configuration (`bundle.language.enableSplit = false`) guarantees all three language packs are bundled locally on the device, enabling instant offline switching in rural areas with zero connectivity.

---

## 2. String Resource Parity Table

| Functional Domain | String Keys Verified | English (`values/`) | Hindi (`values-hi/`) | Telugu (`values-te/`) | Key Parity |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **App Shell & Navigation** | `app_name`, `nav_scan`, `nav_result`, `nav_history`, `nav_account`, `nav_research_demo` | Complete | Complete | Complete | **100%** |
| **Authentication & DPDP** | `auth_title`, `btn_google_sign_in`, `btn_guest_mode`, `dpdp_notice_*`, `account_delete_*` | Complete | Complete | Complete | **100%** |
| **Camera & Scan Screen** | `btn_capture_leaf`, `btn_pick_gallery`, `btn_toggle_torch_*`, `status_model_unavailable` | Complete | Complete | Complete | **100%** |
| **Result & Agronomic Advice**| `crop_and_disease_format`, `confidence_format`, `heading_causes`, `heading_prevention`, `btn_speak_advice` | Complete | Complete | Complete | **100%** |
| **History & Offline Data** | `history_empty_title`, `history_scan_count` (plurals), `confirm_delete_history_*` | Complete | Complete | Complete | **100%** |
| **Research Demo & Telemetry**| `btn_capture_research_photo`, `research_stage_*`, `research_status_*`, `research_telemetry_*` | Complete | Complete | Complete | **100%** |
| **Total Translatable Keys** | **139 User-Facing Keys** | **139** | **139** | **139** | **100%** |

---

## 3. Untranslatable System Keys Audit

The 5 untranslatable keys in `app/src/main/res/values/strings.xml` were audited and confirmed to be non-user-facing system constants:
1. `default_web_client_id`: OAuth Web Client ID for Google Identity services (`translatable="false"`).
2. `lang_english`: Plaintext label `"English"` (`translatable="false"`).
3. `lang_hindi`: Native script label `"हिंदी"` (`translatable="false"`).
4. `lang_telugu`: Native script label `"తెలుగు"` (`translatable="false"`).
5. `crop_and_disease_format`: Pure format string `"%1$s — %2$s"` (`translatable="false"`).

---

## 4. Layout Hardcoding & Localization Safety

1. **Zero Hardcoded Text in XML:**
   - Static inspection across all layout files in `app/src/main/res/layout/` identified **0 instances** of hardcoded string attributes (`android:text="abc"`). Every UI text element references an `@string/` resource.
2. **Pluralization Support:**
   - History item counts use Android plural resources (`R.plurals.history_scan_count`) with valid `one` and `other` quantities across English, Hindi, and Telugu.
3. **Agronomic Advice Localization:**
   - In addition to UI strings, offline treatment advice in `app/src/main/assets/advice/advice_catalog.json` contains native trilingual text for all 16 crop conditions (causes, cultural/biological controls, chemical controls with CIB&RC guidelines, and prevention steps).

---

## 5. Mobile Accessibility (a11y) Evaluation

1. **Screen Reader Support (`contentDescription`):**
   - Icon-only controls (e.g., `btn_toggle_torch`, `btn_thumb_up`, `btn_thumb_down`, `btn_speak_advice`, thumbnail image views) declare explicit `@string/` content descriptions.
   - Decorative views declare `android:importantForAccessibility="no"` to avoid screen-reader clutter.
2. **Touch Target Dimensions:**
   - Core interactive actions (`btn_capture_leaf`, `btn_capture_research_photo`, `btn_pick_gallery`, `btn_lang_en`, `btn_lang_hi`, `btn_lang_te`) satisfy Android's minimum $48 \times 48\text{ dp}$ touch target guidelines.
3. **Dynamic Font Scaling & Text Overflow:**
   - All text sizes are defined in scalable pixels (`sp`).
   - Labels in the Research Demo and Result cards declare `android:ellipsize="end"` and flexible constraint chains, preventing clipping when larger Hindi/Telugu complex conjunct characters are rendered.
4. **Color Contrast:**
   - Primary text uses high-contrast tokens (`@color/on_surface` on `@color/surface`).
   - Rejection status banners use `@color/error_red` with bold typography, accompanied by explicit textual icons/prefixes so status information is not conveyed by color alone.
