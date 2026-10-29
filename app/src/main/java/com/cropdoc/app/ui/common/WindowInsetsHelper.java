package com.cropdoc.app.ui.common;

import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/**
 * Applies system-bar, camera display-cutout, and software-keyboard (IME) WindowInsets in one
 * shared place so no top bar or bottom control ever collides with hardware cutouts or gesture bars.
 */
public final class WindowInsetsHelper {

    private WindowInsetsHelper() {
        // Utility class
    }

    public static void applyShellInsets(
            @NonNull View rootView,
            @NonNull View topHeaderBar,
            @NonNull View contentContainer,
            @NonNull View bottomNavigation) {
        final int topBasePadLeft = topHeaderBar.getPaddingLeft();
        final int topBasePadTop = topHeaderBar.getPaddingTop();
        final int topBasePadRight = topHeaderBar.getPaddingRight();
        final int topBasePadBottom = topHeaderBar.getPaddingBottom();
        final int navBasePadBottom = bottomNavigation.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(rootView, (v, windowInsets) -> {
            Insets sysCutout = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            topHeaderBar.setPadding(
                    topBasePadLeft + sysCutout.left,
                    topBasePadTop + sysCutout.top,
                    topBasePadRight + sysCutout.right,
                    topBasePadBottom);

            contentContainer.setPadding(
                    sysCutout.left,
                    0,
                    sysCutout.right,
                    Math.max(0, ime.bottom - sysCutout.bottom));

            bottomNavigation.setPadding(
                    sysCutout.left,
                    0,
                    sysCutout.right,
                    navBasePadBottom + sysCutout.bottom);

            return windowInsets;
        });
    }
}
