# How we work

How this project is built: the user's method, their bar for battery and smoothness, and the look they want. Each rule names the decision it came from, so it can be weighed rather than just followed. Read it with [CLAUDE.md](CLAUDE.md) (the hard rules), [ARCHITECTURE.md](ARCHITECTURE.md) and [DESIGN.md](DESIGN.md).

fluid_motion runs **cyberdeck_terminal**'s wallpapers (`~/projects/cyberdeck_terminal`, the user's Chrome dashboard) as Android live wallpapers on the user's **Samsung S25 Ultra** (SM-S938B, Adreno 830, a display that runs at 60/48/30/24/10 Hz). It works on any Android 8.1+ phone with OpenGL ES 3. The dashboard's WORKING.md covers the web; the shared parts are written the same way in both.

## The objective
**"No hit to battery life or phone performance"** (the user, 2026-10-23, when the app was started). Every change is judged by that first, then by how it looks. The web keeps full fidelity on the user's fast PC; the phone is tuned for efficiency, and may draw less where the eye can't tell.

## The method
- **Web first, then the phone.**
  - **On the web:** a wallpaper is matched to the user's reference image and tuned with them in cyberdeck_terminal.
  - **The port:** it comes here only once the user is happy with it there (HORIZON and SUNSET, 0.8.0).
  - **What must match:** the look is the web's (same parameters, colours and compositing), and the layouts with random choices are ported rule for rule (DATA FLOW's board, MEGACITY's city: golden tests).
  - **Shaders:** shared where possible, byte for byte (`mc_*`, `hz_*`, `ss_*`), with the phone's differences in uniforms.
- **Adapted to a phone.** A tall, narrow screen sees much less of a scene made for 16:9. Each wallpaper's phone framing is worked out so it still reads as its reference, and written up in DESIGN.md with the reason:
  - HORIZON's smaller cells;
  - SUNSET's raised camera and its sun sized by the width;
  - VELOCITY's base length;
  - the neural net's counts per screen.
- **No phone here.** There's no emulator and no adb. Before anything ships it is checked with:
  - the unit tests;
  - the shader check (the real GLSL in WebGL2, drawn at the S25's resolution);
  - the release script's `--verify-only`.
- **The user tests on the phone.**
  - **How it reaches them:** test builds go out as GitHub prereleases. The user installs them and looks by eye.
  - **What they measure:** they run the app's AUTO CALIBRATE and MEASURE IMPACT, and paste COPY BATTERY DATA or COPY DIAGNOSTICS back.
  - **What's done with it:** their numbers decide the tuning, and are recorded in CHANGELOG and ARCHITECTURE.md § Measurements.
- **One phase at a time,** agreed first (F1–F5, then the plan of 2026-10-27):
  - F6: measure HORIZON and SUNSET, and tune;
  - F7: an aesthetics audit of all seven against the web, with before/after pictures and their cost, the user picking what to build.
- **Ask before anything public:** every push and every release. Committing locally is fine.

## Battery and smoothness: the rules
- **Nothing runs while unseen.** A wallpaper off screen stops its loop: no frames, no timers, a sleeping thread. The app's own screen draws nothing while idle.
- **The panel's refresh rate is the main cost, not the drawing** (MEASURE IMPACT, 0.2.0). A frame costs about 1 ms of CPU and 1–1.7 ms of GPU. So:
  - **Rates the display runs at:** ECO uses the display's own rate between 20 and 30 Hz (24 on the S25), BALANCED is 30 (the default), SMOOTH is 60. `Surface.setFrameRate` lets the panel drop too.
  - **Still frames:** in battery saver (default on), and when hot.
  - **Easing ahead of heat:** SMOOTH eases to 30 near the thermal limit.
- **A tenth of a frame.** AUTO CALIBRATE gives each wallpaper the highest QUALITY whose frames stay under 3 ms of GPU and 3 ms of CPU at 30 fps, so both stay at their lowest clocks.
- **No allocation per frame.** `FrameAllocationTest` weighs a thousand frames of each wallpaper: 0 bytes. Draw from a `Span`, never a Kotlin float range, and loop over Lists by index.
- **Draw only what changes, but cache only what's costly.**
  - **Cache:** MEGACITY's city is worked out per pixel, so it's drawn once a second and copied (0.7.0: 5.09 → 0.93 ms of GPU a frame).
  - **Don't cache:** HORIZON's and SUNSET's skies are a few sums a pixel. A phone's screen doesn't keep its last picture, and reading a kept copy back every frame costs more memory traffic, so more battery, than working them out again (0.8.0).
- **No extra screen-sized buffers.** HORIZON draws its land far end first with back faces culled instead of using a depth buffer (0.8.0). There are no stencil buffers and no multisampling: WebGL multisampling made HORIZON laggy on the user's PC (cyberdeck 0.17.5). Smooth lines are made in the shader.
- **Budgets on geometry.** HORIZON's land stays within 24 000 quads at any zoom (about 17 000 at 100% on the S25).
- **Low priority.** The GL context asks for low GPU priority, and the render thread runs at background priority on the efficiency cores.
- **Measure before and after, and say what it cost.** Each change that could cost battery names its expected effect, and the user's next MEASURE IMPACT confirms it.

## The look the user wants
These are the same preferences as on the web (cyberdeck_terminal's WORKING.md has their origins):
- **Seamless gradients, no visible cuts, bands or lines.** The rain's haze and floor are smooth full-height curves (0.8.1, from "a seamless and smooth gradient from black bottom to greenest top"). SUNSET's ground melts into the sky at the horizon, sized on the phone so no dark band shows (0.8.0).
- **Glowing, smooth lines.** Edges are measured straight across (the gradient's length), with a soft bell core and a Gaussian glow.
- **Match the reference's composition.** Where the phone must differ, keep what makes the reference recognisable: the sun cut by the horizon, the glow just above the middle, the city seen from the ledge.
- **The app's screen** follows the user's phone preferences (from quest_log):
  - larger, readable text (controls 15 sp, details 13 sp);
  - every control at least 48 dp;
  - controls at the bottom, within thumb reach;
  - everything labelled for TalkBack.

## The quality bar
- **Comments are required:**
  - a file header (its purpose and layer);
  - a doc comment on every public declaration;
  - a *why* comment on non-obvious logic and on each script step.
- **Shaders:**
  - GLSL ES 3.00;
  - positions in fragment shaders `highp`;
  - `precision highp int` for integer hashes;
  - a shared shader changed on both sides;
  - the shader check run after any shader or renderer change, with `render.js` kept call-for-call in step.
- **Palettes:** the web's eight. A new one must pass the contrast test.
- **No network code, no analytics, no permissions.** The release script checks the permission list.
- **Release keys never go in git.** The user keeps the backup.
