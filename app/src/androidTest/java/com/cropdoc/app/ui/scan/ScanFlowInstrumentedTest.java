package com.cropdoc.app.ui.scan;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.cropdoc.app.R;
import com.cropdoc.app.ui.MainActivity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Instrumented UI test verifying main navigation, top bar language segmented control,
 * Scan reticle and controls, History empty-state CTA, and Account state-aware layout.
 */
@RunWith(AndroidJUnit4.class)
public final class ScanFlowInstrumentedTest {

    @Rule
    public final ActivityScenarioRule<MainActivity> activityRule =
            new ActivityScenarioRule<>(MainActivity.class);

    @Test
    public void mainShell_displaysTopBarLanguageSegmentedControlAndScanControls() {
        onView(withId(R.id.language_bar)).check(matches(isDisplayed()));
        onView(withId(R.id.toggle_language_group)).check(matches(isDisplayed()));
        onView(withId(R.id.leaf_reticle_box)).check(matches(isDisplayed()));
        onView(withId(R.id.btn_capture_leaf)).check(matches(isDisplayed()));
        onView(withId(R.id.btn_pick_gallery)).check(matches(isDisplayed()));
    }

    @Test
    public void historyAndAccountTabs_displayDesignedStatesAndNavigateBackToScan() {
        onView(withId(R.id.historyFragment)).perform(click());
        onView(withId(R.id.layout_empty_history)).check(matches(isDisplayed()));
        onView(withId(R.id.btn_empty_scan_cta)).perform(click());
        onView(withId(R.id.leaf_reticle_box)).check(matches(isDisplayed()));

        onView(withId(R.id.authFragment)).perform(click());
        onView(withId(R.id.txt_account_status)).check(matches(isDisplayed()));
        onView(withId(R.id.btn_toggle_dpdp_notice)).check(matches(isDisplayed()));
    }
}
