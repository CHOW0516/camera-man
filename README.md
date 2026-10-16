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

## Visual tips

Tips are drawn, not written: a round icon badge at the top (red = light problem, dark = framing hint, green = ready), arrows on the face or standing box, corner arrows for closer (out) / back (in), a red limb on the pose outline, a match-score ring, a compass ring for scan, and a green screen frame plus a short buzz when the shot is ready. The **?** button lists what each icon means.

## Filters and icon buttons

- The bottom buttons are icons too: filters, grid, scan, shutter, pose, flip camera (yellow = on)
- Filters: Original, Warm, Cool, Vivid, B&W, Fade, picked from a row of small sample circles; the viewfinder shows the filter live and the saved photo gets the same look
- Coaching still reads the unfiltered camera image
- A filtered photo is re-encoded, so it loses its EXIF data (time, camera info); unfiltered photos keep it

## Zoom, up/down and smarter scan

- Pinch to zoom, or tap the zoom chip for 1x/2x. When the face or body is too small or too big, a magnifier tip appears; tap it to zoom. Walking arrows only show when more than 2x would be needed or the camera is already fully zoomed out.
- Up/down: a tilted-phone tip when the camera points up or down more than 12°, and raise/lower-phone tips (with a green eye line) when the face sits too low or high
- Scan now locks exposure so light can be compared, records up/straight/down views, and scores each view mostly on a clean area where the person stands, plus plain areas, straight lines and symmetry. The ring colors every direction green/yellow/red; at the best direction a tilt arrow shows how far up or down to point.
- Optional Gemini: paste a free key from aistudio.google.com in the **?** screen. Scan then sends small photos of the top 3 views to Gemini (`gemini-flash-latest`) to pick one (sparkle icon). Free-tier data may be used by Google; without a key or network the app uses its own pick.

## Get the app

Every push to `main` builds a debug APK and attaches it to the **Latest build** release. On your phone, open the repo's **Releases** page, download `camera-man.apk`, and install it (allow "install unknown apps" when asked).

## Build locally

Open the folder in Android Studio, or run `./gradlew assembleDebug`.
