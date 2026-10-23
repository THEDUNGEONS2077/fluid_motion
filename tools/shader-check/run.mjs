/**
 * tools/shader-check/run.mjs: fluid_motion's shaders, checked without a
 * phone. WebGL2 is OpenGL ES 3.0 (the same GLSL ES 3.00), and Chromium's
 * ANGLE validates shaders strictly, so a shader that compiles, links and
 * draws right here is very likely right on the phone.
 *
 * What it does:
 *   1. reads every shader in app/src/main/assets/shaders (pasting in
 *      `#include`s as gl/Gl.kt does) and the frames the JVM dump wrote
 *      (app/build/shader-check: run `./gradlew :app:testDebugUnitTest` first);
 *   2. in headless Chromium (software GPU: SwiftShader), compiles and links
 *      every program, then draws each scene the way its Kotlin renderer
 *      does (render.js mirrors them call for call);
 *   3. writes the frames to out/*.png and prints any problem: a compile or
 *      link error, a uniform the shaders don't use, a GL error, a frame that
 *      came out empty. Exits 1 if there was one.
 * No server, no port: the page is set from here and talked to directly.
 */
import { chromium } from 'playwright';
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..', '..');
const shaderDir = join(root, 'app/src/main/assets/shaders');
const dumpDir = join(root, 'app/build/shader-check');
const outDir = join(here, 'out');
// Android's own old CJK fallback font: it has the half-width katakana the phone's fonts do.
const KANA_FONT = '/usr/share/fonts/truetype/droid/DroidSansFallbackFull.ttf';

/** A shader's source with its includes pasted in. */
function source(name) {
  return readFileSync(join(shaderDir, name), 'utf8')
    .split('\n')
    .map((line) => (line.trim().startsWith('#include ') ? source(`${line.trim().slice('#include '.length)}.glsl`) : line))
    .join('\n');
}

const shaders = Object.fromEntries(
  readdirSync(shaderDir)
    .filter((n) => /\.(vert|frag)$/.test(n))
    .map((n) => [n, source(n)]),
);
const SCENES = ['rain', 'rain-low', 'neural', 'velocity'];
for (const scene of SCENES) {
  if (!existsSync(join(dumpDir, `${scene}.json`))) {
    console.error(`no ${scene}.json in ${dumpDir}: run ./gradlew :app:testDebugUnitTest first`);
    process.exit(2);
  }
}
const dumps = Object.fromEntries(SCENES.map((s) => [s, JSON.parse(readFileSync(join(dumpDir, `${s}.json`), 'utf8'))]));
const fonts = {
  mono: readFileSync(join(root, 'app/src/main/res/font/jetbrains_mono_regular.ttf')).toString('base64'),
  kana: existsSync(KANA_FONT) ? readFileSync(KANA_FONT).toString('base64') : null,
};

mkdirSync(outDir, { recursive: true });
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
let failed = false;
try {
  const page = await browser.newPage();
  page.on('console', (m) => console.log(`  [page] ${m.text()}`));
  page.on('pageerror', (e) => {
    console.error(`  [page error] ${e.message}`);
    failed = true;
  });
  await page.setContent('<!doctype html><meta charset="utf-8"><body style="margin:0;background:#000"></body>');
  await page.addScriptTag({ path: join(here, 'render.js') });
  const started = Date.now();
  const result = await page.evaluate((input) => window.shaderCheck(input), { shaders, dumps, fonts });
  console.log(`drew ${Object.keys(result.images).length} frames in ${((Date.now() - started) / 1000).toFixed(1)} s (${result.renderer})`);
  for (const [name, url] of Object.entries(result.images)) {
    writeFileSync(join(outDir, `${name}.png`), Buffer.from(url.split(',')[1], 'base64'));
    console.log(`  out/${name}.png  ${result.notes[name] ?? ''}`);
  }
  for (const p of result.problems) console.error(`PROBLEM: ${p}`);
  if (result.problems.length) failed = true;
} finally {
  await browser.close();
}
console.log(failed ? 'shader check FAILED' : 'shader check passed');
process.exit(failed ? 1 : 0);
