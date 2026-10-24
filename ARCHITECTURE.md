# Architecture

## Layers
```
app/src/main/java/com/thedungeons2077/fluidmotion/
  lib/        pure Kotlin: palettes, power rules, the five simulations, per-frame packing   (unit tested)
  gl/         EGL context (low GPU priority), shader programs, instance buffers
  scene/      the five renderers (one Scene each) and the rain's glyph atlas
  render/     RenderLoop (thread, EGL surface, frame pacing), PowerWatch (battery saver, heat)
  settings/   SharedPreferences ("fluid_motion")
  wallpaper/  FluidWallpaperService + one subclass per wallpaper (five picker entries)
  ui/         MainActivity, the Compose screen in cyberdeck_terminal's style
app/src/main/assets/shaders/   GLSL ES 3.00 (shared with tools/shader-check)
tools/shader-check/            the shaders run in WebGL2 with dumped frames
```
Imports go downward: `ui`/`wallpaper` → `render` → `scene` → `gl` → `lib`. `lib` imports nothing from Android.

## A frame
1. **Pacing.** `RenderLoop` sleeps until a frame is due, then `Choreographer.postFrameCallbackDelayed` lands it on a vsync. That is one wake-up per frame, not one per vsync.
2. **Drawing.** `Scene.render(dt)` advances the simulation by `dt`, capped at 0.1 s so the motion never jumps after a stall, and draws.
   - **MATRIX RAIN.**
     - **Records.** The simulation (`RainSim`, a port of the web engine) tells a `RainRecorder` what changed: glyphs erased by shape, glyphs put, and stripe fades.
     - **Fades.** A fade is only its stripe set's phase. `rain_fade.vert` builds the stripes from `gl_InstanceID`.
     - **Drawing.** Records and fades go into the persistent *trail layer* (an RGBA8 texture), in the simulation's order, with one blend: premultiplied over, where a fade or erase is black with alpha, which is the web's destination-out.
     - **Composite.** A full-screen pass puts the trail layer over the haze and under the floor.
     - **Atlas.** The glyphs come from an atlas drawn once by Android Canvas: R is the core, G the head glow, B the body glow, A the erase shape. Colours are applied in the shader, so a palette change needs no new atlas.
   - **NEURAL NET.** One pass and five draw calls: the background, bokeh, links (a glow pass, then the thin pass), then nodes and signals.
     - **Uniforms.** Positions go up as small uniform arrays; the links are a static buffer sent once per screen.
     - **Colour.** Colour along the diagonal is worked out in the vertex shaders.
   - **VELOCITY.** One pass.
     - **Light.** Panels (each its own slanted quad), lines, streaks and the flare are added up on a clear target.
     - **Background.** The background then goes in *under* the light by destination alpha (`ONE_MINUS_DST_ALPHA, ONE`), exactly the web's additive canvas over its CSS background.
   - **DATA FLOW.** One pass and nine draw calls.
     - **The board.** `FlowBoard` lays it out once per screen, a port of the web's board.ts rule for rule. `FlowSim` runs the packets, flashes and chip glows in fixed pools.
     - **Background.** The CSS glows and the dot grid are worked out per pixel (`flow_bg.frag`): no texture.
     - **Drawn over it.** The traces (and pin stubs), the vias and pads, and the chips. These are instances sent once per board, palette, glow and quality (`gl/Gl.kt` StaticInstances), redrawn each frame. There is no board layer to keep and read back.
     - **Added.** The light: chips taking data in, afterglow, packets, heads, flashes (`FlowFrame.light`, a few KB a frame). A packet is one instance per stretch of trace it covers, its tail's fade worked out in the fragment shader.
   - **MEGACITY.** One pass and four draw calls, with the web's own shaders (`mc_*`).
     - **The city.** `CityLayout` lays it out once per screen (a port of city.ts, rule for rule): per layer of towers, a skyline packed into a small RGBA32F texture. `mc_city.frag` works out every pixel once: the nearest tower it's inside, then its face, edges and windows from the tower's seed, the windows going on and off by the clock. Nothing about the city is sent per frame.
     - **Its cache** (0.7.0). The city's clock is used only to switch windows, so the city is drawn into a target-sized RGBA8 texture once a second (clock in whole seconds) and copied each frame (`upscale.frag` at one to one). A new city, size, palette, glow or quality draws it again at once. That took most of the frame's cost (5.09 ms at ULTRA on the S25 Ultra) off all but one frame a second. shader-check draws it both ways and requires them within 1/255 (they're identical).
     - **On top.** Signs, then haze patches, beacons and drones (`CityFrame`, a few KB a frame), then the railing and the ledge: triangles sent once per city, palette, quality and zoom.
3. **Swap.** `eglSwapBuffers`.
4. **Measuring.** `render/FrameMeter.kt` measures each frame without allocating or waiting:
   - CPU as thread time;
   - GPU through `EXT_disjoint_timer_query`, a ring of 4 queries read a few frames late;
   - late frames.
   About once a second the loop reports it (STATUS, MEASURE IMPACT, COPY DIAGNOSTICS).

**MEASURE IMPACT** (`lib/Impact.kt`, run by `ui/HomeState.kt`) shows the wallpaper still, then at each profile's rate. It samples `BatteryManager` CURRENT_NOW once a second, unplugged, after a 10 s settle. Each rate's extra current over STILL is the wallpaper's own cost: the screen and everything else are the same in every step.

Simulations keep their own phases, advanced by `dt` and wrapped, so no clock time ever goes into a sine. A wallpaper that runs for months stays as smooth as on day one.

## Battery
The objective is no hit to battery or performance. How it's met:
- **Hidden means nothing runs.** `Engine.onVisibilityChanged(false)` stops the loop: no frames, no timers, a sleeping thread. Most of the day a live wallpaper is behind apps or the screen is off.
- **Capped rates.** ECO, BALANCED 30 (the default) and SMOOTH 60 fps (`lib/Power.kt`). ECO takes the display's own slow rate from 20 up to 30 Hz (24 on the S25 Ultra), else 20.
  - **No judder.** Each rate is one the display runs at, or divides 60 and 120.
  - **Refresh-rate hint.** The rate is passed to `Surface.setFrameRate` (Android 11+), so the S25 Ultra's LTPO panel can lower its refresh too.
- **Battery saver gives a still frame** (default on). **Heat:** a moderate thermal status halves the rate, and severe or worse gives a still frame (default on). PowerWatch listens only while something is drawing.
- **Easing ahead of heat** (Android 11+). The loop reads the thermal headroom every 10 s, between frames it draws anyway, so the reading never wakes the phone. Near the limit, SMOOTH eases to 30 fps, with hysteresis (`lib/Power.kt` Headroom).
- **Low priority.** The GL context asks for LOW GPU priority (`EGL_IMG_context_priority`; Adreno honours it). The render thread runs at background priority, which puts it on the efficiency cores behind the app in front.
- **Little per-frame work.** No allocation, a handful of draw calls, kilobytes of uploads, no depth or stencil buffers. A `glClear` before full-screen passes spares tiled GPUs a reload of the last frame.
  - **No garbage.** `FrameAllocationTest` weighs a thousand frames of each wallpaper's motion and packing on the JVM, with escape analysis off so nothing is quietly optimized away: 0 bytes. Random ranges are `Span`s (two plain floats), because a Kotlin float range (`a..b`) boxes its ends at every read. Per-frame code loops over Lists by index, because a `for` over a List makes an iterator.
- **Rain trail layer.** It is drawn at the screen's resolution, capped at 3 px per dp.
- **Draw only what changes.** MEGACITY's city changes only when a window switches, so it's drawn once a second into a cache and copied each frame (0.7.0).
- **System colours.** `onComputeColors` gives the system the palette's colours, so it never screenshots the wallpaper to work them out.
- **Opaque surfaces.** The wallpaper window and the app's preview are RGBX, so the compositor never blends them.

## ZOOM
- **The rule.** `lib/Zoom.kt` holds the steps, the web's: Chrome's levels from 50% to 200%. Each wallpaper's zoom is kept in Settings as `zoom.<id>` (nothing is stored at 100%). It travels with the look, `RenderLoop.setLook(…, zoom)` → `Scene.setZoom`, and is applied at the scene's next frame with the context current, like QUALITY.
- **How a scene zooms.** It is laid out at `density × zoom`: its simulation gets a screen 1/zoom as big in dp, and it draws at `pxPerDp = density × QUALITY scale × zoom`. The target stays at QUALITY's resolution for the screen, so zoomed in the picture is as sharp; zoomed out there's more to draw.
- **Exceptions:**
  - NEURAL NET's counts are per screen, so they scale by 1/zoom² (`NeuralSim`, clamped to the shaders' arrays).
  - VELOCITY's sizes are shares of a base length, so its base grows by the zoom while the flare keeps its corner inset (`VelocitySim`).
  - The rain's glyph atlas grows as zoom²: its glyphs are capped at `RainParams.MAX_ATLAS_PX_PER_DP` (6), and lower still if the GPU's `GL_MAX_TEXTURE_SIZE` needs it, judged at MAX glow. Past that, its trail layer is drawn smaller and scaled up.
