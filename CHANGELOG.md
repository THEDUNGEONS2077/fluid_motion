# Changelog

## 0.2.0 (build 2)
Measuring what the wallpapers really cost, and a little less work for the same picture.

**Measure it on your phone:**
- **MEASURE IMPACT** (STATUS): with the phone unplugged, it shows the wallpaper full screen still, then at ECO, BALANCED and SMOOTH, 45 s each (about three minutes). It reads the battery current every second and reports what each rate adds over the still frame: mA, % of the battery an hour, and the CPU and GPU time of a frame. The last report stays in STATUS.
- **STATUS** now shows each frame's measured CPU and GPU time (the GPU is timed where the phone's driver allows it) and the share of late frames.
- **COPY DIAGNOSTICS** copies the phone's GPU, display rates, surface, settings, frame costs and the last MEASURE IMPACT report, to paste into a message.

**Less work, the same picture:**
- MATRIX RAIN's fades are built on the GPU: about 200 fewer records filled and sent each frame (62 instead of 257). The frames are pixel for pixel the same.
- **COOL DOWN WHEN HOT** now also looks ahead: as the phone nears its heat limit (its thermal headroom, Android 11+), SMOOTH eases to 30 frames a second, and comes back once it has cooled. Before, the rate only dropped once the phone was already warm.

**Please send back:** a MEASURE IMPACT report for each wallpaper (COPY DIAGNOSTICS includes it). Run them unplugged, with the brightness as you normally have it.

## 0.1.0 (build 1)
The first test build: three live wallpapers from cyberdeck_terminal, and the app to set them up.

**Wallpapers** (each set from the app, or from the phone's wallpaper picker):
- **MATRIX RAIN**: the dashboard's rain on the GPU, with katakana, glowing heads and long trails. The screen opens full.
- **NEURAL NET**: the plexus, with drifting nodes, links, bokeh, and signals lighting the nodes they reach.
- **VELOCITY**: the panels of light, streaks and the corner flare, with the reference's slant kept on a portrait screen.
- Each wallpaper has its own colours (the dashboard's six palettes) and glow (LOW to MAX).

**Battery and performance:**
- **Hidden:** it draws nothing while the wallpaper is hidden.
- **Rate:** 30 frames a second by default (ECO 20, SMOOTH 60), with a refresh-rate hint to the display.
- **Battery saver:** a still frame.
- **Heat:** half rate when warm, a still frame when hot.
- **Priority:** low GPU priority, efficiency cores, nothing allocated per frame.

**The app:** a live preview of the selected wallpaper under the dashboard-style controls, with SET AS WALLPAPER, and a status line with the frame rate and the cost of each frame.

**Please check on the S25 Ultra:**
- each wallpaper looks right and moves smoothly;
- setting each from the app works, for the home screen and for home and lock screens;
- colour and glow changes reach a wallpaper that's already set;
- with battery saver on, the wallpaper holds still;
- battery use over a day (Settings ▸ Battery: fluid_motion should be small).
