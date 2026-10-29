package com.cropdoc.app.ui.common;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Interpolator;
import androidx.annotation.NonNull;
import androidx.core.view.animation.PathInterpolatorCompat;

/**
 * Implements the CropDoc motion specification using RenderThread/GPU composite properties
 * (scaleX, scaleY, translationY, rotation, alpha), custom cubic-bezier PathInterpolator curves,
 * interruptible ViewPropertyAnimator calls, and system reduced-motion compliance.
 */
public final class UiMotionHelper {

    public static final float PRESS_SCALE = 0.97f;
    public static final float ENTER_INITIAL_SCALE = 0.95f;
    public static final long PRESS_DOWN_DURATION_MS = 120L;
    public static final long PRESS_UP_DURATION_MS = 150L;
    public static final long REVEAL_DURATION_MS = 220L;
    public static final long ON_SCREEN_DURATION_MS = 180L;
    public static final long STAGGER_DURATION_MS = 180L;
    public static final long STAGGER_STEP_MS = 40L;
    public static final int MAX_STAGGER_ITEMS = 4;
    private static final float STAGGER_OFFSET_DP = 12f;

    private static final Interpolator EASE_OUT =
            PathInterpolatorCompat.create(0.23f, 1.0f, 0.32f, 1.0f);
    private static final Interpolator EASE_IN_OUT =
            PathInterpolatorCompat.create(0.77f, 0.0f, 0.175f, 1.0f);

    private UiMotionHelper() {
        // Utility class
    }

    public static boolean isMotionEnabled() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return ValueAnimator.areAnimatorsEnabled();
        }
        return true;
    }

    @NonNull
    public static Interpolator getEaseOut() {
        return EASE_OUT;
    }

    @NonNull
    public static Interpolator getEaseInOut() {
        return EASE_IN_OUT;
    }

    @SuppressLint("ClickableViewAccessibility")
    public static void attachPressFeedback(@NonNull View... views) {
        for (View view : views) {
            view.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN && isMotionEnabled() && v.isEnabled()) {
                    v.animate()
                            .scaleX(PRESS_SCALE)
                            .scaleY(PRESS_SCALE)
                            .setDuration(PRESS_DOWN_DURATION_MS)
                            .setInterpolator(EASE_OUT)
                            .start();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    v.animate()
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(PRESS_UP_DURATION_MS)
                            .setInterpolator(EASE_OUT)
                            .start();
                }
                return false;
            });
        }
    }

    public static void animateReveal(@NonNull View view) {
        view.setVisibility(View.VISIBLE);
        if (!isMotionEnabled()) {
            view.setAlpha(1.0f);
            view.setScaleX(1.0f);
            view.setScaleY(1.0f);
            return;
        }
        view.setAlpha(0f);
        view.setScaleX(ENTER_INITIAL_SCALE);
        view.setScaleY(ENTER_INITIAL_SCALE);
        view.animate()
                .alpha(1.0f)
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(REVEAL_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start();
    }

    public static void animateExpandCollapse(@NonNull View contentView, boolean expand) {
        if (!expand) {
            contentView.setVisibility(View.GONE);
            return;
        }
        contentView.setVisibility(View.VISIBLE);
        if (!isMotionEnabled()) {
            contentView.setAlpha(1.0f);
            contentView.setTranslationY(0f);
            return;
        }
        contentView.setAlpha(0f);
        contentView.setTranslationY(-8f);
        contentView.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setDuration(ON_SCREEN_DURATION_MS)
                .setInterpolator(EASE_IN_OUT)
                .start();
    }

    public static void animateListStagger(@NonNull View itemView, int position) {
        if (!isMotionEnabled() || position >= MAX_STAGGER_ITEMS) {
            itemView.setAlpha(1.0f);
            itemView.setTranslationY(0f);
            return;
        }
        float density = itemView.getResources().getDisplayMetrics().density;
        itemView.setAlpha(0f);
        itemView.setTranslationY(STAGGER_OFFSET_DP * density);
        itemView.animate()
                .alpha(1.0f)
                .translationY(0f)
                .setStartDelay(position * STAGGER_STEP_MS)
                .setDuration(STAGGER_DURATION_MS)
                .setInterpolator(EASE_OUT)
                .start();
    }
}
