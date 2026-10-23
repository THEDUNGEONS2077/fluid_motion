# Architecture

## Layers
```
app/src/main/java/com/thedungeons2077/fluidmotion/
  lib/        pure Kotlin: palettes, power rules, the three simulations, per-frame packing   (unit tested)
  gl/         EGL context (low GPU priority), shader programs, instance buffers
  scene/      the three renderers (one Scene each) and the rain's glyph atlas
  render/     RenderLoop (thread, EGL surface, frame pacing), PowerWatch (battery saver, heat)
  settings/   SharedPreferences ("fluid_motion")
  wallpaper/  FluidWallpaperService + one subclass per wallpaper (three picker entries)
  ui/         MainActivity, the Compose screen in cyberdeck_terminal's style
app/src/main/assets/shaders/   GLSL ES 3.00 (shared with tools/shader-check)
tools/shader-check/            the shaders run in WebGL2 with dumped frames
```
Imports go downward: `ui`/`wallpaper` → `render` → `scene` → `gl` → `lib`. `lib` imports nothing from Android.

## A frame
1. **Pacing.** `RenderLoop` sleeps until a frame is due, then `Choreographer.postFrameCallbackDelayed` lands it on a vsync. That is one wake-up per frame, not one per vsync.
2. **Drawing.** `Scene.render(dt)` advances the simulation by `dt`, capped at 0.1 s so the motion never jumps after a stall, and draws.
   - **MATRIX RAIN.**
     - **Records.** The simulation (`RainSim`, a port of the web engine) tells a `RainRecorder` what changed: stripe fades, glyphs erased by shape, glyphs put.
     - **One draw.** The records go into the persistent *trail layer* (an RGBA8 texture), in order, as one instanced draw with one blend: premultiplied over, where a fade or erase is black with alpha, which is the web's destination-out.
     - **Composite.** A full-screen pass puts the trail layer over the haze and under the floor.
     - **Atlas.** The glyphs come from an atlas drawn once by Android Canvas: R is the core, G the head glow, B the body glow, A the erase shape. Colours are applied in the shader, so a palette change needs no new atlas.
   - **NEURAL NET.** One pass and five draw calls: the background, bokeh, links (a glow pass, then the thin pass), then nodes and signals.
     - **Uniforms.** Positions go up as small uniform arrays; the links are a static buffer sent once per screen.
     - **Colour.** Colour along the diagonal is worked out in the vertex shaders.
   - **VELOCITY.** One pass.
     - **Light.** Panels (each its own slanted quad), lines, streaks and the flare are added up on a clear target.
     - **Background.** The background then goes in *under* the light by destination alpha (`ONE_MINUS_DST_ALPHA, ONE`), exactly the web's additive canvas over its CSS background.
3. **Swap.** `eglSwapBuffers`. About once a second the loop reports its frame cost (the app's STATUS line).

Simulations keep their own phases, advanced by `dt` and wrapped, so no clock time ever goes into a sine. A wallpaper that runs for months stays as smooth as on day one.

## Battery
The objective is no hit to battery or performance. How it's met:
- **Hidden means nothing runs.** `Engine.onVisibilityChanged(false)` stops the loop: no frames, no timers, a sleeping thread. Most of the day a live wallpaper is behind apps or the screen is off.
- **Capped rates.** ECO 20, BALANCED 30 (the default) and SMOOTH 60 fps (`lib/Power.kt`).
  - **No judder.** Each rate divides 60 and 120.
  - **Refresh-rate hint.** The rate is passed to `Surface.setFrameRate` (Android 11+), so the S25 Ultra's LTPO panel can lower its refresh too.
- **Battery saver gives a still frame** (default on). **Heat:** a moderate thermal status halves the rate, and severe or worse gives a still frame (default on). PowerWatch listens only while something is drawing.
- **Low priority.** The GL context asks for LOW GPU priority (`EGL_IMG_context_priority`; Adreno honours it). The render thread runs at background priority, which puts it on the efficiency cores behind the app in front.
- **Little per-frame work.** No allocation, a handful of draw calls, kilobytes of uploads, no depth or stencil buffers. A `glClear` before full-screen passes spares tiled GPUs a reload of the last frame.
- **Rain trail layer.** It is drawn at the screen's resolution, capped at 3 px per dp.
- **System colours.** `onComputeColors` gives the system the palette's colours, so it never screenshots the wallpaper to work them out.
- **Opaque surfaces.** The wallpaper window and the app's preview are RGBX, so the compositor never blends them.

## Lifecycle
- **Engines.** The system makes one engine per running copy: home screen, lock screen, and the picker's preview. Each has its own `RenderLoop` (thread and EGL context); the EGL display is shared and never terminated.
- **The app's preview.** It is a SurfaceView with its own loop. It runs between `onStart` and `onStop`, and is released in `onDestroy`.
- **Settings.** The wallpapers run in the app's process, so a change in the app reaches a running wallpaper at once through a SharedPreferences listener. The engine holds that listener, because SharedPreferences keeps listeners only weakly.
- **Failures.** A scene that can't be built on a GPU (a driver refusing a shader) is drawn black and reported in STATUS; the wallpaper doesn't crash.

## Verifying (no phone here)
- **Unit tests** (`lib`):
  - the palettes' contrast and the power rules;
  - the rain erases only what it drew, opens full and fades evenly;
  - links, colours and motion bounds;
  - VELOCITY's geometry on 16:9 and on a phone.
- **ShaderUniformsTest.** Every uniform a renderer sets is declared by its shaders, and every declared uniform is set.
- **FrameDumpTest.** It writes what each scene sends the GPU on an S25 Ultra (1080 × 2340 at 2.8125 px per dp) to `app/build/shader-check/`.
- **tools/shader-check.**
  - **What it does.** Headless Chromium (ANGLE validates GLSL ES strictly) compiles every shader, draws the dumped frames call for call as the renderers do, and writes `out/*.png`.
  - **How it was checked.** The pictures were compared with the web's wallpapers at a phone viewport: the rain's brightness by eighths of the screen, the look of the other two.
- **UI screenshots.**
  - **Setup.** Paparazzi 2 needs Java 21, Kotlin 2.3 and Android tools 32, so it runs in a scratch copy of the project, not here. The steps: add the plugin, force `sdk-common`/`common`/`sdklib` 31.12.0 on the buildscript classpath, use Kotlin 2.3.0, give the tests a JDK 21 launcher, then run `recordPaparazziDebug`.
  - **What it renders.** The home screen at 384 × 832 dp.
