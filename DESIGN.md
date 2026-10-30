# Design

## Origin
The wallpapers are cyberdeck_terminal's (`~/projects/cyberdeck_terminal/src/wallpaper/`). MATRIX RAIN, NEURAL NET and VELOCITY were each matched there to a reference image; DATA FLOW was designed there, at full fidelity, then brought here; MEGACITY, HORIZON and SUNSET were matched there to the user's references and brought here with the same shaders. Its DESIGN.md has the measurements. Here they're redrawn on the GPU to look the same: same parameters, same colours, same compositing. The app's own screen is the dashboard's design system (theme.css and app.css) in Compose.

## The wallpapers on a phone
The web versions were tuned on a landscape monitor. On a portrait phone, a few things are measured differently, so that each still looks like its reference.

| | On the web | On the phone | Why |
|---|---|---|---|
| MATRIX RAIN | Glyphs at 1–2 px per CSS px | At the screen's density, up to 3 px per dp | Crisp at 2.8 px per dp. The look is otherwise the web's: brightness by eighths of the screen matches within the frame-to-frame noise |
| NEURAL NET | 26 nodes per million px² | 64 nodes per screen | At the web's density a portrait phone shows only about 20 nodes |
| VELOCITY sizes | Shares of the width | Shares of a base length, ¾ of the screen's longer side | Shares of a narrow width made thin slivers |
| VELOCITY slant | Lean as a share of the width | Lean as a share of a 16:9 width at the screen's height | The panels keep the reference's angle |
| VELOCITY flare | Placed by width and height | Placed from the bottom-right corner in base lengths | The web's arc ends mid-screen on a portrait screen; here both ends stay off the screen |
| VELOCITY red panels | Start right of the pale panel | On portrait, start spread over the width | Bunched on the right, they all drift off together in the first minute |
| DATA FLOW board | Laid out once per screen size | The same rules: the same random numbers lay out the same board (a CSS px is a dp) | A port rule for rule; FlowBoardGoldenTest keeps it so |
| DATA FLOW packets | 2.2 a second per million px² | 3.2 a second per screen | At the web's density a portrait phone gets one packet every second or so |
| DATA FLOW drawing | The board drawn once into a layer; the light added over it, each tail in six fading slices | The board drawn again each frame from instances, the dot grid worked out per pixel; each tail fades smoothly in the shader (the slices' fade, squared) | No full-screen layer to keep and read back every frame |
| MEGACITY | Drawn per pixel in WebGL 2 | The same shaders, in OpenGL ES 3: the same city, pixel for pixel, from the same layout | One pass per pixel was already the cheapest way to draw tens of thousands of windows |
| HORIZON | A 16:9 layout: about 3.6 cells across the bottom, 41 at the haze | The web's camera and lens; cells smaller by √(how much narrower the screen is): 0.51 units on the S25 Ultra (narrower by 3.85), about half the web's count across, passing at the web's pace in cells | A narrow screen sees a narrow slice of the land: at the web's cells only a cell or two would cross it. Shrunk by the full ratio (0.8.x), the tall land showed four times the web's rows, a crowd of small hills; the user asked for fewer, closer to the reference (0.9.0) |
| HORIZON sky | Drawn once, kept by the canvas | Worked out each frame (top half), dithered by up to half a shade (its own `horizon_sky.frag`, 0.12.0) | A phone's screen doesn't keep its picture; reading a kept copy back costs more than the sums. The glow fades over a few dozen shades of dark red, which an OLED showed as rings (F7); the dashboard's sky is left as it is (the user) |
| HORIZON sun | Core and glow a share of the screen's height (its short side) | The same share of the short side (the width), × √(how much narrower): about 2× on the S25 Ultra (0.12.0) | Sized by a tall phone's narrow width alone it was a small dot (F7, the user: bigger) |
| HORIZON land | 64 units deep, nearest strips first with a depth buffer | 48 units deep (the haze hides the land from 44), farthest first, back faces culled, no depth buffer | A quarter fewer triangles; no screen-sized buffer written each frame |
| SUNSET sun | Scaled by the height | Scaled by the short side (the width), kept on the horizon as far up, in its radii, as on the web | Scaled by a phone's height, it would fill three quarters of the width |
| SUNSET grid | The camera at 1 unit | The camera risen by √(how much narrower the screen is): about half the web's squares across, bigger, the same shape, moving at the same pace on screen | Rising the full ratio (all the web's squares) crowded the far rows into a haze from far below the horizon, the sun's base cut well above the grid |
| SUNSET melt | 5% of the height below the horizon | √(composition × focal × camera height) × 5%: as far as the grid's lines crowd | So the grid fades straight into the sky, with no dark band |
| PRISM | A 34° view on 16:9; light pools over the middle 12 units | As much across as the web's 16:9 over √(how much narrower), the view as tall as that makes it (about 62° on the S25 Ultra); the light pools over a part as much narrower | Through the web's lens an upright screen saw a slice a few blocks wide |

