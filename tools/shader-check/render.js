/**
 * tools/shader-check/render.js: runs in the page. Draws the three scenes
 * the way their Kotlin renderers do (app/.../scene/<scene>/…Renderer.kt),
 * call for call, with the real shaders and the frames the JVM dump wrote.
 * Keep the two in step: a renderer change belongs here too.
 *
 * The one part that can't be the phone's is the rain's atlas (Android
 * Canvas draws it there): it's laid out by the same numbers
 * (lib/rain/AtlasLayout.kt, in the dump) and drawn the web's way (canvas
 * text, blur(σ) for the glows), which is what the Android one copies.
 */
window.shaderCheck = async function ({ shaders, dumps, fonts }) {
  const problems = [];
  const images = {};
  const notes = {};
  const unused = [];

  // The fonts: JetBrains Mono, and the CJK fallback for the katakana.
  const faces = [new FontFace('JBMono', `url(data:font/ttf;base64,${fonts.mono})`)];
  if (fonts.kana) faces.push(new FontFace('Kana', `url(data:font/ttf;base64,${fonts.kana})`));
  for (const f of faces) {
    await f.load();
    document.fonts.add(f);
  }
  if (!fonts.kana) problems.push('no katakana font here: the rain was drawn without its katakana');

  const [W, H] = dumps.rain.screen;
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true, alpha: true, premultipliedAlpha: true });
  if (!gl) return { problems: ['no WebGL2 in this browser'], images, notes, renderer: 'none' };
  const info = gl.getExtension('WEBGL_debug_renderer_info');
  const renderer = info ? gl.getParameter(info.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER);

  /** Notes a pending GL error. */
  const check = (what) => {
    const e = gl.getError();
    if (e) problems.push(`${what}: GL error 0x${e.toString(16)}`);
  };

  /** A program from two shader files, as gl/Gl.kt builds one; u() notes uniforms the program doesn't use. */
  function program(vs, fs) {
    const compile = (type, name) => {
      const s = gl.createShader(type);
      gl.shaderSource(s, shaders[name]);
      gl.compileShader(s);
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) problems.push(`compile ${name}: ${gl.getShaderInfoLog(s)}`);
      return s;
    };
    const p = gl.createProgram();
    gl.attachShader(p, compile(gl.VERTEX_SHADER, vs));
    gl.attachShader(p, compile(gl.FRAGMENT_SHADER, fs));
    gl.linkProgram(p);
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) problems.push(`link ${vs} + ${fs}: ${gl.getProgramInfoLog(p)}`);
    const locations = {};
    return {
      use: () => gl.useProgram(p),
      u(name) {
        if (!(name in locations)) {
          locations[name] = gl.getUniformLocation(p, name);
          if (locations[name] === null) unused.push(`${vs}+${fs}: ${name}`);
        }
        return locations[name];
      },
    };
  }

  const rgb = (c) => [((c >> 16) & 255) / 255, ((c >> 8) & 255) / 255, (c & 255) / 255];
  const set3 = (loc, c) => gl.uniform3f(loc, ...rgb(c));
  const empty = gl.createVertexArray();
  /** n quads from gl_VertexID and gl_InstanceID alone (gl/Gl.kt EmptyVao). */
  const quads = (n) => {
    if (n <= 0) return;
    gl.bindVertexArray(empty);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, n);
    gl.bindVertexArray(null);
  };
  const fullScreen = () => {
    gl.bindVertexArray(empty);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    gl.bindVertexArray(null);
  };

  /** The screen as a PNG, with its mean brightness by eighths of the height (to compare with the web's). */
  function snapshot(name, note = '') {
    check(`drawing ${name}`);
    const px = new Uint8Array(W * H * 4);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
    const img = new ImageData(W, H);
    const eighths = new Array(8).fill(0).map(() => [0, 0, 0]);
    let brightest = 0;
    for (let y = 0; y < H; y++) {
      // GL's rows run bottom up.
      const src = (H - 1 - y) * W * 4;
      const e = eighths[Math.floor((y * 8) / H)];
      for (let x = 0; x < W; x++) {
        const i = src + x * 4;
        const o = (y * W + x) * 4;
        img.data[o] = px[i];
        img.data[o + 1] = px[i + 1];
        img.data[o + 2] = px[i + 2];
        img.data[o + 3] = 255;
        e[0] += px[i];
        e[1] += px[i + 1];
        e[2] += px[i + 2];
        brightest = Math.max(brightest, px[i], px[i + 1], px[i + 2]);
      }
    }
    const per = (W * H) / 8;
    const profile = eighths.map((e) => e.map((v) => Math.round(v / per)).join('/')).join('  ');
    if (brightest < 16) problems.push(`${name} came out black`);
    const out = document.createElement('canvas');
    out.width = W;
    out.height = H;
    out.getContext('2d').putImageData(img, 0, 0);
    images[name] = out.toDataURL('image/png');
    notes[name] = `${note} mean rgb by eighths: ${profile}`;
  }

  // --- MATRIX RAIN (scene/rain/RainRenderer.kt) -----------------------------------------
  function rain(d) {
    const A = d.atlas;
    const kana = fonts.kana ? [...d.kana] : [];
    const glyphs = [...kana, ...[...d.base]];
    const slotW = (l) => A.cellW[l] + 2 * A.padSlot;
    const slotH = (l) => A.cellH[l] + 2 * A.padSlot;
    /** One channel of the atlas (GlyphAtlas.kt): the glyphs in white, blurred for the glows, bolder for the erase. */
    const plane = (kind) => {
      const c = document.createElement('canvas');
      c.width = A.width;
      c.height = A.height;
      const x = c.getContext('2d', { willReadFrequently: true });
      x.fillStyle = '#fff';
      x.strokeStyle = '#fff';
      x.textAlign = 'center';
      x.textBaseline = 'middle';
      x.lineWidth = d.eraseStroke;
      const sigma = kind === 'head' ? (d.glow.head * d.pxPerDp) / 2 : kind === 'body' ? (d.glow.body * d.pxPerDp) / 2 : 0;
      x.filter = sigma > 0 ? `blur(${sigma}px)` : 'none';
      for (let l = 0; l < 3; l++) {
        x.font = `${A.fontPx[l]}px JBMono, Kana`;
        glyphs.forEach((g, s) => {
          const cx = (s % A.perRow) * slotW(l) + slotW(l) / 2;
          const cy = A.originY[l] + Math.floor(s / A.perRow) * slotH(l) + slotH(l) / 2;
          x.save();
          if (s < kana.length) {
            x.translate(cx, cy);
            x.scale(-1, 1);
            x.translate(-cx, -cy);
          }
          x.fillText(g, cx, cy);
          if (kind === 'erase') x.strokeText(g, cx, cy);
          x.restore();
        });
      }
      return x.getImageData(0, 0, A.width, A.height).data;
    };
    const [core, head, body, erase] = ['core', 'head', 'body', 'erase'].map(plane);
    const rgba = new Uint8Array(A.width * A.height * 4);
    for (let i = 0; i < A.width * A.height; i++) {
      rgba[4 * i] = core[4 * i + 3];
      rgba[4 * i + 1] = head[4 * i + 3];
      rgba[4 * i + 2] = body[4 * i + 3];
      rgba[4 * i + 3] = erase[4 * i + 3];
    }
    const atlas = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, atlas);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, A.width, A.height, 0, gl.RGBA, gl.UNSIGNED_BYTE, rgba);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);

    // The trail layer (makeLayer).
    const layer = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, layer);
    gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, W, H);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, layer, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) problems.push('rain: trail layer framebuffer incomplete');
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);

    const ops = program('rain_ops.vert', 'rain_ops.frag');
    const comp = program('fullscreen.vert', 'rain_composite.frag');
    // The instance stream (gl/Gl.kt InstanceStream): column/row, code, alpha.
    const buf = gl.createBuffer();
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, buf);
    for (const [loc, size, offset] of [[0, 2, 0], [1, 1, 2], [2, 1, 3]]) {
      gl.enableVertexAttribArray(loc);
      gl.vertexAttribPointer(loc, size, gl.FLOAT, false, 16, offset * 4);
      gl.vertexAttribDivisor(loc, 1);
    }
    gl.bindVertexArray(null);

    // setOpsUniforms.
    ops.use();
    gl.uniform2f(ops.u('uTarget'), W, H);
    gl.uniform2fv(ops.u('uPitch'), new Float32Array(d.pitch));
    gl.uniform2fv(ops.u('uCell'), new Float32Array([0, 1, 2].flatMap((l) => [A.cellW[l], A.cellH[l]])));
    gl.uniform1fv(ops.u('uOrigin'), new Float32Array(A.originY));
    gl.uniform4f(ops.u('uPads'), A.padHead, A.padBody, A.padErase, A.padSlot);
    gl.uniform2f(ops.u('uAtlas'), A.width, A.height);
    gl.uniform1i(ops.u('uPerRow'), A.perRow);
    gl.uniform1f(ops.u('uStripe'), d.fadeStripe);
    set3(ops.u('uBright'), d.colors.textBright);
    set3(ops.u('uBody'), d.body);
    set3(ops.u('uDim'), d.dim);
    set3(ops.u('uGlowColor'), d.colors.accent);
    gl.uniform1f(ops.u('uGlowAlpha'), d.glow.alpha);
    gl.uniform1f(ops.u('uHeadLayers'), d.glow.headLayers);
    gl.uniform1i(ops.u('uGlyphs'), 0);
    comp.use();
    set3(comp.u('uBg'), d.colors.bg);
    set3(comp.u('uAccent'), d.colors.accent);
    gl.uniform1i(comp.u('uRain'), 0);

    /** beginLayer + the records drawn in order (the phone draws them in batches of 4096: the same picture). */
    const draw = (records) => {
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.viewport(0, 0, W, H);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      ops.use();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, atlas);
      if (!records.length) return;
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(records), gl.STREAM_DRAW);
      gl.bindVertexArray(vao);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, records.length / 4);
      gl.bindVertexArray(null);
    };
    /** The composite to the screen (render, step 2). */
    const compose = () => {
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, W, H);
      gl.disable(gl.BLEND);
      gl.clear(gl.COLOR_BUFFER_BIT);
      comp.use();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, layer);
      fullScreen();
    };
    draw(d.prewarm);
    compose();
    snapshot('rain-open', `${d.prewarm.length / 4} records painted the opening screen;`);
    let records = 0;
    for (const f of d.frames) {
      draw(f);
      records += f.length / 4;
    }
    compose();
    snapshot('rain', `then ${d.frames.length} frames, ${Math.round(records / d.frames.length)} records each;`);
  }

  // --- NEURAL NET (scene/neural/NeuralRenderer.kt) --------------------------------------
  function neural(d) {
    const g = d.glow;
    const c = d.colors;
    const bg = program('fullscreen.vert', 'neural_bg.frag');
    const bokeh = program('neural_bokeh.vert', 'neural_bokeh.frag');
    const link = program('neural_link.vert', 'neural_link.frag');
    const spark = program('neural_spark.vert', 'neural_spark.frag');
    // sendLinks: node a, node b, weight.
    const linkBuf = gl.createBuffer();
    const linkVao = gl.createVertexArray();
    gl.bindVertexArray(linkVao);
    gl.bindBuffer(gl.ARRAY_BUFFER, linkBuf);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(d.links), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 12, 0);
    gl.vertexAttribDivisor(0, 1);
    gl.bindVertexArray(null);
    const linkCount = d.links.length / 3;
    // setUniforms.
    bg.use();
    set3(bg.u('uBg'), c.bg);
    set3(bg.u('uCool'), c.cool);
    set3(bg.u('uAccent'), c.accent);
    for (const p of [bokeh, link, spark]) {
      p.use();
      gl.uniform2f(p.u('uTargetPx'), W, H);
      gl.uniform1f(p.u('uDensity'), d.density);
      gl.uniform2f(p.u('uScreen'), d.dp[0], d.dp[1]);
      set3(p.u('uCool'), d.coolLifted);
      set3(p.u('uBright'), c.textBright);
      set3(p.u('uAccent'), c.accent);
    }
    link.use();
    gl.uniform1f(link.u('uMaxLink'), d.maxLink);
    spark.use();
    gl.uniform1f(spark.u('uEdge'), d.edge);
    gl.uniform1f(spark.u('uHalo'), g.halo);
    // render.
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    bg.use();
    fullScreen();
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    bokeh.use();
    gl.uniform4fv(bokeh.u('uBokeh'), new Float32Array(d.bokeh));
    quads(d.bokeh.length / 4);
    link.use();
    gl.uniform4fv(link.u('uNodes'), new Float32Array(d.nodes));
    gl.bindVertexArray(linkVao);
    if (g.lineGlow > 0) {
      gl.uniform1f(link.u('uWidth'), d.lineWidth * d.glowWidth);
      gl.uniform1f(link.u('uStrength'), g.lineGlow);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, linkCount);
    }
    gl.uniform1f(link.u('uWidth'), d.lineWidth);
    gl.uniform1f(link.u('uStrength'), 1);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, linkCount);
    gl.bindVertexArray(null);
    spark.use();
    gl.uniform4fv(spark.u('uSparks'), new Float32Array(d.sparks));
    quads(d.sparks.length / 4);
    snapshot('neural', `${d.nodes.length / 2} nodes, ${linkCount} links, ${d.sparks.length / 4} sparks, ${d.bokeh.length / 4} bokeh;`);
  }

  // --- VELOCITY (scene/velocity/VelocityRenderer.kt) ------------------------------------
  function velocity(d) {
    const panel = program('velocity_panel.vert', 'velocity_panel.frag');
    const light = program('velocity_light.vert', 'velocity_light.frag');
    const flare = program('velocity_flare.vert', 'velocity_flare.frag');
    const bg = program('fullscreen.vert', 'velocity_bg.frag');
    // makeTile.
    const tile = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tile);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, 1, d.tile.length, 0, gl.RED, gl.UNSIGNED_BYTE, new Uint8Array(d.tile));
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.REPEAT);
    // setUniforms.
    const t = d.tones;
    panel.use();
    gl.uniform2f(panel.u('uTargetPx'), W, H);
    gl.uniform1f(panel.u('uDensity'), d.density);
    gl.uniform2f(panel.u('uScreen'), d.dp[0], d.dp[1]);
    gl.uniform1f(panel.u('uScreenH'), d.dp[1]);
    set3(panel.u('uPale'), t.pale);
    set3(panel.u('uRed'), t.red);
    gl.uniform1f(panel.u('uSoft'), d.soft);
    gl.uniform1f(panel.u('uStriation'), d.striation);
    gl.uniform1i(panel.u('uTile'), 0);
    light.use();
    gl.uniform2f(light.u('uTargetPx'), W, H);
    gl.uniform1f(light.u('uDensity'), d.density);
    gl.uniform2f(light.u('uScreen'), d.dp[0], d.dp[1]);
    gl.uniform1i(light.u('uLineCount'), d.lines.length / 4);
    set3(light.u('uPale'), t.pale);
    set3(light.u('uRed'), t.red);
    gl.uniform1f(light.u('uGlow'), d.g);
    flare.use();
    gl.uniform2f(flare.u('uTargetPx'), W, H);
    gl.uniform1f(flare.u('uDensity'), d.density);
    gl.uniform3f(flare.u('uBloom'), ...d.bloom);
    gl.uniform3f(flare.u('uArc'), ...d.arc);
    gl.uniform2f(flare.u('uArcSpan'), ...d.span);
    gl.uniform1f(flare.u('uArcHalf'), d.arcHalf);
    gl.uniform1f(flare.u('uArcAlpha'), d.arcAlpha);
    set3(flare.u('uPale'), t.pale);
    set3(flare.u('uFlare'), t.flare);
    gl.uniform4f(flare.u('uArcBox'), ...d.arcBox);
    bg.use();
    set3(bg.u('uBg'), d.colors.bg);
    set3(bg.u('uCool'), d.colors.cool);
    set3(bg.u('uAccent'), d.colors.accent);
    // render.
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    gl.viewport(0, 0, W, H);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE);
    panel.use();
    gl.uniform4fv(panel.u('uPanels'), new Float32Array(d.panels));
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, tile);
    quads(d.panels.length / 4);
    light.use();
    gl.uniform4fv(light.u('uLines'), new Float32Array(d.lines));
    const ns = d.streaks.length / 4;
    if (ns > 0) {
      gl.uniform4fv(light.u('uStreaks'), new Float32Array(d.streaks));
      gl.uniform4fv(light.u('uStreakLook'), new Float32Array(d.looks));
    }
    quads(d.lines.length / 4 + ns);
    flare.use();
    gl.uniform1f(flare.u('uStrength'), d.strength);
    quads(2);
    gl.blendFunc(gl.ONE_MINUS_DST_ALPHA, gl.ONE);
    bg.use();
    fullScreen();
    gl.disable(gl.BLEND);
    snapshot('velocity', `${d.panels.length / 4} panels, ${ns} streaks;`);
  }

  for (const [name, fn] of [['rain', rain], ['neural', neural], ['velocity', velocity]]) {
    try {
      fn(dumps[name]);
    } catch (e) {
      problems.push(`${name}: ${e.message}`);
    }
  }
  if (unused.length) notes.unused = `uniforms the compiler dropped (unused: harmless): ${unused.join(', ')}`;
  return { problems, images, notes, renderer };
};
