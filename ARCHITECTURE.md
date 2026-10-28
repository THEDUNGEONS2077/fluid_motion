# Architecture

## Layers
```
app/src/main/java/com/thedungeons2077/fluidmotion/
  lib/        pure Kotlin: palettes, power rules, the eight wallpapers' simulations, framing and per-frame packing   (unit tested)
  gl/         EGL context (low GPU priority), shader programs, instance buffers
  scene/      the eight renderers (one Scene each) and the rain's glyph atlas
  render/     RenderLoop (thread, EGL surface, frame pacing), PowerWatch (battery saver, heat)
  settings/   SharedPreferences ("fluid_motion")
  wallpaper/  FluidWallpaperService + one subclass per wallpaper (eight picker entries)
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
   - **NEURAL NET.** One pass and four draw calls: the background, links (a glow pass, then the thin pass), then nodes and signals.
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
   - **HORIZON** (0.8.0). One pass, four draw calls at most, with the web's own shaders (`hz_*`).
     - **The sky** over the top half, worked out per pixel each frame. The web draws it once and keeps it, but a phone's screen doesn't keep its last picture, and reading a kept copy back every frame is memory traffic: more battery than the sky's few sums.
     - **The land** (`lib/horizon/Land.kt`) is made once per screen shape and zoom: jittered points, cut into triangles, sent once. Each strip is placed by the vertex shader at its distance from the camera, so a frame sends one number (`uCamZ`).
     - **No depth buffer.** The mesh lists the far wall, then the strips far end first. Two draws (`HorizonDraw`) cover them back to front from wherever the camera is, with back faces culled, so nearer hills cover farther ones. That spares a screen-sized depth buffer written and cleared every frame.
     - **Triangle budget.** The cell follows the screen's shape (`HorizonView`), and zoomed out it grows to stay within 24 000 quads: about 4 700 at 100% on the S25 Ultra (17 000 in 0.8.x, before its cells grew for fewer hills).
   - **SUNSET** (0.8.0). One pass, two draw calls, no geometry, no textures, with the web's own shaders (`ss_*`). The sky above the horizon and the ground below it are each one full-screen triangle, scissored. The ground's only per-frame input is how far the grid has come (`uTravel`).
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
- **Draw only what changes.** MEGACITY's city changes only when a window switches, so it's drawn once a second into a cache and copied each frame (0.7.0). A cache pays only when what it saves is dear: HORIZON's and SUNSET's skies are a few sums a pixel, cheaper to work out again than to read back (0.8.0).
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
  - Below the screen's resolution, NEURAL NET, VELOCITY, DATA FLOW, MEGACITY, HORIZON and SUNSET draw into a smaller frame, which `gl/ScaledTarget.kt` scales up in one pass. The rain scales its own trail layer and atlas.
  - At full resolution nothing extra is drawn.
  - **PRISM at LOW and MEDIUM** (0.11.0) also draws with lighter shaders of the phone's own (`prism_lite.vert/.frag`; HIGH and ULTRA keep the web's `pr_*`, byte for byte, and the web's PRISM is never changed: the user, 2026-10-28). Its light pools were most of its cost (with none, a frame took a third of the time), so the lite pair sums them in the vertex shader at the corners of a 6 × 6 grid on each face (`gridCubeVertices`, `PrismParams.LITE_GRID`) and blends between them, instead of at every pixel. In the software check the frame came out within a step of colour on all but 0.4% of pixels (at most 18/255, along a few edges) for 2.2× less work. `PrismLiteTest` keeps `prism_lite.frag` equal to `pr_box.frag` but for that sum. (A per-block mask of the pools that reach it was tried first: invisible, but only ~5% cheaper, because the blocks the pools miss are mostly out of view.)
- **Applying a change.** A quality or glow change is applied at the scene's next frame, with the context current: the rain rebuilds and pre-warms again.
- **AUTO CALIBRATE** (`ui/HomeState.kt`, rules in `Calibration`):
  - **What it measures.** Each wallpaper is drawn in the preview at each quality that draws differently on this screen (`SceneKind.usesLinkGlow` marks the wallpapers whose LOW draws less, not just smaller), at BALANCED's rate. Its frames are timed by FrameMeter. Where the GPU can't be timed it uses `glFinish` wall time, an upper bound, only while calibrating.
  - **What it picks.** Each wallpaper gets the highest quality within 3 ms GPU and 3 ms CPU, and its own rate (0.11.0): BALANCED, or ECO if even its chosen quality is over budget. Before 0.11.0 one wallpaper over budget put them all at ECO (the S25 Ultra's PRISM did, 0.10.0).
  - **Where it's kept.** The result is stored with the system build and screen (`Settings.calibrated`: qualities and `profiles` per wallpaper; a calibration from before 0.11.0 has one profile for all), and every sample (`Settings.calibrationSamples`). The wallpapers read the quality and profile in force through `Settings.quality(kind)` and `effectivePower(kind)`.
  - **The summary** (0.11.0, the user: a summary and recommended manual settings after calibrating; `lib/CalibrationSummary.kt`, `CalibrationSummaryTest`). From the samples: AUTO's pick per wallpaper and its cost, then three ways to set MANUAL (one rate and quality for all), each with the battery an hour on screen (CostModel), the GPU's busiest share for the heaviest wallpaper, and which wallpapers go over budget, and a USE button:
    - **LONGEST BATTERY:** ECO, LOW.
    - **RECOMMENDED:** BALANCED, at the sharpest quality every wallpaper draws within budget, leaving out those over it even at LOW (named).
    - **SMOOTHEST:** SMOOTH, at the sharpest quality every wallpaper draws in three quarters of a 60 fps frame (12.5 ms; the S25 Ultra's PRISM at ULTRA, 11.8 ms, held 60 fps with no late frames).
    The settings' CALIBRATION section shows it, and opens by itself when a calibration finishes.

## INSIGHT
- **The log.** `lib/Usage.kt` keeps time on screen per wallpaper and rate (`matrix-rain@30`; rate 0 is still), frames, and CPU and GPU time, hour by hour, for a week.
  - `render/UsageRecorder.kt` keeps it for the wallpaper engines only (not the previews).
  - Spans open and close on visibility, rate, easing and kind changes. Frames come from FrameMeter's running totals.
  - It writes to `render/UsageStore.kt` (SharedPreferences `fluid_motion_usage`, one locked read, add and write) when the wallpaper goes off screen, and every 10 min between drawn frames. No timers, nothing per frame.
- **COPY BATTERY DATA.** `lib/BatteryExport.kt` (pure, `BatteryExportTest`) formats all of it as one text with `--- SECTION ---` heads: the phone and battery, each wallpaper's settings, the last AUTO CALIBRATE, each wallpaper's latest MEASURE IMPACT (`Settings.impactReport(kind)`, from 0.7.1), the cost model, then INSIGHT's days and its log hour by hour in local time. `HomeState.copyBatteryData` gathers it and puts it on the clipboard: nothing is sent anywhere.
- **The estimate.** `CostModel` turns time at each rate into mAh. A wallpaper's cost is close to proportional to its rate (SUNSET: 1.46, 1.47, 1.45 mA per fps at 24, 30, 60), so from 0.11.0 it takes the **median** of the wallpaper's measured mA per fps (latest per rate, `Settings.impactModel`) times the rate: one bad reading among three can't move it. Then the same rate on another wallpaper, then the measured mA per fps, then a typical 1.2 mA per fps.
- **MEASURE IMPACT's runs** (0.11.0): STILL is measured first and again last, and the base is their mean (the phone's own draw wandered 99–130 mA between runs). A run where a slower rate reads more than 15 mA dearer than a faster one is flagged NOISY in its report (`Impact.inconsistent`).

## Lifecycle
- **Engines.** The system makes one engine per running copy: home screen, lock screen, and the picker's preview. Each has its own `RenderLoop` (thread and EGL context); the EGL display is shared and never terminated.
- **The app's preview.** It is a SurfaceView with its own loop. It runs between `onStart` and `onStop`, and is released in `onDestroy`.
- **Settings.** The wallpapers run in the app's process, so a change in the app reaches a running wallpaper at once through a SharedPreferences listener. The engine holds that listener, because SharedPreferences keeps listeners only weakly.
- **Failures.** A scene that can't be built on a GPU (a driver refusing a shader) is drawn black and reported in STATUS; the wallpaper doesn't crash.

## Measurements (the user's S25 Ultra, and from 0.10.0 a Galaxy A36)
Each release that could change the cost is measured on the phone by the user (AUTO CALIBRATE, MEASURE IMPACT, COPY BATTERY DATA) and recorded here, so tuning starts from history. MEASURE IMPACT reports the extra current over the wallpaper still (STILL), with the screen and everything else the same in every step.

- **0.2.0: where the cost is.** A frame costs about 1 ms of CPU and 1–1.7 ms of GPU at every rate. The extra current is BALANCED +8 to +47 mA (0.2–0.9% an hour) and SMOOTH +54 to +87 mA (1.1–1.7% an hour), the rain costing most. ECO at 20 fps cost as much as BALANCED, because the display has no 20 Hz mode and stayed at 60. **The refresh rate is the main cost**, which is why ECO became the display's own 24 (0.2.1).
- **0.3.0: AUTO CALIBRATE.** ULTRA for all three wallpapers of the time. GPU ms at LOW / MEDIUM / HIGH (= ULTRA at FHD+):
  - rain: 0.86 / 1.10 / 1.34;
  - neural: 0.75 / 1.26 / 1.33;
  - velocity: 0.82 / 1.36 / 1.55.

  MEDIUM saves little, because the scale-up pass costs most of what drawing smaller saves.
- **0.5.0.**
  - **GPU ms at ULTRA:** rain 1.35, neural 0.90, velocity 2.24, DATA FLOW 0.93.
  - **DATA FLOW's MEASURE IMPACT:** BALANCED +53 mA, SMOOTH +160 mA (battery saver on). The user said not to worry about ECO.
- **0.6.1** (battery saver on, STILL 123 mA).
  - **GPU ms at ULTRA:** rain 1.34, neural 1.11, velocity 2.02, flow 0.89, MEGACITY 5.09. AUTO CALIBRATE picked LOW for MEGACITY (1.84 ms).
  - **MEASURE IMPACT, ECO / BALANCED / SMOOTH:**

    | Wallpaper (palette, glow) | ECO | BALANCED | SMOOTH |
    |---|---|---|---|
    | MEGACITY (AMBER, MAX) | +36 | +46 | +122 mA |
    | VELOCITY (BLOOD) | +25 | +16 | +56 mA |
    | RAIN (PHOSPHOR, LOW) | +36 | +40 | +109 mA |
- **0.7.0: MEGACITY's cache.**
  - **GPU:** 5.09 → **0.93 ms** at ULTRA, and AUTO CALIBRATE now picks ULTRA.
  - **MEASURE IMPACT:** +32 / +30 / +71 mA (−35% at BALANCED, −42% at SMOOTH).
  - **Heaviest now:** VELOCITY (2.08 ms) and the rain (1.49 ms). VELOCITY is light on the battery all the same (+16 mA at BALANCED), so it was left as it is.
- **0.10.0: PRISM** (new) waits for its first AUTO CALIBRATE and MEASURE IMPACT. Each frame: one full-screen pass, then about 190 blocks (36 vertices each) in one instanced draw, blended back to front (up to three layers of glass deep), with 10 light pools per pixel; the CPU sorts and packs them (an insertion sort into an order kept between frames).
- **0.8.1: HORIZON and SUNSET, first measured (F6, 2026-10-28).** Both at ULTRA, battery saver on, 60 fps asked and drawn, no late frames:

    | Wallpaper (palette, glow) | CPU | GPU | ECO 24 | BALANCED 30 | SMOOTH 60 |
    |---|---|---|---|---|---|
    | HORIZON (OUTRUN, LOW) | 0.84 ms | 2.08 ms | +82 | +48 | +100 mA |
    | SUNSET (BLOOD, MAX) | 0.76 ms | 1.28 ms | +35 | +44 | +87 mA |

  - **Within the budget:** both under 3 ms of GPU and of CPU. HORIZON ties VELOCITY as the heaviest on the GPU; its land was then about 17 000 quads (104 000 vertices). SUNSET is two full-screen passes with no geometry.
  - **HORIZON's ECO** (+82 mA) measured above BALANCED: most likely noise in a single run (the bases differ, 99 against 113 mA), to be seen again.
  - **AUTO CALIBRATE** on the phone was still 0.7.0's, so it hadn't measured these two yet.
- **0.9.0: HORIZON's bigger cells** (for fewer hills, the user's request): the land is about 4 700 quads (28 000 vertices), a quarter of 0.8.1's, so its GPU time should drop. To be measured.
- **0.10.0, F6 (2026-10-28): the S25 Ultra** (Adreno 830, FHD+ 2.81 px/dp, battery saver on).
  - **AUTO CALIBRATE, GPU ms at LOW / MEDIUM / HIGH (= ULTRA):** rain 0.86 / 1.07 / 1.32; neural 0.65 / 1.10 / 1.10; velocity 0.72 / 1.15 / 0.73; flow 0.46 / 0.94 / 0.92; MEGACITY 0.60 / 0.92 / 0.85; HORIZON 0.99 / 1.76 / 2.15 (its bigger cells didn't make it cheaper: its cost is pixels, not vertices); SUNSET 0.73 / 1.18 / 1.28; **PRISM 3.72 / 7.85 / 11.76**. CPU under 1.3 ms for all.
  - **PRISM is the one over budget**, by 4× at ULTRA, and even at LOW; pixel-bound (cost follows resolution). AUTO picked LOW for it, and its old rule then put every wallpaper at ECO (fixed in 0.11.0). The user's call: PRISM stays as it is at ULTRA on their phone (it runs smoothly at 60 fps, 10 ms GPU, 0% late) and on the web; slower phones are accommodated through QUALITY (the lite shaders, 0.11.0).
  - **MEASURE IMPACT** (ECO 24 / BALANCED 30 / SMOOTH 60, extra mA): PRISM (NEON, MAX, ULTRA) +53 / +60 / +153 (STILL 130), the dearest of all at SMOOTH, 3.1% an hour; HORIZON (OUTRUN, HIGH) +149 / +39 / +77 (STILL 122). HORIZON's ECO read high a second time, and DATA FLOW's 24 fps figure was +202 against +53 at 30: single-run noise, which 0.11.0's STILL-at-both-ends, NOISY flag and median cost model deal with.
  - **COPY BATTERY DATA, the week:** 15–53 min a day on screen, all at SMOOTH 60 (mostly MATRIX RAIN, then NEURAL NET and DATA FLOW): 32–96 mAh a day, 0.7–1.9% of the battery. Frames 0.4–1.0 ms GPU and 0.4–0.8 ms CPU on average.
- **0.7.1 data from a Galaxy A36** (SM-A366B, Adreno 710, 1080 × 2340 at 3.0 px/dp, 120/60 Hz; the user's brother; not yet updated, never calibrated): QUALITY HIGH, MATRIX RAIN at BALANCED 30 (NEURAL NET earlier at 0.7.1's ECO, 20 fps). Frames **2.2–3.2 ms CPU and 1.7–2.9 ms GPU for the rain**, NEURAL NET (with 0.7.1's bokeh) 3.0 ms CPU, 4.1 ms GPU: about 4–5× the S25 Ultra's, and near or over the 3 ms budget. 11–21 min a day on screen. At that speed PRISM's ULTRA would be ~50 ms a frame; the lite shaders at LOW are what such a phone needs. To measure on 0.11.0: AUTO CALIBRATE, MEASURE IMPACT on the rain and PRISM, COPY DIAGNOSTICS.

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
- **HORIZON and SUNSET** (`HorizonTest`, `SunsetTest`): the land the same for a seed and within bounds; every triangle facing the camera; the draw order back to front at any camera distance; the triangle budget at every zoom; on 16:9 the web's own framing; on an upright phone the cells, the sun and the grid's shape and pace as DESIGN.md says.
- **Zoomed frames.** FrameDumpTest also dumps each scene at 150% (and DATA FLOW at 50%; MEGACITY, HORIZON and SUNSET also drawn at LOW), which shader-check draws as `*-zoom.png`.
- **ShaderUniformsTest.** Every uniform a renderer sets is declared by its shaders, and every declared uniform is set.
- **FrameDumpTest.** It writes what each scene sends the GPU on an S25 Ultra (1080 × 2340 at 2.8125 px per dp) to `app/build/shader-check/`.
- **tools/shader-check.**
  - **What it does.** Headless Chromium (ANGLE validates GLSL ES strictly) compiles every shader, draws the dumped frames call for call as the renderers do, and writes `out/*.png`.
  - **How it was checked.** The pictures were compared with the web's wallpapers at a phone viewport: the rain's brightness by eighths of the screen, the look of the others.
- **UI screenshots.**
  - **Setup.** Paparazzi 2 needs Java 21, Kotlin 2.3 and Android tools 32, so it runs in a scratch copy of the project, not here. The steps: add the plugin, force `sdk-common`/`common`/`sdklib` 31.12.0 on the buildscript classpath, use Kotlin 2.3.0, give the tests a JDK 21 launcher, then run `recordPaparazziDebug`.
  - **What it renders.** The home screen at 384 × 832 dp. The build runs on JDK 21 too (`JAVA_HOME`), because the plugin needs it.
