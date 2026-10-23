# fluid_motion

Three live wallpapers for Android, from the [cyberdeck_terminal](https://thedungeons2077.github.io/cyberdeck_terminal/) dashboard:

- **MATRIX RAIN**: columns of glyphs falling in three depths, glowing heads, long fading trails.
- **NEURAL NET**: a drifting plexus of glowing nodes; signals run along its links.
- **VELOCITY**: light caught in motion; slanted panels drift, streaks race past.

The app is their preview and settings, in the dashboard's terminal style. It is built to cost as little battery as possible:

- **Hidden:** it draws nothing at all while the wallpaper is hidden.
- **On screen:** it draws 30 frames a second by default (ECO 20, SMOOTH 60) and tells the display, so a phone like the S25 Ultra can slow its refresh too.
- **Battery saver:** it holds a still frame.
- **Heat:** it slows down when the phone is warm, and holds a still frame when it's hot.
- **Priority:** the app in front always gets the processor and the GPU first.

Android 8.1 or newer, with OpenGL ES 3 (practically every phone since 2015). It is optimized first for the Samsung S25 Ultra.

## Install
1. On the phone, open the latest release on [GitHub Releases](https://github.com/THEDUNGEONS2077/fluid_motion/releases) and download `fluid_motion-vX.Y.Z.apk`.
2. Open it. If Android asks, allow your browser or file manager to install unknown apps. Then tap **Install**.
3. Updates install over the top. Your settings are kept.

## Use
1. Open **fluid_motion**.
2. Pick a wallpaper, its colours and its glow. The screen shows it running, exactly as it will on your home screen.
3. Tap **SET AS WALLPAPER ↗**. Android shows its own preview; tap **Set wallpaper** there. Samsung phones ask whether it's for the home screen, or the home and lock screens.

You can change the colours, glow and power settings any time, even while the wallpaper is set: it changes straight away. Tap the wallpaper in the app to hide the controls and see it whole.

**POWER:**
- **ECO / BALANCED / SMOOTH** set the frames a second while the wallpaper is on screen.
- **STILL IN BATTERY SAVER:** a still frame while battery saver is on.
- **COOL DOWN WHEN HOT:** SMOOTH eases to 30 as the phone nears its heat limit; half the frames when it's warm, a still frame when it's hot.

**STATUS:**
- It shows what each frame costs, measured: CPU and GPU time, and late frames.
- **MEASURE IMPACT** measures the battery cost of each rate on your phone, in about three minutes. Unplug the phone first.
- **COPY DIAGNOSTICS** copies all of that, to paste into a message.

## Build
On Linux with JDK 17 and the Android SDK (`ANDROID_HOME`):
```bash
./gradlew :app:testDebugUnitTest      # tests
./gradlew :app:assembleDebug          # a debug build ("fluid_motion dev": installs beside the release)
scripts/release-android.sh --verify-only   # a signed release, with every check (needs the release key)
```

## The release key
Release builds are signed with `keys/fluid_motion-release.jks`, whose passwords are in `keystore.properties`. Neither is in git. **Back both up somewhere safe.** If they're lost, a new build can't install over the old one: you'd have to uninstall, and lose the settings.

Certificate SHA-256: `8F:7D:DF:CE:20:A8:EF:40:FE:4B:5C:A5:E3:3B:B1:E7:6A:66:59:42:5F:E3:11:CE:F2:64:96:A0:70:DC:02:A3`

## Licences
JetBrains Mono is under the SIL Open Font License 1.1 (`app/src/main/assets/licenses/OFL-JetBrainsMono.txt`).
