# fluid_motion: notes for Claude Code

Read [ARCHITECTURE.md](ARCHITECTURE.md) (layers, the render loop, the battery rules) and [DESIGN.md](DESIGN.md) (each wallpaper's look, the app's look) first.

## What this is
An Android app (`com.thedungeons2077.fluidmotion`) with three **live wallpapers** ported from cyberdeck_terminal (`~/projects/cyberdeck_terminal`, its `src/wallpaper/`): MATRIX RAIN, NEURAL NET and VELOCITY. The app is their preview and settings, in cyberdeck_terminal's design. It's optimized first for the user's Samsung S25 Ultra, and works on any Android 8.1+ phone with OpenGL ES 3.

**The key objective (user, 2026-10-23): no hit to battery life or phone performance.** Every change is judged by that first. See ARCHITECTURE.md § Battery.

## Rules
- **Comments are required.** Every file gets a header comment (its purpose and layer). Every public declaration gets a doc comment. Non-obvious logic and each shell-script step get a short *why* comment.
- **Layers** (ARCHITECTURE.md):
  - `lib` is pure Kotlin with no Android or GL, and is unit tested.
  - `gl` and `scene` draw. `render` drives the drawing. `wallpaper` and `ui` are the Android entry points.
  - Per-frame data packing lives in `lib` (`RainRecords`, `NeuralFrame`, `VelocityFrame`), so the JVM frame dump and the renderers share it.
- **Nothing allocates per frame** in `render` or `scene`, and nothing runs while a wallpaper is off screen. No loops in the UI either: an idle app screen draws nothing.
- **Shaders** are GLSL ES 3.00 files in `app/src/main/assets/shaders`, with `#include name` for `name.glsl`.
  - **Precision.** A uniform used in both stages must have the same precision in both. Positions in fragment shaders are `highp`, because mediump is 16-bit on phones.
  - **Required check.** After any shader or renderer change, run the shader check (Commands below).
  - **Keep the harness in step.** `tools/shader-check/render.js` mirrors each renderer call for call, so a renderer change belongs there too.
- **Colours come from the palette** (`lib/Palettes.kt`, the same six as the web). A new palette must pass the contrast test.
- **No network code, no analytics, no permissions.** The release script checks the permission list.
- **Release keys never go in git.** `keys/` and `keystore.properties` are ignored, and the user keeps the backup.
- **Publishing is public.** Ask the user before creating the GitHub repo, pushing, or publishing a release. Test builds go out as GitHub (pre)releases; the user installs from there, not via adb.
- **No emulator here** (no KVM). Check with:
  - the unit tests;
  - the shader check;
  - for the UI, a Paparazzi screenshot run in a scratch copy (it needs JDK 21 plus newer Kotlin and tools; see ARCHITECTURE.md § Verifying).

## Commands
```bash
./gradlew :app:testDebugUnitTest       # unit tests + the frame dump (app/build/shader-check/)
(cd tools/shader-check && npm ci && node run.mjs)   # shaders compiled, linked and drawn in WebGL2 → out/*.png
./gradlew :app:assembleDebug           # debug APK (com.thedungeons2077.fluidmotion.dev, installs beside the release)
scripts/release-android.sh --verify-only            # everything a release checks, nothing published
scripts/release-android.sh --prerelease --publish   # a test build on GitHub Releases (ask first)
```
The system JDK 17 builds everything. The Android SDK is in `~/Android/Sdk`.

## Releasing
1. Bump `versionCode` (+1) and `versionName` in `app/build.gradle.kts`.
2. Add a `## x.y.z (build N)` section at the top of `CHANGELOG.md`.
3. Commit and push.
4. Run `scripts/release-android.sh --prerelease --publish`, with the user's OK.
