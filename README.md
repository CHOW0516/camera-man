# Camera Man

An Android camera app with a free, on-device AI coach that suggests where to stand, how to use the light, and how to pose.

## Phase 1: camera base

- Live camera preview, front/back switch
- Take a photo and save it to `Pictures/CameraMan`
- Rule-of-thirds grid (toggle)
- Level line that turns green when the phone is straight

## Get the app

Every push to `main` builds a debug APK and attaches it to the **Latest build** release. On your phone, open the repo's **Releases** page, download `camera-man.apk`, and install it (allow "install unknown apps" when asked).

## Build locally

Open the folder in Android Studio, or run `./gradlew assembleDebug`.
