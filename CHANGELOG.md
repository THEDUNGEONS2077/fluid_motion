# Changelog

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