- **The app.** HomeScreen's ZOOM stepper, and a pinch on the preview (`pinchToZoom`, a step at a time through `HomeState.previewZoom`, kept on lift). AUTO CALIBRATE measures each wallpaper at its own zoom.

## QUALITY and AUTO
- **QUALITY.** `lib/Quality.kt` caps the px per dp a wallpaper is drawn with: LOW 1.5, MEDIUM 2.25, HIGH 3, ULTRA uncapped. LOW also drops NEURAL NET's link glow pass, DATA FLOW's soft glow round its traces and halos round its packets (`FlowParams.glow`), and MEGACITY's window halo.
  - Below the screen's resolution, NEURAL NET, VELOCITY, DATA FLOW and MEGACITY draw into a smaller frame, which `gl/ScaledTarget.kt` scales up in one pass. The rain scales its own trail layer and atlas.
  - At full resolution nothing extra is drawn.
- **Applying a change.** A quality or glow change is applied at the scene's next frame, with the context current: the rain rebuilds and pre-warms again.
- **AUTO CALIBRATE** (`ui/HomeState.kt`, rules in `Calibration`):
  - **What it measures.** Each wallpaper is drawn in the preview at each quality that draws differently on this screen (`SceneKind.usesLinkGlow` marks the wallpapers whose LOW draws less, not just smaller), at BALANCED's rate. Its frames are timed by FrameMeter. Where the GPU can't be timed it uses `glFinish` wall time, an upper bound, only while calibrating.
  - **What it picks.** Each wallpaper gets the highest quality within 3 ms GPU and 3 ms CPU, and the rate is BALANCED. If even the chosen quality is over budget, the rate is ECO.
  - **Where it's kept.** The result is stored with the system build and screen (`Settings.calibrated`). The wallpapers read the quality and profile in force through `Settings.quality(kind)` and `effectivePower()`.

