/**
 * tools/shader-check/render.js: runs in the page. Draws the seven scenes
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

  /**
   * gl/ScaledTarget.kt: at [scale] < 1 the scene draws into a smaller frame,
   * scaled up to the screen after; at 1 straight to the screen.
   */
  const upscale = program('fullscreen.vert', 'upscale.frag');
  function scaledTarget(scale) {
    if (scale >= 0.999) return { width: W, height: H, begin: () => { gl.bindFramebuffer(gl.FRAMEBUFFER, null); gl.viewport(0, 0, W, H); }, end: () => {} };
    const width = Math.round(W * scale);
    const height = Math.round(H * scale);
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, width, height);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const fbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, tex, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) problems.push('scaled target incomplete');
    gl.bindFramebuffer(gl.FRAMEBUFFER, null);
    upscale.use();
    gl.uniform1i(upscale.u('uFrame'), 0);
    return {
      width,
      height,
      begin: () => {
        gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
        gl.viewport(0, 0, width, height);
      },
      end: () => {
        gl.bindFramebuffer(gl.FRAMEBUFFER, null);
        gl.viewport(0, 0, W, H);
        gl.disable(gl.BLEND);
        gl.clear(gl.COLOR_BUFFER_BIT);
        upscale.use();
        gl.activeTexture(gl.TEXTURE0);
        gl.bindTexture(gl.TEXTURE_2D, tex);
        fullScreen();
      },
    };
  }

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
  function rain(d, name) {
    const A = d.atlas;
    const [LW, LH] = d.layer;
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
    gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, LW, LH);
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
    const fade = program('rain_fade.vert', 'rain_fade.frag');
    const period = d.fadePhases * d.fadeStripe;
    const perSet = Math.ceil(LH / period);
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
    gl.uniform2f(ops.u('uTarget'), LW, LH);
    gl.uniform2fv(ops.u('uPitch'), new Float32Array(d.pitch));
    gl.uniform2fv(ops.u('uCell'), new Float32Array([0, 1, 2].flatMap((l) => [A.cellW[l], A.cellH[l]])));
    gl.uniform1fv(ops.u('uOrigin'), new Float32Array(A.originY));
    gl.uniform4f(ops.u('uPads'), A.padHead, A.padBody, A.padErase, A.padSlot);
    gl.uniform2f(ops.u('uAtlas'), A.width, A.height);
    gl.uniform1i(ops.u('uPerRow'), A.perRow);
    set3(ops.u('uBright'), d.colors.textBright);
    set3(ops.u('uBody'), d.body);
    set3(ops.u('uDim'), d.dim);
    set3(ops.u('uGlowColor'), d.colors.accent);
    gl.uniform1f(ops.u('uGlowAlpha'), d.glow.alpha);
    gl.uniform1f(ops.u('uHeadLayers'), d.glow.headLayers);
    gl.uniform1i(ops.u('uGlyphs'), 0);
    // drawFades' uniforms.
    fade.use();
    gl.uniform2f(fade.u('uTarget'), LW, LH);
    gl.uniform1i(fade.u('uPerSet'), perSet);
    gl.uniform1f(fade.u('uStripe'), d.fadeStripe);
    gl.uniform1f(fade.u('uPeriod'), period);
    gl.uniform1f(fade.u('uFade'), d.fadeAlpha);
    comp.use();
    set3(comp.u('uBg'), d.colors.bg);
    set3(comp.u('uAccent'), d.colors.accent);
    gl.uniform1i(comp.u('uRain'), 0);

    /** beginLayer, the frame's fades (drawFades), then its records in order (the phone draws them in batches of 4096: the same picture). */
    const draw = (records, fades = []) => {
      gl.bindFramebuffer(gl.FRAMEBUFFER, fbo);
      gl.viewport(0, 0, LW, LH);
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      if (fades.length) {
        fade.use();
        gl.uniform1iv(fade.u('uPhases'), new Int32Array(fades));
        quads(fades.length * perSet);
      }
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
    snapshot(`${name}-open`, `${d.quality}: layer ${LW}×${LH}; ${d.prewarm.length / 4} records painted the opening screen;`);
    let records = 0;
    for (const f of d.frames) {
      draw(f.records, f.fades);
      records += f.records.length / 4;
    }
    compose();
    snapshot(name, `${d.quality}: then ${d.frames.length} frames, ${Math.round(records / d.frames.length)} records each;`);
  }

  // --- NEURAL NET (scene/neural/NeuralRenderer.kt) --------------------------------------
  function neural(d, low = false, name = low ? 'neural-low' : 'neural') {
    const g = d.glow;
    const c = d.colors;
    const t = scaledTarget(low ? d.lowScale : 1);
    const px = d.density * (low ? d.lowScale : 1);
    const sparks = low ? d.sparksLow : d.sparks;
    const bg = program('fullscreen.vert', 'neural_bg.frag');
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
    for (const p of [link, spark]) {
      p.use();
      gl.uniform2f(p.u('uTargetPx'), t.width, t.height);
      gl.uniform1f(p.u('uDensity'), px);
      gl.uniform2f(p.u('uScreen'), d.dp[0], d.dp[1]);
      set3(p.u('uCool'), d.coolLifted);
      set3(p.u('uBright'), c.textBright);
      set3(p.u('uAccent'), c.accent);
    }
    link.use();
    gl.uniform1f(link.u('uMaxLink'), d.maxLink);
    spark.use();
    gl.uniform1f(spark.u('uEdge'), low ? d.edgeLow : d.edge);
    gl.uniform1f(spark.u('uHalo'), g.halo);
    // render.
    t.begin();
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    bg.use();
    fullScreen();
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    link.use();
    gl.uniform4fv(link.u('uNodes'), new Float32Array(d.nodes));
    gl.bindVertexArray(linkVao);
    if (g.lineGlow > 0 && !low) {
      gl.uniform1f(link.u('uWidth'), d.lineWidth * d.glowWidth);
      gl.uniform1f(link.u('uStrength'), g.lineGlow);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, linkCount);
    }
    gl.uniform1f(link.u('uWidth'), d.lineWidth);
    gl.uniform1f(link.u('uStrength'), 1);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, linkCount);
    gl.bindVertexArray(null);
    spark.use();
    gl.uniform4fv(spark.u('uSparks'), new Float32Array(sparks));
    quads(sparks.length / 4);
    t.end();
    snapshot(name, `${low ? `LOW: drawn ${t.width}×${t.height}, no link glow; ` : ''}${d.nodes.length / 2} nodes, ${linkCount} links, ${sparks.length / 4} sparks`);
  }

  // --- VELOCITY (scene/velocity/VelocityRenderer.kt) ------------------------------------
  function velocity(d, low = false, name = low ? 'velocity-low' : 'velocity') {
    const target = scaledTarget(low ? d.lowScale : 1);
    const px = d.density * (low ? d.lowScale : 1);
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
    gl.uniform2f(panel.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(panel.u('uDensity'), px);
    gl.uniform2f(panel.u('uScreen'), d.dp[0], d.dp[1]);
    gl.uniform1f(panel.u('uScreenH'), d.dp[1]);
    set3(panel.u('uPale'), t.pale);
    set3(panel.u('uRed'), t.red);
    gl.uniform1f(panel.u('uSoft'), d.soft);
    gl.uniform1f(panel.u('uStriation'), d.striation);
    gl.uniform1i(panel.u('uTile'), 0);
    light.use();
    gl.uniform2f(light.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(light.u('uDensity'), px);
    gl.uniform2f(light.u('uScreen'), d.dp[0], d.dp[1]);
    gl.uniform1i(light.u('uLineCount'), d.lines.length / 4);
    set3(light.u('uPale'), t.pale);
    set3(light.u('uRed'), t.red);
    gl.uniform1f(light.u('uGlow'), d.g);
    flare.use();
    gl.uniform2f(flare.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(flare.u('uDensity'), px);
    gl.uniform3f(flare.u('uBloom'), ...d.bloom);
    gl.uniform3f(flare.u('uArc'), ...d.arc);
    gl.uniform2f(flare.u('uArcSpan'), ...d.span);
    gl.uniform1f(flare.u('uArcHalf'), d.arcHalf);
    gl.uniform1f(flare.u('uArcAlpha'), d.arcAlpha);
    set3(flare.u('uPale'), t.pale);
    set3(flare.u('uFlare'), t.flare);
    gl.uniform4f(flare.u('uArcBox'), ...(low ? d.arcBoxLow : d.arcBox));
    bg.use();
    set3(bg.u('uBg'), d.colors.bg);
    set3(bg.u('uCool'), d.colors.cool);
    set3(bg.u('uAccent'), d.colors.accent);
    // render.
    target.begin();
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
    target.end();
    snapshot(name, `${low ? `LOW: drawn ${target.width}×${target.height}; ` : ''}${d.panels.length / 4} panels, ${ns} streaks;`);
  }

  // --- DATA FLOW (scene/flow/FlowRenderer.kt) -------------------------------------------
  function flow(d, low = false, name = low ? 'flow-low' : 'flow') {
    const target = scaledTarget(low ? d.lowScale : 1);
    const px = d.density * (low ? d.lowScale : 1);
    const t = d.tones;
    const c = d.colors;
    const bg = program('fullscreen.vert', 'flow_bg.frag');
    const seg = program('flow_seg.vert', 'flow_seg.frag');
    const mark = program('flow_mark.vert', 'flow_mark.frag');
    const chip = program('flow_chip.vert', 'flow_chip.frag');
    /** Instances of [stride] floats, attributes [sizes] floats each in order (gl/Gl.kt StaticInstances, InstanceStream). */
    const instances = (data, stride, sizes) => {
      const vao = gl.createVertexArray();
      const buf = gl.createBuffer();
      gl.bindVertexArray(vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, buf);
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data.length ? data : [0]), gl.STATIC_DRAW);
      let offset = 0;
      sizes.forEach((size, loc) => {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, stride * 4, offset * 4);
        gl.vertexAttribDivisor(loc, 1);
        offset += size;
      });
      gl.bindVertexArray(null);
      return { vao, count: data.length / stride };
    };
    const draw = (inst) => {
      if (!inst.count) return;
      gl.bindVertexArray(inst.vao);
      gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, inst.count);
      gl.bindVertexArray(null);
    };
    // sendBoard (LOW: the glow without the board's soft glow or the packets' halos).
    const boardSegs = instances(low ? d.boardSegsLow : d.boardSegs, 16, [4, 4, 4, 4]);
    const boardMarks = instances(d.boardMarks, 6, [4, 2]);
    const boardChips = instances(d.boardChips, 6, [4, 2]);
    const l = low ? d.lightLow : d.light;
    const lightSegs = instances(l.segs, 16, [4, 4, 4, 4]);
    const lightMarks = instances(l.marks, 6, [4, 2]);
    const lightChips = instances(l.chips, 6, [4, 2]);
    // setUniforms.
    bg.use();
    set3(bg.u('uBg'), c.bg);
    set3(bg.u('uCool'), c.cool);
    set3(bg.u('uAccent'), c.accent);
    set3(bg.u('uDot'), t.dot);
    gl.uniform2f(bg.u('uScreen'), d.dp[0], d.dp[1]);
    gl.uniform1f(bg.u('uDensity'), px);
    gl.uniform1f(bg.u('uPitch'), d.pitch);
    gl.uniform2f(bg.u('uMain'), d.main[0], d.main[1]);
    gl.uniform1f(bg.u('uFar'), d.far);
    seg.use();
    gl.uniform2f(seg.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(seg.u('uDensity'), px);
    mark.use();
    gl.uniform2f(mark.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(mark.u('uDensity'), px);
    set3(mark.u('uBg'), c.bg);
    set3(mark.u('uWarmVia'), t.warmVia);
    set3(mark.u('uCoolVia'), t.coolVia);
    set3(mark.u('uWarmLight'), t.warmLight);
    set3(mark.u('uCoolLight'), t.coolLight);
    set3(mark.u('uWarmHead'), t.warmHead);
    set3(mark.u('uCoolHead'), t.coolHead);
    chip.use();
    gl.uniform2f(chip.u('uTargetPx'), target.width, target.height);
    gl.uniform1f(chip.u('uDensity'), px);
    gl.uniform1f(chip.u('uPitch'), d.pitch);
    set3(chip.u('uBody'), t.chipBody);
    set3(chip.u('uEdge'), t.chipEdge);
    set3(chip.u('uDie'), t.die);
    set3(chip.u('uLight'), t.warmLight);
    // render: 1. the board's background; 2. the board, drawn over; 3. the light, added.
    target.begin();
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    bg.use();
    fullScreen();
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    seg.use();
    draw(boardSegs);
    mark.use();
    draw(boardMarks);
    chip.use();
    gl.uniform1i(chip.u('uPass'), 0);
    draw(boardChips);
    gl.blendFunc(gl.ONE, gl.ONE);
    gl.uniform1i(chip.u('uPass'), 1);
    draw(lightChips);
    seg.use();
    draw(lightSegs);
    mark.use();
    draw(lightMarks);
    gl.disable(gl.BLEND);
    target.end();
    snapshot(
      name,
      `${low ? `LOW: drawn ${target.width}×${target.height}, no soft glow or halos; ` : ''}${d.traces} traces (${boardSegs.count} stretches), ${d.pulses} packets (${lightSegs.count} lit stretches), ${d.flashes} flashes, ${lightChips.count} chips lit;`,
    );
  }

  // --- MEGACITY (scene/megacity/MegacityRenderer.kt) -------------------------------------
  function megacity(d, low = false, name = low ? 'megacity-low' : 'megacity') {
    const target = scaledTarget(low ? d.lowScale : 1);
    const px = d.density * (low ? d.lowScale : 1);
    const t = d.tones;
    const city = program('mc_full.vert', 'mc_city.frag');
    const sign = program('mc_sign.vert', 'mc_sign.frag');
    const glow = program('mc_glow.vert', 'mc_glow.frag');
    const shape = program('mc_shape.vert', 'mc_shape.frag');
    // sendSkyline: RGBA float, read with texelFetch.
    const skyline = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, skyline);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 2048, d.rows * d.layers, 0, gl.RGBA, gl.FLOAT, new Float32Array(d.skyline));
    /** A buffer and vertex array: attributes `sizes` floats each, one per instance or per vertex. */
    const vertices = (data, sizes, perInstance) => {
      const vao = gl.createVertexArray();
      gl.bindVertexArray(vao);
      gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
      gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(data.length ? data : [0]), gl.STATIC_DRAW);
      const stride = sizes.reduce((a, b) => a + b, 0);
      let offset = 0;
      sizes.forEach((size, loc) => {
        gl.enableVertexAttribArray(loc);
        gl.vertexAttribPointer(loc, size, gl.FLOAT, false, stride * 4, offset * 4);
        if (perInstance) gl.vertexAttribDivisor(loc, 1);
        offset += size;
      });
      gl.bindVertexArray(null);
      return { vao, count: data.length / stride };
    };
    const signs = vertices(d.signs, [4, 4, 4], true);
    const glows = vertices(d.glows, [4, 4, 4], true);
    const fg = vertices(d.foreground, [2, 4], false);
    // setUniforms.
    city.use();
    gl.uniform1i(city.u('uSkyline'), 0);
    gl.uniform1i(city.u('uLayers'), d.layers);
    gl.uniform1i(city.u('uRows'), d.rows);
    gl.uniform1i(city.u('uCols'), d.cols);
    gl.uniform2f(city.u('uTarget'), target.width, target.height);
    gl.uniform1f(city.u('uDensity'), px);
    gl.uniform1f(city.u('uHeight'), d.height);
    gl.uniform2fv(city.u('uCell'), new Float32Array(d.cells));
    gl.uniform1fv(city.u('uFog'), new Float32Array(d.fogs));
    for (const k of ['skyTop', 'skyMid', 'skyLow', 'lowGlow', 'faceFar', 'faceNear', 'edge', 'winPale', 'winViolet', 'winPink', 'winWhite']) {
      gl.uniform3fv(city.u('u' + k[0].toUpperCase() + k.slice(1)), new Float32Array(t[k]));
    }
    gl.uniform1f(city.u('uWindowGlow'), low ? 0 : d.windowGlow);
    for (const p of [sign, glow, shape]) {
      p.use();
      gl.uniform2f(p.u('uTarget'), target.width, target.height);
      gl.uniform1f(p.u('uDensity'), px);
    }
    // The city's cache (resizeCache, drawCity): the target's size, the clock in whole seconds.
    const second = Math.floor(d.time);
    const cache = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, cache);
    gl.texStorage2D(gl.TEXTURE_2D, 1, gl.RGBA8, target.width, target.height);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const cacheFbo = gl.createFramebuffer();
    gl.bindFramebuffer(gl.FRAMEBUFFER, cacheFbo);
    gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, cache, 0);
    if (gl.checkFramebufferStatus(gl.FRAMEBUFFER) !== gl.FRAMEBUFFER_COMPLETE) problems.push('megacity: the city cache is incomplete');
    /** The city straight into what's bound (as before the cache), at the clock `time`. */
    const drawCity = (time) => {
      gl.clearColor(0, 0, 0, 0);
      gl.clear(gl.COLOR_BUFFER_BIT);
      gl.disable(gl.BLEND);
      city.use();
      gl.uniform1f(city.u('uTime'), time);
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, skyline);
      fullScreen();
    };
    /** The rest of the frame over the city: signs, glows, the foreground; then the scale-up. */
    const lights = () => {
      gl.enable(gl.BLEND);
      gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
      for (const [p, v] of [[sign, signs], [glow, glows]]) {
        if (!v.count) continue;
        p.use();
        gl.bindVertexArray(v.vao);
        gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, v.count);
      }
      shape.use();
      gl.bindVertexArray(fg.vao);
      gl.drawArrays(gl.TRIANGLES, 0, fg.count);
      gl.bindVertexArray(null);
      gl.disable(gl.BLEND);
      target.end();
    };
    /** The screen's pixels now. */
    const screen = () => {
      const px = new Uint8Array(W * H * 4);
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.readPixels(0, 0, W, H, gl.RGBA, gl.UNSIGNED_BYTE, px);
      return px;
    };
    // First as before the cache (the city drawn in the frame, at the same whole second), to compare.
    target.begin();
    drawCity(second);
    lights();
    const direct = screen();
    // render: the city into its cache, then copied (upscale.frag at one to one), then the rest.
    gl.bindFramebuffer(gl.FRAMEBUFFER, cacheFbo);
    gl.viewport(0, 0, target.width, target.height);
    drawCity(second);
    target.begin();
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    upscale.use();
    gl.uniform1i(upscale.u('uFrame'), 0);
    gl.activeTexture(gl.TEXTURE0);
    gl.bindTexture(gl.TEXTURE_2D, cache);
    fullScreen();
    lights();
    const cached = screen();
    let worst = 0;
    for (let i = 0; i < cached.length; i++) worst = Math.max(worst, Math.abs(cached[i] - direct[i]));
    if (worst > 1) problems.push(`${name}: the cached city differs from the city drawn in the frame by up to ${worst}/255`);
    snapshot(name, `${low ? `LOW: drawn ${target.width}×${target.height}, no window halo; ` : ''}${d.cols} columns × ${d.layers} layers, ${signs.count} signs, ${glows.count} glows, ${fg.count} foreground vertices; the city from its cache (second ${second}), within ${worst}/255 of drawing it in the frame;`);
  }

  // --- HORIZON (scene/horizon/HorizonRenderer.kt) ---------------------------------------
  function horizon(d, low = false, name = low ? 'horizon-low' : 'horizon') {
    const target = scaledTarget(low ? d.lowScale : 1);
    const v = low ? d.low : d.full;
    const t = d.tones;
    const sky = program('hz_full.vert', 'hz_sky.frag');
    const land = program('hz_land.vert', 'hz_land.frag');
    // sendMesh: per vertex (x, height), then (strip, row in it, depth offset, corner).
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(d.mesh), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 24, 0);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 24, 8);
    gl.bindVertexArray(null);
    // setUniforms.
    for (const p of [sky, land]) {
      p.use();
      gl.uniform2f(p.u('uTarget'), target.width, target.height);
      gl.uniform2f(p.u('uSun'), v.sun[0], v.sun[1]);
      gl.uniform1f(p.u('uGlowR'), v.glowR);
      gl.uniform2f(p.u('uDome'), v.dome[0], v.dome[1]);
      gl.uniform3fv(p.u('uSkyTop'), new Float32Array(t.skyTop));
      gl.uniform3fv(p.u('uSkyLow'), new Float32Array(t.skyLow));
      gl.uniform3fv(p.u('uGlow'), new Float32Array(t.glow));
      gl.uniform1f(p.u('uCoreR'), v.coreR);
      gl.uniform3fv(p.u('uCore'), new Float32Array(t.core));
    }
    land.use();
    gl.uniform1f(land.u('uDensity'), v.density);
    gl.uniform1f(land.u('uFocal'), v.focal);
    gl.uniform1f(land.u('uHorizon'), v.horizon);
    gl.uniform1f(land.u('uCamH'), d.camH);
    gl.uniform1f(land.u('uNear'), d.near);
    gl.uniform1f(land.u('uFar'), d.far);
    gl.uniform1f(land.u('uRows'), d.rows);
    gl.uniform1f(land.u('uCell'), d.cell);
    gl.uniform3fv(land.u('uLine'), new Float32Array(t.line));
    gl.uniform3fv(land.u('uFace'), new Float32Array(t.face));
    gl.uniform1f(land.u('uLineW'), d.lineW);
    gl.uniform1f(land.u('uGlowW'), d.glowW);
    gl.uniform1f(land.u('uGlowA'), d.glowA);
    gl.uniform1f(land.u('uSoftNear'), d.softNear);
    gl.uniform1f(land.u('uSoften'), d.soften);
    gl.uniform1f(land.u('uFogStart'), d.fogStart);
    gl.uniform1f(land.u('uFogEnd'), d.fogEnd);
    // render: the sky over the top half, then the land: the wall, the strips far end first, back faces dropped, no depth buffer.
    const w = target.width;
    const h = target.height;
    target.begin();
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    gl.enable(gl.SCISSOR_TEST);
    const skyFrom = Math.max(0, Math.floor(h * (1 - d.horizonShare)) - 1);
    gl.scissor(0, skyFrom, w, h - skyFrom);
    sky.use();
    fullScreen();
    gl.scissor(0, 0, w, Math.ceil(h * (1 - d.horizonShare)) + 1);
    gl.enable(gl.CULL_FACE);
    land.use();
    gl.uniform1f(land.u('uCamZ'), d.camZ);
    gl.bindVertexArray(vao);
    gl.drawArrays(gl.TRIANGLES, 0, d.wallVertices);
    // HorizonDraw.nearStrip / farFirst.
    const near = Math.max(0, Math.ceil(d.camZ / d.cell - 1 + 1e-6) % d.rows);
    const per = d.stripVertices;
    if (near > 0) gl.drawArrays(gl.TRIANGLES, d.wallVertices + (d.rows - near) * per, near * per);
    gl.drawArrays(gl.TRIANGLES, d.wallVertices, (d.rows - near) * per);
    gl.bindVertexArray(null);
    gl.disable(gl.CULL_FACE);
    gl.disable(gl.SCISSOR_TEST);
    target.end();
    snapshot(name, `${low ? `LOW: drawn ${w}×${h}; ` : ''}${d.rows} rows × ${d.cols - 1} quads (cell ${d.cell.toFixed(3)}), ${(d.mesh.length / 6) | 0} vertices;`);
  }

  // --- SUNSET (scene/sunset/SunsetRenderer.kt) -------------------------------------------
  function sunset(d, low = false, name = low ? 'sunset-low' : 'sunset') {
    const target = scaledTarget(low ? d.lowScale : 1);
    const v = low ? d.low : d.full;
    const t = d.tones;
    const sky = program('ss_full.vert', 'ss_sky.frag');
    const ground = program('ss_full.vert', 'ss_ground.frag');
    // setUniforms.
    for (const p of [sky, ground]) {
      p.use();
      gl.uniform2f(p.u('uTarget'), target.width, target.height);
      gl.uniform1f(p.u('uUnit'), v.unit);
      gl.uniform1f(p.u('uHorizon'), v.horizon);
      gl.uniform1f(p.u('uScanEvery'), d.scanEvery);
      gl.uniform1f(p.u('uScanDepth'), d.scanDepth);
      gl.uniform2f(p.u('uSun'), v.sun[0], v.sun[1]);
      gl.uniform1f(p.u('uSunR'), v.sunR);
      gl.uniform1f(p.u('uGlowR'), v.glowR);
      gl.uniform3fv(p.u('uSkyLeft'), new Float32Array(t.skyLeft));
      gl.uniform3fv(p.u('uSkyRight'), new Float32Array(t.skyRight));
      gl.uniform3fv(p.u('uGlow'), new Float32Array(t.glow));
    }
    sky.use();
    gl.uniform3fv(sky.u('uSkyTop'), new Float32Array(t.skyTop));
    gl.uniform3fv(sky.u('uSunTop'), new Float32Array(t.sunTop));
    gl.uniform3fv(sky.u('uSunBase'), new Float32Array(t.sunBase));
    gl.uniform3fv(sky.u('uStar'), new Float32Array(t.star));
    gl.uniform3fv(sky.u('uStripe'), new Float32Array(d.stripe));
    gl.uniform1f(sky.u('uGapGrow'), d.gapGrow);
    gl.uniform1f(sky.u('uStarCell'), d.starCell);
    gl.uniform1f(sky.u('uStarChance'), d.starChance);
    ground.use();
    gl.uniform1f(ground.u('uFocal'), v.focal);
    gl.uniform1f(ground.u('uCamH'), v.camH);
    gl.uniform1f(ground.u('uLine'), d.line);
    gl.uniform1f(ground.u('uGlowW'), d.glowW);
    gl.uniform3fv(ground.u('uWave'), new Float32Array(d.wave));
    gl.uniform1f(ground.u('uDrift'), d.drift);
    gl.uniform1f(ground.u('uMeltH'), v.melt);
    gl.uniform3fv(ground.u('uGround'), new Float32Array(t.ground));
    gl.uniform3fv(ground.u('uWarm'), new Float32Array(t.warm));
    gl.uniform3fv(ground.u('uCool'), new Float32Array(t.cool));
    // render: the sky above the horizon, the ground below it.
    const w = target.width;
    const h = target.height;
    target.begin();
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    gl.enable(gl.SCISSOR_TEST);
    const groundRows = Math.ceil(h * (1 - d.horizonShare));
    const skyFrom = Math.max(0, Math.floor(h * (1 - d.horizonShare)) - 1);
    gl.scissor(0, skyFrom, w, h - skyFrom);
    sky.use();
    fullScreen();
    gl.scissor(0, 0, w, groundRows);
    ground.use();
    gl.uniform1f(ground.u('uTravel'), d.travel);
    fullScreen();
    gl.disable(gl.SCISSOR_TEST);
    target.end();
    snapshot(name, `${low ? `LOW: drawn ${w}×${h}; ` : ''}camera ${v.camH.toFixed(2)} up, sun r ${v.sunR.toFixed(0)} px;`);
  }

  // --- PRISM (scene/prism/PrismRenderer.kt) --------------------------------------------
  // `lite`: the blocks drawn with the phone's lighter pair (prism_lite.*, as at LOW and MEDIUM) on its finer cube (d.gridCube).
  function prism(d, low = false, name = low ? 'prism-low' : 'prism', lite = low) {
    const target = scaledTarget(low ? d.lowScale : 1);
    const t = d.tones;
    const back = program('pr_full.vert', 'pr_back.frag');
    const box = lite ? program('prism_lite.vert', 'prism_lite.frag') : program('pr_box.vert', 'pr_box.frag');
    // build: the cube per vertex (corner, normal), the blocks per instance (min corner, size), in back-to-front order.
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    const cube = lite ? d.gridCube : d.cube;
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(cube), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 24, 0);
    gl.enableVertexAttribArray(1);
    gl.vertexAttribPointer(1, 3, gl.FLOAT, false, 24, 12);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array(d.boxes), gl.STREAM_DRAW);
    gl.enableVertexAttribArray(2);
    gl.vertexAttribPointer(2, 3, gl.FLOAT, false, 24, 0);
    gl.vertexAttribDivisor(2, 1);
    gl.enableVertexAttribArray(3);
    gl.vertexAttribPointer(3, 3, gl.FLOAT, false, 24, 12);
    gl.vertexAttribDivisor(3, 1);
    gl.bindVertexArray(null);
    // render: what's behind the glass, then the blocks, premultiplied.
    target.begin();
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    gl.disable(gl.BLEND);
    back.use();
    gl.uniform3fv(back.u('uBg'), new Float32Array(t.navy));
    gl.uniform3fv(back.u('uAccent'), new Float32Array(t.magenta));
    gl.uniform3fv(back.u('uCool'), new Float32Array(t.blue));
    gl.uniform2f(back.u('uAspect'), d.aspect, 1);
    gl.uniform1f(back.u('uLight'), d.light);
    fullScreen();
    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    box.use();
    gl.uniformMatrix4fv(box.u('uViewProj'), false, new Float32Array(d.viewProj));
    gl.uniform4fv(box.u('uLightPos'), new Float32Array(d.lightPos));
    gl.uniform3fv(box.u('uLightCol'), new Float32Array(d.lightCol));
    gl.uniform1i(box.u('uLights'), d.lights);
    gl.uniform3fv(box.u('uEye'), new Float32Array(d.eye));
    gl.uniform3fv(box.u('uBody'), new Float32Array(t.navy));
    gl.uniform3fv(box.u('uEdge'), new Float32Array(t.edge));
    gl.uniform1f(box.u('uAlpha'), d.alpha);
    gl.uniform1f(box.u('uFresnel'), d.fresnel);
    gl.uniform1f(box.u('uDensity'), low ? d.densityLow : d.density);
    gl.uniform1f(box.u('uEdgeW'), d.edgeW);
    gl.uniform1f(box.u('uEdgeGlow'), d.edgeGlow);
    gl.uniform1f(box.u('uGlowAmt'), d.glowAmt);
    gl.uniform1f(box.u('uFar'), d.far);
    gl.bindVertexArray(vao);
    gl.drawArraysInstanced(gl.TRIANGLES, 0, cube.length / 6, d.count);
    gl.bindVertexArray(null);
    gl.disable(gl.BLEND);
    target.end();
    snapshot(name, `${low ? `LOW: drawn ${target.width}×${target.height}; ` : ''}${lite ? 'the lighter shaders; ' : ''}${d.count} blocks, ${d.lights} lights;`);
  }

  // Each scene at full resolution, then at LOW (scaled up: gl/ScaledTarget.kt, the rain's own layer).
  const runs = [
    ['rain', () => rain(dumps.rain, 'rain')],
    ['rain-low', () => rain(dumps['rain-low'], 'rain-low')],
    ['neural', () => neural(dumps.neural)],
    ['neural-low', () => neural(dumps.neural, true)],
    ['velocity', () => velocity(dumps.velocity)],
    ['velocity-low', () => velocity(dumps.velocity, true)],
    ['flow', () => flow(dumps.flow)],
    ['flow-low', () => flow(dumps.flow, true)],
    // ZOOM: each laid out for a screen 1/zoom as big, drawn zoom times as large (the dumps carry it).
    ['rain-zoom', () => rain(dumps['rain-zoom'], 'rain-zoom')],
    ['neural-zoom', () => neural(dumps['neural-zoom'], false, 'neural-zoom')],
    ['velocity-zoom', () => velocity(dumps['velocity-zoom'], false, 'velocity-zoom')],
    ['flow-zoom', () => flow(dumps['flow-zoom'], false, 'flow-zoom')],
    ['flow-zoom-out', () => flow(dumps['flow-zoom-out'], false, 'flow-zoom-out')],
    ['megacity', () => megacity(dumps.megacity)],
    ['megacity-low', () => megacity(dumps.megacity, true)],
    ['megacity-zoom', () => megacity(dumps['megacity-zoom'], false, 'megacity-zoom')],
    ['horizon', () => horizon(dumps.horizon)],
    ['horizon-low', () => horizon(dumps.horizon, true)],
    ['horizon-zoom', () => horizon(dumps['horizon-zoom'], false, 'horizon-zoom')],
    ['sunset', () => sunset(dumps.sunset)],
    ['sunset-low', () => sunset(dumps.sunset, true)],
    ['sunset-zoom', () => sunset(dumps['sunset-zoom'], false, 'sunset-zoom')],
    ['prism', () => prism(dumps.prism)],
    ['prism-low', () => prism(dumps.prism, true)],
    // Full resolution with the lighter pair: compared with 'prism', it should match to within a step of colour.
    ['prism-lite', () => prism(dumps.prism, false, 'prism-lite', true)],
    ['prism-zoom', () => prism(dumps['prism-zoom'], false, 'prism-zoom')],
  ];
  for (const [name, fn] of runs) {
    try {
      fn();
    } catch (e) {
      problems.push(`${name}: ${e.message}`);
    }
  }
  if (unused.length) notes.unused = `uniforms the compiler dropped (unused: harmless): ${unused.join(', ')}`;
  return { problems, images, notes, renderer };
};
