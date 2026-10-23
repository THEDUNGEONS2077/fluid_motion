# Design

## Origin
The three wallpapers are cyberdeck_terminal's (`~/projects/cyberdeck_terminal/src/wallpaper/`), each matched there to a reference image. Its DESIGN.md has the measurements. Here they're redrawn on the GPU to look the same: same parameters, same colours, same compositing. The app's own screen is the dashboard's design system (theme.css and app.css) in Compose.

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

**Glow levels** are the web's (LOW, MEDIUM, HIGH, MAX), each wallpaper reading them its own way. **Palettes** are the web's six. Each wallpaper starts in its reference's palette: the rain in PHOSPHOR, the others in BLOOD.

**Motion is the web's.** It runs at the power profile's rate instead of every screen refresh. The motion is slow, so 30 fps reads the same as 120 at a quarter of the work.

## The app
- **The screen.** The selected wallpaper runs full screen. The brand panel `<fluid_motion>` sits top left with the time on the left and the date on the right (as the dashboard's). The controls sit in a panel at the bottom, within thumb reach, with **SET AS WALLPAPER ↗** last and largest. A tap on the wallpaper hides the controls to see it whole.
- **Sections.**
  - WALLPAPER: picture tiles, one marked ● ON HOME when it's the home screen's.
  - COLOURS: that wallpaper's palette, as swatches in each palette's own colours.
  - GLOW.
  - POWER: ECO, BALANCED, SMOOTH; STILL IN BATTERY SAVER; COOL DOWN WHEN HOT.
  - APP COLOURS: the screen's own palette.
  - STATUS: the rate, the cost of a frame, GPU priority and the version.
- **Type and colour.** One family, JetBrains Mono (the web's files, converted to TTF). Labels are UPPERCASE with letter-spacing, and brightness shows importance. The one effect is the accent glow, used for state.
- **Readable on a phone** (the user's preference from quest_log). The type is larger than the dashboard's (controls 15 sp, details 13 sp), every control is at least 48 dp tall, and the controls sit at the bottom.
- **Accessibility.** Every control is a radio button, switch or button for TalkBack, with labels that name what they do.
- **Battery.** No animation loops. The clock ticks once a second only while the app is in front.