## INSIGHT
- **The log.** `lib/Usage.kt` keeps time on screen per wallpaper and rate (`matrix-rain@30`; rate 0 is still), frames, and CPU and GPU time, hour by hour, for a week.
  - `render/UsageRecorder.kt` keeps it for the wallpaper engines only (not the previews).
  - Spans open and close on visibility, rate, easing and kind changes. Frames come from FrameMeter's running totals.
  - It writes to `render/UsageStore.kt` (SharedPreferences `fluid_motion_usage`, one locked read, add and write) when the wallpaper goes off screen, and every 10 min between drawn frames. No timers, nothing per frame.
- **The estimate.** `CostModel` turns time at each rate into mAh. It uses MEASURE IMPACT's measured extra current, latest per wallpaper and rate (`Settings.impactModel`), then the same rate on another wallpaper, then the measured mA per fps, then a typical 1.2 mA per fps.

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
  - VELOCITY's geometry on 16:9 and on a phone;
  - MEGACITY: the city the same for a seed, signs and beacons only where in sight, the skyline packed as the shader reads it, the ledge, the motion's pools; **CityGoldenTest:** the city is the web's (ten cities compared by checksum);
  - DATA FLOW's board: runs at 45°, no trace crossing or touching another, chip traces from their pins, the screen covered; its pools and arrays never overrun;
  - **FlowBoardGoldenTest:** given the same random numbers, the board is the web's, chip for chip and trace for trace. 80 boards were compared in full against board.ts under Node when the port was made; a few are kept as checksums;
  - **FrameAllocationTest:** a frame of each wallpaper allocates nothing;
  - **ZoomTest:** the steps, and what a zoom does to the neural net's counts and VELOCITY's sizes.
- **Zoomed frames.** FrameDumpTest also dumps each scene at 150% (and DATA FLOW at 50%; MEGACITY also drawn at LOW), which shader-check draws as `*-zoom.png`.
- **ShaderUniformsTest.** Every uniform a renderer sets is declared by its shaders, and every declared uniform is set.
- **FrameDumpTest.** It writes what each scene sends the GPU on an S25 Ultra (1080 × 2340 at 2.8125 px per dp) to `app/build/shader-check/`.
- **tools/shader-check.**
  - **What it does.** Headless Chromium (ANGLE validates GLSL ES strictly) compiles every shader, draws the dumped frames call for call as the renderers do, and writes `out/*.png`.
  - **How it was checked.** The pictures were compared with the web's wallpapers at a phone viewport: the rain's brightness by eighths of the screen, the look of the others.
- **UI screenshots.**
  - **Setup.** Paparazzi 2 needs Java 21, Kotlin 2.3 and Android tools 32, so it runs in a scratch copy of the project, not here. The steps: add the plugin, force `sdk-common`/`common`/`sdklib` 31.12.0 on the buildscript classpath, use Kotlin 2.3.0, give the tests a JDK 21 launcher, then run `recordPaparazziDebug`.
  - **What it renders.** The home screen at 384 × 832 dp. The build runs on JDK 21 too (`JAVA_HOME`), because the plugin needs it.
