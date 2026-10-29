package com.cropdoc.app.ui;

import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.NavController;
import androidx.navigation.NavOptions;
import androidx.navigation.fragment.NavHostFragment;
import com.cropdoc.app.BuildConfig;
import com.cropdoc.app.R;
import com.cropdoc.app.databinding.ActivityMainBinding;
import com.cropdoc.app.ui.common.WindowInsetsHelper;
import com.cropdoc.app.util.LocaleHelper;

/**
 * Single-Activity host managing Edge-to-Edge WindowInsets, Material 3 bottom navigation
 * (clearing transient Result backstack entries on tab switch), and language switching.
 */
public final class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        WindowInsetsHelper.applyShellInsets(
                binding.rootMainContainer,
                binding.languageBar,
                binding.navHostFragment,
                binding.bottomNavigation);
        setupNavigation();
        setupLanguageToggleGroup();
    }

    private void setupNavigation() {
        NavHostFragment host = (NavHostFragment) getSupportFragmentManager()
                .findFragmentById(R.id.nav_host_fragment);
        if (host == null) {
            return;
        }
        NavController navController = host.getNavController();
        if (BuildConfig.DEBUG) {
            MenuItem researchTab = binding.bottomNavigation.getMenu().add(
                    Menu.NONE, R.id.researchDemoFragment, 3, R.string.nav_research_demo);
            researchTab.setIcon(R.drawable.ic_science_research);
        }
        binding.bottomNavigation.setOnItemSelectedListener(
                item -> navigateTopLevelTab(navController, item.getItemId()));
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            int destId = destination.getId();
            if (destId == R.id.scanFragment || destId == R.id.resultFragment) {
                binding.bottomNavigation.getMenu().findItem(R.id.scanFragment).setChecked(true);
            } else if (destId == R.id.historyFragment) {
                binding.bottomNavigation.getMenu().findItem(R.id.historyFragment).setChecked(true);
            } else if (destId == R.id.authFragment) {
                binding.bottomNavigation.getMenu().findItem(R.id.authFragment).setChecked(true);
            } else if (destId == R.id.researchDemoFragment) {
                MenuItem item = binding.bottomNavigation.getMenu()
                        .findItem(R.id.researchDemoFragment);
                if (item != null) {
                    item.setChecked(true);
                }
            }
        });
    }

    private boolean navigateTopLevelTab(@NonNull NavController navController, int targetDestId) {
        try {
            if (targetDestId == R.id.researchDemoFragment && !BuildConfig.DEBUG) {
                return false;
            }
            if (targetDestId == R.id.scanFragment) {
                navController.popBackStack(R.id.scanFragment, false);
                return true;
            }
            NavOptions navOptions = new NavOptions.Builder()
                    .setLaunchSingleTop(true)
                    .setRestoreState(false)
                    .setPopUpTo(R.id.scanFragment, false, false)
                    .build();
            navController.navigate(targetDestId, null, navOptions);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void setupLanguageToggleGroup() {
        syncCheckedLanguageButton();
        binding.btnLangEn.setOnClickListener(
                v -> LocaleHelper.applyLanguage(LocaleHelper.LANG_ENGLISH));
        binding.btnLangHi.setOnClickListener(
                v -> LocaleHelper.applyLanguage(LocaleHelper.LANG_HINDI));
        binding.btnLangTe.setOnClickListener(
                v -> LocaleHelper.applyLanguage(LocaleHelper.LANG_TELUGU));
    }

    private void syncCheckedLanguageButton() {
        String current = LocaleHelper.getCurrentLanguageCode();
        if (LocaleHelper.LANG_HINDI.equals(current)) {
            binding.toggleLanguageGroup.check(R.id.btn_lang_hi);
        } else if (LocaleHelper.LANG_TELUGU.equals(current)) {
            binding.toggleLanguageGroup.check(R.id.btn_lang_te);
        } else {
            binding.toggleLanguageGroup.check(R.id.btn_lang_en);
        }
    }
}
