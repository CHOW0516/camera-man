# Camera Man

An Android camera app with a free, on-device AI coach that suggests where to stand, how to use the light, and how to pose.

## Phase 1: camera base

- Live camera preview, front/back switch
- Take a photo and save it to `Pictures/CameraMan`
- Rule-of-thirds grid (toggle)
- Level line that turns green when the phone is straight

## Phase 2: live light and framing tips

- Warns when the scene is too dark, backlit, or in harsh light
- Finds the main face (ML Kit, on-device) and coaches framing: step closer, leave headroom, line the eyes up with a thirds point
- One tip at a time in a banner at the top; it waits a few frames before changing so it does not flicker

## Phase 3: spot coach and scan mode

- Side-light tip: when half the face is in shadow, a yellow arrow points toward the light
- Green arrow from the face to the target dot when reframing
- **Scan**: hold the phone upright and turn in a full circle; the app scores each direction for light on the subject, backlight, blown-out highlights and background clutter, then points you to the best direction to shoot

## Phase 4: pose mode

- **Pose** button: shows a dashed box where the person should stand (on a thirds line) and a white outline of a target pose
- Body pose detection (ML Kit, on-device) draws the person's skeleton and a live match score
- Tips in order: stand in the box, feet in the photo, move left/right, come closer/step back, then which arm or leg to adjust
- **Next pose** cycles through 8 starter poses (relaxed stand, hand on hip, walking step, wave, hand in hair, and more); add poses in `PoseLibrary.kt` by listing bone angles

## Get the app

Every push to `main` builds a debug APK and attaches it to the **Latest build** release. On your phone, open the repo's **Releases** page, download `camera-man.apk`, and install it (allow "install unknown apps" when asked).

## Build locally

Open the folder in Android Studio, or run `./gradlew assembleDebug`.
