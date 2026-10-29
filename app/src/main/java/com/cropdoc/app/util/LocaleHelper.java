package com.cropdoc.app.util;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Per-app language switcher supporting English ("en"), Hindi ("hi"), and Telugu ("te")
 * via AndroidX AppCompat 1.8.0 without restarting the process.
 */
public final class LocaleHelper {

    public static final String LANG_ENGLISH = "en";
    public static final String LANG_HINDI = "hi";
    public static final String LANG_TELUGU = "te";

    private LocaleHelper() {
        // Utility class
    }

    public static void applyLanguage(@NonNull String languageTag) {
        if (languageTag.equals(getCurrentLanguageCode())) {
            return;
        }
        LocaleListCompat appLocale = LocaleListCompat.forLanguageTags(languageTag);
        AppCompatDelegate.setApplicationLocales(appLocale);
    }

    @NonNull
    public static String getCurrentLanguageCode() {
        LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
        if (!current.isEmpty() && current.get(0) != null) {
            String lang = current.get(0).getLanguage();
            if (LANG_HINDI.equals(lang) || LANG_TELUGU.equals(lang)) {
                return lang;
            }
        }
        return LANG_ENGLISH;
    }
}
