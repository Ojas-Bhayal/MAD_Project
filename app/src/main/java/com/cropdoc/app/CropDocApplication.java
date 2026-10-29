package com.cropdoc.app;

import android.app.Application;
import android.os.StrictMode;
import androidx.annotation.NonNull;
import com.cropdoc.app.di.AppContainer;
import com.google.firebase.FirebaseApp;
import com.google.firebase.appcheck.FirebaseAppCheck;
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory;
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory;

/**
 * Application entry point initializing the manual DI container, StrictMode thread guards in debug,
 * and Firebase App Check (Play Integrity in release, Debug provider in debug builds).
 */
public final class CropDocApplication extends Application {

    private AppContainer appContainer;

    @Override
    public void onCreate() {
        super.onCreate();
        appContainer = new AppContainer(this);
        if (BuildConfig.DEBUG) {
            enableDebugStrictMode();
        }
        initFirebaseAndAppCheckSafely();
    }

    @NonNull
    public AppContainer getAppContainer() {
        return appContainer;
    }

    private void enableDebugStrictMode() {
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build());
    }

    private void initFirebaseAndAppCheckSafely() {
        try {
            if (FirebaseApp.initializeApp(this) != null) {
                FirebaseAppCheck appCheck = FirebaseAppCheck.getInstance();
                if (BuildConfig.DEBUG) {
                    appCheck.installAppCheckProviderFactory(
                            DebugAppCheckProviderFactory.getInstance());
                } else {
                    appCheck.installAppCheckProviderFactory(
                            PlayIntegrityAppCheckProviderFactory.getInstance());
                }
            }
        } catch (Throwable ignored) {
            // Offline mode works without google-services.json present
        }
    }
}
