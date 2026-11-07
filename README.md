# PureImage Offline 1.1

Complete Kotlin / Compose / MVVM Android project. Android 10 or newer.

## What changed
No server, API key, model download, or INTERNET permission. Select a photo, tap
Clean up offline, compare, and save a new PNG to Pictures/PureImage.
Original photos remain unchanged. The filter is classical mild median denoising,
not diffusion reconstruction, SynthID detection, or verified watermark removal.
It may soften texture; some images show little change. This does not fulfill
reliable SynthID removal and no such removal is claimed.

Processing handles at most 2048 pixels on the longest side, preserving aspect
ratio, and exports sRGB PNG. Inputs: PNG/JPEG/WebP, at most 25 MiB and 40 MP.
Transparent pixels are preserved by the filter. ImageDecoder handles orientation.
Exports do not intentionally copy EXIF metadata. Do not assume that all provenance
or embedded watermarks have been removed.

## Build on GitHub
1. Extract this ZIP on your computer.
2. Upload the CONTENTS of PureImage-offline into the existing repository root,
   replacing matching files. Do not nest it under another project folder.
3. Delete old ImageApi.kt and NetworkModule.kt from app/src/main/java/com/pureimage/app/data.
4. Ensure .github/workflows/android-apk.yml is updated too (hidden folders may be missed).
5. Commit to main, open Actions > Build PureImage APK, then download
   PureImage-offline-<run number>. Extract the ZIP and install app-debug.apk.

The same application ID is retained; versionCode is now 2. A different GitHub
runner debug signing key may require uninstalling the old APK before installing.

## Local build
JDK 17, Android SDK 35. Run:
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
APK: app/build/outputs/apk/debug/app-debug.apk

## Validation
See VALIDATION.md for checks actually performed. Device testing is still needed
for picker, camera orientation, cancellation, and gallery saves.