**MEGACITY on a phone.** The reference is a phone wallpaper, so the phone has its composition: the rooftop ledge across the bottom left (the reference's figure on it was left out at the user's wish, 0.6.1). The near layers of towers are sparse enough that the violet haze shows low down, as in the reference. ZOOM grows the towers and windows; the ledge keeps its place. It starts in NEON.

**DATA FLOW on a phone.** The board's rules are the web's, so on a phone the chips are as big as they are in a browser of that size: the main chip spans about half the width, and its buses alone cover the share of the screen the board aims for (16% of its points). The loose traces that fill a monitor's board between the buses (about half of its traces, many ending in pads) are rare on a phone.

**Glow levels** are the web's (LOW, MEDIUM, HIGH, MAX), each wallpaper reading them its own way. **Palettes** are the web's eight (SYNTH and OUTRUN from 0.8.0). Each wallpaper starts in its reference's palette: the rain in PHOSPHOR, NEURAL NET and VELOCITY in BLOOD. DATA FLOW, with no reference, starts in ICE (cyan traces, violet ones and a violet glow; from 0.12.0, as on the web, at least a fifth of every board's traces in the violet: by chance about 1 board in 14 had come out all one colour); MEGACITY in NEON (its reference's blue haze and pink signs); HORIZON in SYNTH and SUNSET in OUTRUN, the palettes made for them.

**Zoom is the web's** (user request, 2026-10-23: zoom in, and set any wallpaper's default zoom). Its steps are Chrome's, from 50% to 200%, and each wallpaper keeps its own.
- **What a zoom does.** A wallpaper at zoom z is laid out for a screen 1/z as big and drawn z times as large: larger glyphs, nodes, traces and chips, and fewer of them.
- **Sharpness.** It's drawn at the resolution QUALITY sets for the screen, so it's as sharp at any zoom.
- **Counts.** NEURAL NET's counts are per screen at 100%, so they scale with the area that shows (1/z²), within the shaders' arrays (12 to 128 nodes).
- **VELOCITY** is laid out in shares of the screen, so it grows its panels, streaks and flare instead. The flare stays in its corner and the panels keep their slant.
- **The rain's glyphs** are drawn at 6 px per dp at most. That covers the S25 Ultra's 200% at FHD+. Past it, the trail layer is drawn smaller and scaled up, so the glyph atlas never grows past what a GPU allows (and 40-odd MB).
- **Where.** The app sets it with − / + or a pinch on the preview, a step at a time. A pinch is saved when the fingers lift.

**Motion is the web's.** It runs at the power profile's rate instead of every screen refresh. The motion is slow, so 30 fps reads the same as 120 at a quarter of the work.

## Each wallpaper's look
Each one is the web's (cyberdeck_terminal DESIGN.md has its reference and how it was matched); this is what it is on the phone, and what was decided here.

**MATRIX RAIN** (starts in PHOSPHOR). The dashboard's rain on the GPU: three layers of falling glyphs (katakana and digits), glowing pale heads, long fading trails, opening full.
- **Its light** is worked out per pixel in `rain_composite.frag`, as the web's CSS layers: a faint accent haze behind (strongest at the top, (1 + cos πt) / 2 of 7%), the rain, then a darkening to black over the full height (t³).
- **Since 0.8.1** both curves span the whole screen with no corners. The floor's old start at 84% and the haze's corners read as lines (the user: "a seamless and smooth gradient from black bottom to greenest top").
- **GLOW** sets the glyphs' glow, as on the web.

