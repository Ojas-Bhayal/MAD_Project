# CropDoc GitHub publishing checklist

This is a separate staging copy; the original project was not modified.

1. Review README.md and confirm setup instructions are current.
2. `app/google-services.json` and `local.properties` were intentionally excluded; configure them locally after cloning.
3. Review model and dataset licenses before publishing assets.
4. Files at or above 95 MiB were skipped; check the desktop log. Required large models need Git LFS or approved external hosting.
5. Verify there are no credentials, signing keys, private scan images, logs, databases, or personal data.
6. Run `git status --short` before committing.
7. Build on Windows using `gradlew.bat testDebugUnitTest assembleDebug` from Command Prompt, or `./gradlew.bat testDebugUnitTest assembleDebug` from PowerShell.

This script does not stage, commit, or push files to GitHub.
