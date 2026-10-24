# Changelog

## 0.7.0 (build 9)
**MEGACITY, much lighter.** Your measurements on 0.6.1 showed it as by far the heaviest wallpaper: 5.09 ms of graphics work a frame at ULTRA, against 2.02 for VELOCITY and 1.34 for MATRIX RAIN. It was the only one AUTO CALIBRATE had to drop to LOW.
- **What changed.** Working out the city per pixel was most of that, yet the city only changes when a window goes on or off. So it's now drawn into memory once a second, and each frame just copies it, then adds what moves: signs, haze, beacons, drones and the ledge.
- **What you'll see.** The same picture, pixel for pixel. Windows now switch on the second's edge instead of a fraction after it, which no one can see.
- **Memory.** One screen-sized picture (about 10 MB on the S25 Ultra).
- **To compare** (same settings as before: MEGACITY, AMBER, GLOW MAX, battery saver as it was): AUTO CALIBRATE should now pick a higher quality for MEGACITY, and MEASURE IMPACT should show less current at every rate.
  - **Before (0.6.1, ULTRA):** GPU 5.09 ms; ECO 24 +36 mA, BALANCED 30 +46 mA, SMOOTH 60 +122 mA (over STILL 123 mA).
  - **After (0.7.0, ULTRA, measured on your S25 Ultra the same way):** GPU **0.93 ms** (−82%); ECO 24 +32 mA, BALANCED 30 **+30 mA** (−35%), SMOOTH 60 **+71 mA** (−42%), over the same STILL 123 mA. AUTO CALIBRATE now picks **ULTRA** for MEGACITY (it picked LOW), and it's no longer the heaviest wallpaper: MATRIX RAIN (1.49 ms) and VELOCITY (2.08 ms) cost more on the graphics card.
- **The same change on the dashboard** (cyberdeck_terminal 0.13.1).
- **VELOCITY left as it is:** your numbers put it the lightest on the battery of those measured (+16 mA at BALANCED).

## 0.6.1 (build 8)
- **MEGACITY without the figure** (your call): the city is seen from the rooftop ledge alone, its glass railing now running the length of it. The same on the dashboard (0.10.1).


## 0.6.0 (build 7)
A fifth wallpaper: MEGACITY.

**MEGACITY** (from your reference):
- **What it shows.** A city of towers in blue haze at night, seen from a rooftop where a lone figure stands on the ledge, looking out over it. Five layers of towers, the far ones lost in the haze; tens of thousands of windows, lit floor by floor; pink neon signs with glyphs; the haze turning violet lower down, a glow rising from below. Her outline is our own drawing of the pose.
- **What moves.** A few windows go on or off every second, each at its own moment; signs breathe, and now and then one stutters like a failing tube; beacons blink on the roofs; drones cross with short tails; patches of haze drift.
- **The same city as the dashboard's.** Laid out by the same rules from the same random numbers, and drawn by the very same shaders.
- **Colours.** It starts in NEON, the reference's; every palette makes its own city.

**On a phone:**
- **Light on the battery.** The whole city is worked out once per pixel in one pass, windows included, from a small texture sent once; per frame only the signs, drones and beacons change (a few kilobytes). Nothing is allocated per frame.
- **QUALITY LOW** also leaves out the windows' soft halo. **ZOOM** grows the towers and windows; the figure keeps her place.

**Also:**
- WALLPAPER's tiles: with five, the row scrolls sideways (each name whole, ● ON HOME too), opening at the wallpaper you're on.


## 0.5.0 (build 6)
A fourth wallpaper, DATA FLOW, and ZOOM for every wallpaper.

**DATA FLOW:**
- **What it shows.** A circuit board at work. Chips with their pins; traces running out in buses, bending at 45° like a printed board's, ending in vias and pads; packets of light racing along them, white-hot at the head with a fading tail.
- **What happens on it.** Each packet leaves a short afterglow on its trace. A via flashes when one arrives, and a chip lights up as data comes in. Now and then a whole bus fires at once.
- **The same board as the dashboard's.** It's laid out by the dashboard's rules, rule for rule: given the same random numbers, the phone and the web lay out the same board (a test keeps it so).
- **Colours.** It starts in ICE: cyan traces and violet ones, a violet glow rising from the bottom left. All six palettes and the four glow levels work as on the other wallpapers.

**On a phone:**
- **Packets.** 3.2 a second per screen. Counted per area, as on the web, a phone would get one every second or so and look idle.
- **Drawing.** The board is drawn again each frame from small shapes sent to the GPU once (about 150 on a phone), with no full-screen layer kept in memory. Per frame, only the light changes: a few kilobytes.
- **QUALITY LOW** also leaves out the soft glow round the traces and the halos round the packets. AUTO CALIBRATE measures LOW on its own for DATA FLOW, as it does for NEURAL NET.

**ZOOM, for every wallpaper** (under GLOW: **−** · 100% · **+** · RESET, or pinch the wallpaper in the app):
- **Each wallpaper keeps its own zoom.** It's the zoom that wallpaper is shown at on your home screen from then on: its default. A pinch is kept when your fingers lift.
- **From 50% to 200%,** in the same steps as the dashboard. Zoomed in, the glyphs, nodes, traces and chips are larger and fewer. VELOCITY grows its panels, streaks and flare, and the flare stays in its corner. Below 100%, there's more of everything, smaller.
- **As sharp at every zoom:** the wallpaper is drawn at the resolution QUALITY sets for your screen, not scaled up. The rain's glyphs stop growing in sharpness at 6 px per dp, which covers 200% on your S25 at its default resolution, so its glyph sheet never outgrows a phone's graphics memory.
- **Cost:** zooming in costs the same or less. Zooming out costs more, mostly on MATRIX RAIN (more glyphs to draw). AUTO CALIBRATE measures each wallpaper at its own zoom.

