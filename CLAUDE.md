# fluid_motion: notes for Claude Code

Read [WORKING.md](WORKING.md) (how we work: the user's method, the battery bar, the look they want), [ARCHITECTURE.md](ARCHITECTURE.md) (layers, the render loop, the battery rules, the S25 measurements) and [DESIGN.md](DESIGN.md) (each wallpaper's look, the app's look) first.

## What this is
An Android app (`com.thedungeons2077.fluidmotion`) with eight **live wallpapers** ported from cyberdeck_terminal (`~/projects/cyberdeck_terminal`, its `src/wallpaper/`): MATRIX RAIN, NEURAL NET, VELOCITY, DATA FLOW, MEGACITY, HORIZON, SUNSET and PRISM. The app is their preview and settings, in cyberdeck_terminal's design. It's optimized first for the user's Samsung S25 Ultra, and works on any Android 8.1+ phone with OpenGL ES 3.

**The key objective (user, 2026-10-23): no hit to battery life or phone performance.** Every change is judged by that first. See ARCHITECTURE.md § Battery.

## Rules
- **Comments are required.** Every file gets a header comment (its purpose and layer). Every public declaration gets a doc comment. Non-obvious logic and each shell-script step get a short *why* comment.
- **Layers** (ARCHITECTURE.md):
  - `lib` is pure Kotlin with no Android or GL, and is unit tested.
  - `gl` and `scene` draw. `render` drives the drawing. `wallpaper` and `ui` are the Android entry points.
  - Per-frame data packing and framing live in `lib` (`RainRecords`, `NeuralFrame`, `VelocityFrame`, `FlowFrame`, `CityFrame`, `HorizonView`, `SunsetView`, `PrismFrame`), so the JVM frame dump and the renderers share them.
- **Nothing allocates per frame** in `render` or `scene`, and nothing runs while a wallpaper is off screen. No loops in the UI either: an idle app screen draws nothing.
  - `FrameAllocationTest` weighs each wallpaper's frame; a new wallpaper gets a case there.
  - Draw random ranges from a `Span`, never a Kotlin float range (`a..b` boxes its ends at every read). In per-frame code, loop over Lists by index (a `for` over a List makes an iterator).
- **ZOOM** (lib/Zoom.kt, the web's steps) reaches every scene through `Scene.setZoom`. A scene lays itself out at `density × zoom` and draws at `pxPerDp × zoom`, and its target follows QUALITY at the screen's own density. A new wallpaper must zoom too, and get a zoomed dump in FrameDumpTest.
- **MEGACITY's city is the web's, rule for rule** (`lib/megacity/City.kt` and city.ts, laid out in Double as JavaScript is, in the same order of random draws; `CityGoldenTest`, its header says how to regenerate). **Its shaders are one set** with the web's: `assets/shaders/mc_*` are byte for byte cyberdeck_terminal's `src/wallpaper/megacity/shaders/*`; change both.
- **NEURAL NET's fragment shaders and colour are the web's too** (cyberdeck_terminal 0.22.0): `neural_link.frag`, `neural_spark.frag` and `neural_shade.glsl` are byte for byte `src/wallpaper/neural-net/shaders/link.frag`, `spark.frag` and `shade.glsl`; change both. Its vertex shaders differ (the web sends the links' ends per instance).
- **HORIZON's and SUNSET's shaders are one set with the web's too:** `hz_*` and `ss_*` are byte for byte `src/wallpaper/horizon/shaders/*` and `src/wallpaper/sunset/shaders/*` (prefixed); change both. The exception: the phone draws HORIZON's sky with its own `horizon_sky.frag` (`hz_sky.frag` plus a dither, 0.12.0; `HorizonSkyTest` keeps them equal but for it), because the user doesn't want the dashboard's HORIZON touched. So are PRISM's `pr_*` (`src/wallpaper/prism/shaders/*`; its framing is `lib/prism` `PrismView`), but **the web's PRISM never changes** (the user, 2026-10-28): the phone's own cheaper pair for LOW and MEDIUM is `prism_lite.*` (`PrismLiteTest` keeps it equal to `pr_box.frag` but for where the light pools are summed), and HIGH and ULTRA stay exactly the web's. Everything a phone does differently goes through their uniforms and HORIZON's mesh (`lib/horizon`, `lib/sunset`: `HorizonView`, `SunsetView`).
- **DATA FLOW's board is the web's, rule for rule** (`lib/flow/FlowBoard.kt` and board.ts, in the same order of random draws). Change a board rule on both sides, then regenerate `FlowBoardGoldenTest` (its header says how).
- **Shaders** are GLSL ES 3.00 files in `app/src/main/assets/shaders`, with `#include name` for `name.glsl`.
  - **Precision.** A uniform used in both stages must have the same precision in both. Positions in fragment shaders are `highp`, because mediump is 16-bit on phones. Integer maths in a fragment shader (a hash) needs `precision highp int;` too: ints default to mediump there (SUNSET's stars, 0.8.0).
  - **Required check.** After any shader or renderer change, run the shader check (Commands below).
  - **Keep the harness in step.** `tools/shader-check/render.js` mirrors each renderer call for call, so a renderer change belongs there too.
- **Colours come from the palette** (`lib/Palettes.kt`, the same eight as the web). A new palette must pass the contrast test.
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
