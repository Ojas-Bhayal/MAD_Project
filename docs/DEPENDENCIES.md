# CropDoc Dependency List with Exact Versions and Primary Sources

All dependencies are declared in `gradle/libs.versions.toml` (Single Source of Truth) and verified as of **October 17, 2026**. Zero unused dependencies exist in the project.

| TOML Key | Maven Coordinates | Exact Version | Scope | Primary Verification Source |
| :--- | :--- | :--- | :--- | :--- |
| `android-application` | `com.android.tools.build:gradle` | `9.4.0` | Gradle Plugin | [AGP 9.4.0 Release Notes](https://developer.android.com/build/releases/agp-9-4-0-release-notes) |
| `google-services` | `com.google.gms:google-services` | `4.4.3` | Gradle Plugin | [Firebase Android Setup](https://firebase.google.com/docs/android/setup) |
| `firebase-crashlytics` | `com.google.firebase:firebase-crashlytics-gradle` | `3.0.3` | Gradle Plugin | [Firebase Android Setup](https://firebase.google.com/docs/android/setup) |
| `androidx-room` | `androidx.room:room-gradle-plugin` | `2.8.5` | Gradle Plugin | [Room Release Notes](https://developer.android.com/jetpack/androidx/releases/room) |
| `navigation-safeargs` | `androidx.navigation:navigation-safe-args-gradle-plugin` | `2.10.2` | Gradle Plugin | [Navigation Release Notes](https://developer.android.com/jetpack/androidx/releases/navigation) |
| `androidx-core` | `androidx.core:core` | `1.19.1` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-annotation` | `androidx.annotation:annotation` | `1.11.0` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-appcompat` | `androidx.appcompat:appcompat` | `1.8.0` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-activity` | `androidx.activity:activity` | `1.13.0` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-fragment` | `androidx.fragment:fragment` | `1.9.1` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-constraintlayout` | `androidx.constraintlayout:constraintlayout` | `2.2.2` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-recyclerview` | `androidx.recyclerview:recyclerview` | `1.4.0` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `androidx-exifinterface` | `androidx.exifinterface:exifinterface` | `1.4.2` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `google-material` | `com.google.android.material:material` | `1.13.0` | `implementation` | [Maven Central MDC-Android](https://mvnrepository.com/artifact/com.google.android.material/material/1.13.0) |
| `camerax-core`, `camera2`, `lifecycle`, `view` | `androidx.camera:camera-*` | `1.6.2` | `implementation` | [CameraX Release Notes](https://developer.android.com/jetpack/androidx/releases/camera) |
| `navigation-fragment`, `navigation-ui` | `androidx.navigation:navigation-*` | `2.10.2` | `implementation` | [Navigation Release Notes](https://developer.android.com/jetpack/androidx/releases/navigation) |
| `room-runtime`, `room-guava`, `room-compiler` | `androidx.room:room-*` | `2.8.5` | `implementation` / `annotationProcessor` | [Room Release Notes](https://developer.android.com/jetpack/androidx/releases/room) |
| `work-runtime` | `androidx.work:work-runtime` | `2.12.0` | `implementation` | [WorkManager Release Notes](https://developer.android.com/jetpack/androidx/releases/work) |
| `lifecycle-viewmodel`, `livedata`, `runtime`, `common-java8` | `androidx.lifecycle:lifecycle-*` | `2.11.0` | `implementation` | [Lifecycle Release Notes](https://developer.android.com/jetpack/androidx/releases/lifecycle) |
| `credentials-core`, `credentials-play-services` | `androidx.credentials:credentials*` | `1.6.0` | `implementation` | [Credentials Release Notes](https://developer.android.com/jetpack/androidx/releases/credentials) |
| `googleid` | `com.google.android.libraries.identity.googleid:googleid` | `1.1.1` | `implementation` | [Google Identity Services](https://issuetracker.google.com/issues/397720952) |
| `firebase-bom` | `com.google.firebase:firebase-bom` | `35.0.0` | `platform` | [Firebase Android Release Notes](https://firebase.google.com/support/release-notes/android) |
| `firebase-auth`, `firestore`, `crashlytics`, `config`, `appcheck-*` | `com.google.firebase:firebase-*` | `BoM 35.0.0` | `implementation` / `debugImplementation` | [Firebase Package Index](https://firebase.google.com/docs/reference/android/packages) |
| `litert-core` | `com.google.ai.edge.litert:litert` | `2.3.0` | `implementation` | [LiteRT Oct 2026 Release](https://developers.google.com/edge/litert/releases/litert_oct) |
| `glide-core` | `com.github.bumptech.glide:glide` | `5.0.9` | `implementation` | [Maven Central Glide](https://central.sonatype.com/artifact/com.github.bumptech.glide/glide/versions) |
| `profileinstaller` | `androidx.profileinstaller:profileinstaller` | `1.4.1` | `implementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
| `guava` | `com.google.guava:guava` | `33.3.1-android` | `implementation` | Required for CameraX `ListenableFuture` |
| `leakcanary-android` | `com.squareup.leakcanary:leakcanary-android` | `2.14` | `debugImplementation` | [Maven Central LeakCanary](https://mvnrepository.com/artifact/com.squareup.leakcanary/leakcanary-android/2.14) |
| `checkstyle` | `com.puppycrawl.tools:checkstyle` | `10.26.1` | Static Analysis | [Maven Checkstyle (JDK 17)](https://mvnrepository.com/artifact/com.puppycrawl.tools/checkstyle/10.26.1) |
| `junit`, `mockito-core`, `arch-core-testing`, `espresso-core` | `junit:junit:4.13.2`, `org.mockito:mockito-core:5.14.2`, `androidx.arch.core:core-testing:2.2.0`, `androidx.test.espresso:espresso-core:3.7.0` | See left | `testImplementation` / `androidTestImplementation` | [Jetpack Stable Releases](https://developer.android.com/jetpack/androidx/versions/stable-channel) |