**All wallpapers: no more garbage per frame.**
- Every wallpaper made a little garbage each frame: MATRIX RAIN about 85 bytes, VELOCITY 23, NEURAL NET 7. Drawing a random number from a range made a new object every time, and the rain also made one walking its layers. The phone's memory cleaner would sweep this up every so often, waking up while the wallpaper should be idle.
- Now each frame makes none. A test weighs a thousand frames of every wallpaper.

**Also:**
- WALLPAPER's tiles: four to a row; a name too long for one line wraps to two ("MATRIX / RAIN") instead of being cut off.
- ECO at 24 fps is still waiting for its MEASURE IMPACT run on the S25 Ultra.

## 0.4.0 (build 5)
INSIGHT: what the wallpapers cost, day by day.

**INSIGHT** (a new section, under POWER):
- **Today:** how long a fluid_motion wallpaper was on your home or lock screen, drawing or still (battery saver, heat). Also how many frames it drew, what a frame cost the CPU and GPU, and where the time went (which wallpaper, at which rate).
- **Battery:** the estimated battery used (mAh, % of the battery, and % per hour on screen).
- **Last 7 days:** bars of the battery each day's use took, today lit.
- **The estimate:**
  - It comes from what MEASURE IMPACT measured on your phone: the extra current each wallpaper adds at each rate. Every completed run now updates it.
  - Before any run, it uses a typical figure: +1.2 mA per frame a second, from your S25 measurements.
  - It counts the screen refreshing for the wallpaper, which the system's battery screen counts as the screen's, not the app's.

**Keeping the log costs nothing:**
- It never wakes the phone and adds nothing per frame. Time on screen comes from when the wallpaper is shown, hidden or changes rate. Frames come from running totals read only when the log is written.
- The log is written when the wallpaper goes off screen, and every 10 minutes while it stays on (between frames drawn anyway).
- It keeps a week. The app's preview and the system picker's preview aren't counted.

**Also:**
- MEASURE IMPACT and AUTO CALIBRATE reports now say which version made them and when, so an old report is plain to see.
- ECO at 24 fps is still waiting for its MEASURE IMPACT run on the S25 Ultra.

## 0.3.0 (build 4)
AUTO CALIBRATE, and a QUALITY setting.

**POWER has two modes:**
- **AUTO** finds the balance for this phone. It draws at 30 frames a second, where the screen can slow down too (the measurements showed the screen's refresh is most of the cost). Each wallpaper gets the sharpest QUALITY it draws cheaply here.
  - **AUTO CALIBRATE** (about half a minute) draws each wallpaper at each quality on the phone and times its frames. Each one gets the highest quality whose frames stay under 3 ms of GPU and 3 ms of CPU (a tenth of a frame at 30 fps, so both stay at their lowest clocks).
  - A phone too weak for even LOW goes to ECO.
  - If the system or screen changes, AUTO asks for a new calibration.
- **MANUAL** keeps the profiles (ECO, BALANCED, SMOOTH) and adds **QUALITY**:
  - **LOW:** 1.5 px per dp, and NEURAL NET without its links' glow;
  - **MEDIUM:** 2.25 px per dp;
  - **HIGH:** 3 px per dp, the look so far;
  - **ULTRA:** the screen's full resolution, always.
  - Below full resolution the wallpaper is drawn smaller and scaled up smoothly in one pass. At full resolution nothing extra is drawn. On the S25 Ultra at its default FHD+, HIGH and ULTRA are both full resolution.
- **Who starts where:**
  - a new install starts in AUTO;
  - a phone that already chose a profile by hand (yours: SMOOTH) stays MANUAL, so its rate never changes by itself.

**Also:**
- A glow or quality change rebuilds its scene at the next frame, not at once. That's safe even when the wallpaper's surface has gone away for a moment.
- COPY DIAGNOSTICS includes the mode, the quality and what AUTO CALIBRATE measured.

**Please check on the S25 Ultra:**
- **ECO:** run MEASURE IMPACT on MATRIX RAIN. ECO (24 fps here) should cost less than BALANCED now.
- **AUTO:** switch POWER to AUTO and run AUTO CALIBRATE, then paste COPY DIAGNOSTICS: it has each wallpaper's measured quality.
- **LOW:** in MANUAL, try QUALITY LOW on each wallpaper, to see how it looks on your screen.

## 0.2.1 (build 3)
ECO now actually saves battery.

- **The finding** (MEASURE IMPACT on the S25 Ultra, 0.2.0): what a wallpaper costs is mostly how fast the screen refreshes, not the drawing. A frame takes about 1 ms of CPU and 1–1.7 ms of GPU at every rate. ECO at 20 fps cost as much as BALANCED at 30, or more, because the display has no rate that 20 fits (it runs at 60, 48, 30, 24 or 10 Hz), so the panel stayed at 60 Hz.
- **The fix:** each profile now draws at a rate this display can run at. ECO takes the display's own slow rate between 20 and 30 Hz if it has one (24 on the S25 Ultra, so the panel drops to 24 Hz). On a display without one it stays at 20, which fits 60 and 120 Hz evenly. BALANCED (30) and SMOOTH (60) are unchanged.
- POWER and MEASURE IMPACT show and measure the rate each profile really draws at here. The rates follow the display if its settings change (motion smoothness).

**Please check:** a MEASURE IMPACT run on MATRIX RAIN. ECO should now cost less than BALANCED.

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