**NEURAL NET** (BLOOD). A plexus: glowing nodes joined by long thin links, a deep teal sky and a red glow rising from the bottom right. Signals run along links about once a second.
- **Counts:** set per screen (64 nodes), not per area, so a tall phone isn't sparse.
- **No bokeh:** the reference's soft out-of-focus discs drifting behind the net were removed at the user's request (0.9.0, the web's 0.19.0 too).
- **GLOW:** sets the halos and the links' soft second pass (dropped at LOW quality).

**VELOCITY** (BLOOD). Light caught in motion: a big pale panel and dark red panels leaning left, drifting across, streaks racing, fine lines flickering, a breathing blue flare in the corner. All of it is additive light over a near-black background.
- **On a phone:** sizes come from a base length (¾ of the long side), the 16:9 slant is kept, and the flare is placed from its corner.
- **Cost:** it was the heaviest on the GPU (about 2 ms at ULTRA) yet light on the battery (+16 mA at BALANCED): the earlier tuning candidate.

**DATA FLOW** (ICE; no reference). A circuit board at night, laid out by the web's rules:
- traces at 45° steps leaving the chips in buses that bend like ribbons;
- vias and pads;
- packets of light running along with fading tails;
- vias flashing and chips glowing as data arrives.

On a phone the board is mostly the main chip's buses. ZOOM below 100% fills it out.

**MEGACITY** (NEON). A vertical cyberpunk city in blue haze at night, seen from a rooftop ledge with a glass railing (the reference's figure left out, 0.6.1):
- five layers of towers;
- windows switching on and off;
- neon signs that stutter;
- beacons, drones and drifting haze.

Its shaders are the web's (`mc_*`), and the city is drawn once a second (0.7.0).

**HORIZON** (SYNTH). A wireframe land of jittered triangles, teal edges with a soft core and glow, rolling toward a hot pink glow with a white core just above the middle, under a dark plum sky.
- **The camera** glides forward, and nothing else moves.
- **On a phone:** cells half the web's size (about half as many across as its 16:9: big, dark triangles near, as in the reference), the land 48 deep, drawn far end first.
- **GLOW:** sets the edges' glow and the horizon's.

**SUNSET** (OUTRUN). The outrun sunset:
- **The sun:** a big striped sun, golden to hot pink, its base cut by the horizon;
- **The sky:** a starry night, purple left and blue right, under CRT scanlines;
- **The grid:** gently rolling, pink left and teal right, gliding toward you and melting into the sky with no line.

On a phone the sun is sized by the width, and the camera sits higher (√ of how much narrower the screen is) for bigger squares with a clear perspective. GLOW doesn't change it (as on the web: the sun's glow is the reference's own).

**QUANTUM KERNEL** (AZURE). Five depths of code on true black, in the rain's glyphs (mirrored katakana, digits, its symbols as operators), laid out as code: lanes of indented blocks. Small dim code far back, sharper nearer, big bold glowing code in focus, a few huge lines blurred in front. Each scrolls up at its own pace, nearer faster; about every 1.4 s a line in focus lights up as if run. The dashboard's as of its 0.27.1 (the user: "It's perfect"). On a phone the text is much smaller (the user, 0.13.1: "text needs to be way smaller"): every size of a depth (font, speed, glow, blur) is scaled by (width ÷ 1920)^0.57, 0.40 on the S25 (about 60 glyphs across the code in focus, 27 before), never below 0.35, and 1 on a screen as wide as the dashboard; ZOOM applies on top. Lanes are counted at that scale, so the S25 lays out as if 961 wide (lanes 4, 3, 3, 2, 2), and they repeat from a random point per depth so code runs off both edges alike (the dashboard's 0.27.2). The frame is one pass (the dashboard copies five sheets).

**PRISM** (NEON). Smoky glass blocks of many sizes from a high angle, magenta light glowing inside the glass and blue around it, thin smooth edges (each face draws its own outline: no multisampling). The camera sways a little and the lights breathe. From the dashboard's 0.23.1 (no neon tubes: the user had them removed there first). On a phone the view is taller (see the table above), so the blocks recede up the screen.

## The app
- **The screen.** The selected wallpaper runs full screen. The brand panel `<fluid_motion>` sits top left with the time on the left and the date on the right (as the dashboard's). The controls sit in a panel at the bottom, within thumb reach, with **SET AS WALLPAPER ↗** last and largest. A tap on the wallpaper hides the controls to see it whole.
- **Sections.** Each folds (0.11.0, the user: the settings had a lot going on; every section collapsible, closed to start): its title is a button with `▸` / `▾` in the accent, and while closed it says what's chosen in it beside the lit rule, dim (`PRISM`, `NEON`, `AUTO · BALANCED · ULTRA`). All are closed when the app opens; CALIBRATION opens by itself when a calibration finishes. A note from a run or a choice shows above them, so a closed section can't hide it. The cyberdeck dashboard's SYSTEM folds the same way (its 0.25.0).
  - WALLPAPER: picture tiles (thumbnails rendered by the shader check), one marked ● ON HOME when it's the home screen's. The names may wrap to a second line. Up to four tiles share the width; with more (seven since 0.8.0, eight since 0.10.0) the row scrolls sideways, each tile wide enough for its name and ● ON HOME, opening at the chosen one.
  - COLOURS: that wallpaper's palette, as swatches in each palette's own colours, three to a row (eight palettes: 3, 3, 2).
  - GLOW (shown for every wallpaper; SUNSET ignores it: F7 may hide it there).
  - ZOOM: that wallpaper's zoom, − value + RESET (each button named for TalkBack, the value read out as it changes); or pinch the preview.
  - POWER: AUTO or MANUAL (ECO, BALANCED, SMOOTH; QUALITY); STILL IN BATTERY SAVER; COOL DOWN WHEN HOT.
  - CALIBRATION (0.11.0): what AUTO picked for each wallpaper and its cost, a wallpaper over budget lit bright; then LONGEST BATTERY, RECOMMENDED and SMOOTHEST, each a boxed card on the background colour (RECOMMENDED's border and name lit) with its figures in words and a USE button.
  - INSIGHT: the week in bars, and COPY BATTERY DATA.
  - APP COLOURS: the screen's own palette.
  - STATUS: the rate, the cost of a frame, GPU priority and the version.
- **Type and colour.** One family, JetBrains Mono (the web's files, converted to TTF). Labels are UPPERCASE with letter-spacing, and brightness shows importance. The one effect is the accent glow, used for state.
- **Readable on a phone** (the user's preference from quest_log). The type is larger than the dashboard's (controls 15 sp, details 13 sp), every control is at least 48 dp tall, and the controls sit at the bottom.
- **Accessibility.** Every control is a radio button, switch or button for TalkBack, with labels that name what they do.
- **Battery.** No animation loops. The clock ticks once a second only while the app is in front.
