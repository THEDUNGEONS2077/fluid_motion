# Design

## Origin
The wallpapers are cyberdeck_terminal's (`~/projects/cyberdeck_terminal/src/wallpaper/`). MATRIX RAIN, NEURAL NET and VELOCITY were each matched there to a reference image; DATA FLOW was designed there, at full fidelity, then brought here; MEGACITY was matched there to the user's reference and brought here with the same shaders. Its DESIGN.md has the measurements. Here they're redrawn on the GPU to look the same: same parameters, same colours, same compositing. The app's own screen is the dashboard's design system (theme.css and app.css) in Compose.

## The wallpapers on a phone
The web versions were tuned on a landscape monitor. On a portrait phone, a few things are measured differently, so that each still looks like its reference.

| | On the web | On the phone | Why |
|---|---|---|---|
| MATRIX RAIN | Glyphs at 1–2 px per CSS px | At the screen's density, up to 3 px per dp | Crisp at 2.8 px per dp. The look is otherwise the web's: brightness by eighths of the screen matches within the frame-to-frame noise |
| NEURAL NET | 26 nodes per million px² | 64 nodes and 30 bokeh per screen | At the web's density a portrait phone shows only about 20 nodes |
| VELOCITY sizes | Shares of the width | Shares of a base length, ¾ of the screen's longer side | Shares of a narrow width made thin slivers |
| VELOCITY slant | Lean as a share of the width | Lean as a share of a 16:9 width at the screen's height | The panels keep the reference's angle |
| VELOCITY flare | Placed by width and height | Placed from the bottom-right corner in base lengths | The web's arc ends mid-screen on a portrait screen; here both ends stay off the screen |
| VELOCITY red panels | Start right of the pale panel | On portrait, start spread over the width | Bunched on the right, they all drift off together in the first minute |
| DATA FLOW board | Laid out once per screen size | The same rules: the same random numbers lay out the same board (a CSS px is a dp) | A port rule for rule; FlowBoardGoldenTest keeps it so |
| DATA FLOW packets | 2.2 a second per million px² | 3.2 a second per screen | At the web's density a portrait phone gets one packet every second or so |
| DATA FLOW drawing | The board drawn once into a layer; the light added over it, each tail in six fading slices | The board drawn again each frame from instances, the dot grid worked out per pixel; each tail fades smoothly in the shader (the slices' fade, squared) | No full-screen layer to keep and read back every frame |

| MEGACITY | Drawn per pixel in WebGL 2 | The same shaders, in OpenGL ES 3: the same city, pixel for pixel, from the same layout | One pass per pixel was already the cheapest way to draw tens of thousands of windows |

**MEGACITY on a phone.** The reference is a phone wallpaper, so the phone has its composition: the rooftop ledge across the bottom left (the reference's figure on it was left out at the user's wish, 0.6.1). The near layers of towers are sparse enough that the violet haze shows low down, as in the reference. ZOOM grows the towers and windows; the ledge keeps its place. It starts in NEON.

**DATA FLOW on a phone.** The board's rules are the web's, so on a phone the chips are as big as they are in a browser of that size: the main chip spans about half the width, and its buses alone cover the share of the screen the board aims for (16% of its points). The loose traces that fill a monitor's board between the buses (about half of its traces, many ending in pads) are rare on a phone.

**Glow levels** are the web's (LOW, MEDIUM, HIGH, MAX), each wallpaper reading them its own way. **Palettes** are the web's six. Each wallpaper starts in its reference's palette: the rain in PHOSPHOR, NEURAL NET and VELOCITY in BLOOD. DATA FLOW, with no reference, starts in ICE (cyan traces, violet ones and a violet glow); MEGACITY in NEON (its reference's blue haze and pink signs).

**Zoom is the web's** (user request, 2026-10-23: zoom in, and set any wallpaper's default zoom). Its steps are Chrome's, from 50% to 200%, and each wallpaper keeps its own.
- **What a zoom does.** A wallpaper at zoom z is laid out for a screen 1/z as big and drawn z times as large: larger glyphs, nodes, traces and chips, and fewer of them.
- **Sharpness.** It's drawn at the resolution QUALITY sets for the screen, so it's as sharp at any zoom.
- **Counts.** NEURAL NET's counts are per screen at 100%, so they scale with the area that shows (1/z²), within the shaders' arrays (12 to 128 nodes).
- **VELOCITY** is laid out in shares of the screen, so it grows its panels, streaks and flare instead. The flare stays in its corner and the panels keep their slant.
- **The rain's glyphs** are drawn at 6 px per dp at most. That covers the S25 Ultra's 200% at FHD+. Past it, the trail layer is drawn smaller and scaled up, so the glyph atlas never grows past what a GPU allows (and 40-odd MB).
- **Where.** The app sets it with − / + or a pinch on the preview, a step at a time. A pinch is saved when the fingers lift.

**Motion is the web's.** It runs at the power profile's rate instead of every screen refresh. The motion is slow, so 30 fps reads the same as 120 at a quarter of the work.

## The app
- **The screen.** The selected wallpaper runs full screen. The brand panel `<fluid_motion>` sits top left with the time on the left and the date on the right (as the dashboard's). The controls sit in a panel at the bottom, within thumb reach, with **SET AS WALLPAPER ↗** last and largest. A tap on the wallpaper hides the controls to see it whole.
- **Sections.**
  - WALLPAPER: picture tiles, one marked ● ON HOME when it's the home screen's. The names may wrap to a second line. Up to four tiles share the width; with five the row scrolls sideways, each tile wide enough for its name and ● ON HOME, opening at the chosen one.
  - COLOURS: that wallpaper's palette, as swatches in each palette's own colours.
  - GLOW.
  - ZOOM: that wallpaper's zoom, − value + RESET (each button named for TalkBack, the value read out as it changes); or pinch the preview.
  - POWER: ECO, BALANCED, SMOOTH; STILL IN BATTERY SAVER; COOL DOWN WHEN HOT.
  - APP COLOURS: the screen's own palette.
  - STATUS: the rate, the cost of a frame, GPU priority and the version.
- **Type and colour.** One family, JetBrains Mono (the web's files, converted to TTF). Labels are UPPERCASE with letter-spacing, and brightness shows importance. The one effect is the accent glow, used for state.
- **Readable on a phone** (the user's preference from quest_log). The type is larger than the dashboard's (controls 15 sp, details 13 sp), every control is at least 48 dp tall, and the controls sit at the bottom.
- **Accessibility.** Every control is a radio button, switch or button for TalkBack, with labels that name what they do.
- **Battery.** No animation loops. The clock ticks once a second only while the app is in front.
