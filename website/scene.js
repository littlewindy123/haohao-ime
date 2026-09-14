import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { KEY_ROWS, DECALS, normalizeKeyboardSkin, keyFaceColor, readableKeyInk } from './keyboard-model.mjs';

// Complete front-facing board; letter legends are independent of the illustration atlas.
export function createKeyboardScene(host, { onReady, onFail, onKey, onLayout, interactive = false, rendererFactory = options => new THREE.WebGLRenderer(options) } = {}) {
  const renderer = rendererFactory({ alpha: true, antialias: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.domElement.className = 'keyboard-canvas'; renderer.domElement.setAttribute('aria-hidden', 'true'); host.append(renderer.domElement);
  const scene = new THREE.Scene(), camera = new THREE.OrthographicCamera(-6.2, 6.2, 4, -4, .1, 50);
  camera.position.set(0, 15, 7.4); camera.lookAt(0, .38, 0); camera.updateMatrixWorld();
  scene.add(new THREE.HemisphereLight(0xffffff, 0x918b82, 1.8));
  const sun = new THREE.DirectionalLight(0xfffaf0, 2.8); sun.position.set(-4, 9, 5); sun.castShadow = true;
  sun.shadow.mapSize.set(512, 512); Object.assign(sun.shadow.camera, { left: -8, right: 8, top: 7, bottom: -7 }); sun.shadow.normalBias = .035; scene.add(sun);
  const group = new THREE.Group(); scene.add(group);
  // Rest at a complete front view. Dragging the exposed case gives a small, bounded turn.
  const materials = [], surfaces = [], keyMeshes = [], geometryCache = new Map();
  function mat(color) { const m = new THREE.MeshStandardMaterial({ color, roughness: .8, metalness: .015 }); materials.push(m); return m; }
  const keyMat = mat('#f7f2e5'), functionMat = mat('#a1c6b1'), enterMat = mat('#eab65d'), baseMat = mat('#c7c9bc');
  function box(w, h, d, material, x, y, z) {
    const id = `${w}/${h}/${d}`;
    if (!geometryCache.has(id)) geometryCache.set(id, new RoundedBoxGeometry(w, h, d, 2, .09));
    const mesh = new THREE.Mesh(geometryCache.get(id), material);
    mesh.position.set(x, y, z); mesh.castShadow = true; mesh.receiveShadow = true; group.add(mesh); return mesh;
  }
  box(11.25, .32, 4.95, baseMat, 0, .16, 0);
  box(11.1, .12, 4.8, keyMat, 0, .37, 0);
  KEY_ROWS.forEach((row, r) => {
    const gap = .105, total = row.reduce((sum, k) => sum + k.w, 0) + (row.length - 1) * gap;
    let x = -total / 2;
    row.forEach(key => {
      const m = key.id === 'ENTER' ? enterMat : key.id.length > 1 && key.id !== 'SPACE' ? functionMat : keyMat;
      const mesh = box(key.w, .46, .98, m, x + key.w / 2, .69, (r - 1.5) * 1.13); x += key.w + gap;
      mesh.userData.id = key.id; mesh.userData.width = key.w; keyMeshes.push(mesh);
      const canvas = document.createElement('canvas'); canvas.width = Math.round(key.w * 200); canvas.height = 200;
      const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
      texture.anisotropy = Math.min(renderer.capabilities.getMaxAnisotropy(), 4);
      const faceMat = new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 });
      const face = new THREE.Mesh(new THREE.PlaneGeometry(key.w * .9, .87), faceMat); face.rotation.x = -Math.PI / 2; face.position.y = .235; mesh.add(face);
      surfaces.push({ key, canvas, texture });
    });
  });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(30, 30), new THREE.ShadowMaterial({ opacity: .13 })); shadow.rotation.x = -Math.PI / 2; shadow.position.y = -.025; shadow.receiveShadow = true; scene.add(shadow);
  let disposed = false, visible = true, frame = 0, pressed, pressStart = 0, revision = 0, pendingImage, atlasImage, ready = false, layoutDirty = true, rotationXTo, rotationZTo;
  let palette = normalizeKeyboardSkin(), uppercase = true, chinese = true;
  const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)');
  function paint(image = atlasImage) {
    if (disposed) return;
    surfaces.forEach(({ key, canvas, texture }) => {
      const ctx = canvas.getContext('2d'), w = canvas.width, h = canvas.height, tile = DECALS[key.id];
      ctx.clearRect(0, 0, w, h);
      if (image && tile !== undefined) {
        const size = image.naturalWidth / 2, height = image.naturalHeight / 2;
        ctx.save(); ctx.beginPath(); ctx.roundRect(0, 0, w, h, 18); ctx.clip();
        ctx.fillStyle = keyFaceColor(palette, key.id); ctx.fillRect(0, 0, w, h);
        if (key.id === 'SPACE') {
          ctx.globalAlpha = .22; ctx.drawImage(image, size, height, size, height, 0, 0, w, h); ctx.globalAlpha = 1;
          ctx.drawImage(image, 0, 0, size, height, (w - h * 1.3) / 2, 0, h * 1.3, h);
        } else {
          ctx.globalAlpha = key.id.length > 1 ? .16 : 1;
          ctx.drawImage(image, (tile % 2) * size, Math.floor(tile / 2) * height, size, height, 0, 0, w, h);
        }
        ctx.restore();
      }
      const text = /^[A-Z]$/.test(key.id) && !uppercase ? key.id.toLowerCase() : key.label ?? key.id;
      const ink = readableKeyInk(palette.ink, keyFaceColor(palette, key.id));
      ctx.fillStyle = ink; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.font = (text.length > 3 ? 36 : 80) + 'px "Segoe UI", sans-serif';
      if (image && tile !== undefined && key.id.length === 1) {
        ctx.fillStyle = palette.colors[0]; ctx.fillRect(5, 5, 54, 61); ctx.fillStyle = ink; ctx.font = '46px "Segoe UI", sans-serif'; ctx.fillText(text, 32, 37);
      } else ctx.fillText(text, w / 2, h / 2 + 2);
      if ((key.id === 'SHIFT' && uppercase && interactive) || (key.id === 'LANG' && !chinese)) {
        ctx.strokeStyle = ink; ctx.lineWidth = 5; ctx.beginPath(); ctx.roundRect(8, 8, w - 16, h - 16, 13); ctx.stroke();
      }
      texture.needsUpdate = true;
    }); invalidate();
  }
  function render(now = performance.now()) {
    frame = 0; if (disposed || !visible || document.hidden) return;
    if (pressed) { const t = Math.min(1, (now - pressStart) / 170); pressed.position.y = .69 - Math.sin(t * Math.PI) * .15; if (t === 1) pressed = undefined; }
    try {
      renderer.render(scene, camera);
      if (layoutDirty && onLayout) {
        scene.updateMatrixWorld(true);
        onLayout(keyMeshes.map(mesh => {
          const points = [-1, 1].flatMap(x => [-1, 1].map(z => mesh.localToWorld(new THREE.Vector3(x * mesh.userData.width / 2, .235, z * .49)).project(camera)));
          const xs = points.map(p => (p.x + 1) * 50), ys = points.map(p => (1 - p.y) * 50);
          return { id: mesh.userData.id, x: Math.min(...xs), y: Math.min(...ys), width: Math.max(...xs) - Math.min(...xs), height: Math.max(...ys) - Math.min(...ys) };
        }));
        layoutDirty = false;
      }
      host.dataset.renderedFrames = String(Number(host.dataset.renderedFrames || 0) + 1);
      if (!ready) { ready = true; onReady?.(); }
    } catch (error) { dispose(); onFail?.(error); return; }
    if (pressed) invalidate();
  }
  function invalidate() { if (!frame && !disposed && visible && !document.hidden) frame = requestAnimationFrame(render); }
  function resize() {
    if (disposed) return;
    const { width, height } = host.getBoundingClientRect(); if (!width || !height) return;
    group.scale.z = interactive && width < 600 ? 1.35 : 1;
    const aspect = width / height, halfW = Math.max(5.9, (interactive && width < 600 ? 3.35 : 2.55) * aspect), halfH = halfW / aspect;
    Object.assign(camera, { left: -halfW, right: halfW, top: halfH, bottom: -halfH }); camera.updateProjectionMatrix(); renderer.setSize(width, height, false); layoutDirty = true; invalidate();
  }
  function press(id) {
    if (disposed) return;
    if (pressed) pressed.position.y = .69;
    pressed = !motion?.matches && !navigator.connection?.saveData && visible && !document.hidden ? keyMeshes.find(k => k.userData.id === id.toUpperCase()) : undefined;
    host.dataset.lastKey = id.toUpperCase(); pressStart = performance.now(); invalidate();
  }
  function stopTurn() { rotationXTo?.tween.kill(); rotationZTo?.tween.kill(); rotationXTo = rotationZTo = undefined; group.rotation.set(0, 0, 0); layoutDirty = true; }
  function pause() { stopTurn(); cancelAnimationFrame(frame); frame = 0; if (pressed) { pressed.position.y = .69; pressed = undefined; } if (visible && !document.hidden) { requestAtlas(); invalidate(); } }
  function turn(x, z) {
    if (!interactive || disposed || !visible || document.hidden || motion?.matches || navigator.connection?.saveData) return;
    const update = () => { layoutDirty = true; invalidate(); };
    if (window.gsap?.quickTo) {
      rotationXTo ??= window.gsap.quickTo(group.rotation, 'x', { duration: .28, ease: 'power2.out', onUpdate: update });
      rotationZTo ??= window.gsap.quickTo(group.rotation, 'z', { duration: .28, ease: 'power2.out', onUpdate: update });
      rotationXTo(x); rotationZTo(z);
    } else { group.rotation.set(x, 0, z); update(); }
  }
  const ray = new THREE.Raycaster();
  let drag;
  function down(event) { if (interactive && event.isPrimary !== false) drag = { x: event.clientX, y: event.clientY, moved: false }; }
  function move(event) {
    if (!interactive) return;
    if (drag) {
      const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
      if (Math.hypot(dx, dy) > 7) drag.moved = true;
      if (event.pointerType === 'touch' && Math.abs(dy) > Math.abs(dx)) return;
      const rect = host.getBoundingClientRect(), bound = Math.PI / 90;
      turn(Math.max(-bound, Math.min(bound, dy / rect.height * .12)), Math.max(-bound, Math.min(bound, -dx / rect.width * .12)));
    }
  }
  function leave() { drag = undefined; turn(0, 0); }
  function up() { if (drag?.moved) turn(0, 0); }
  function click(event) { if (!interactive) return; if (drag?.moved) { drag = undefined; return; } drag = undefined; const r = host.getBoundingClientRect(); scene.updateMatrixWorld(true); ray.setFromCamera(new THREE.Vector2((event.clientX-r.left)/r.width*2-1, -(event.clientY-r.top)/r.height*2+1), camera); const hit = ray.intersectObjects(keyMeshes, false)[0]; if (hit) { press(hit.object.userData.id); onKey?.(hit.object.userData.id); } }
  function lost(event) { event.preventDefault(); dispose(); onFail?.(); }
  renderer.domElement.addEventListener('click', click); renderer.domElement.addEventListener('webglcontextlost', lost);
  const pointerEvents = { pointerdown: down, pointermove: move, pointerleave: leave, pointerup: up, pointercancel: leave };
  if (interactive) for (const [name, handler] of Object.entries(pointerEvents)) renderer.domElement.addEventListener(name, handler);
  document.addEventListener('visibilitychange', pause);
  motion?.addEventListener?.('change', pause);
  navigator.connection?.addEventListener?.('change', pause);
  const observer = typeof IntersectionObserver !== 'undefined' ? new IntersectionObserver(entries => { visible = entries[0].isIntersecting; pause(); }) : undefined; observer?.observe(host);
  const sizeObserver = typeof ResizeObserver !== 'undefined' ? new ResizeObserver(resize) : undefined; sizeObserver?.observe(host);
  if (!sizeObserver) window.addEventListener('resize', resize);
  function dispose() {
    if (disposed) return; disposed = true; revision++; stopTurn(); cancelAnimationFrame(frame); observer?.disconnect(); sizeObserver?.disconnect();
    window.removeEventListener('resize', resize);
    for (const [name, handler] of Object.entries(pointerEvents)) renderer.domElement.removeEventListener(name, handler);
    if (pendingImage) { pendingImage.onload = null; pendingImage.onerror = null; pendingImage = undefined; }
    atlasImage = undefined;
    motion?.removeEventListener?.('change', pause); navigator.connection?.removeEventListener?.('change', pause);
    document.removeEventListener('visibilitychange', pause); renderer.domElement.removeEventListener('click', click); renderer.domElement.removeEventListener('webglcontextlost', lost);
    const mats = new Set(materials), geometries = new Set(); scene.traverse(o => { if (o.geometry) geometries.add(o.geometry); if (o.material) mats.add(o.material); }); geometries.forEach(g => g.dispose()); mats.forEach(m => m.dispose()); surfaces.forEach(s => s.texture.dispose()); sun.shadow.dispose(); renderer.dispose(); renderer.domElement.remove();
  }
  function requestAtlas() {
    if (disposed || !visible || document.hidden || navigator.connection?.saveData || !palette.atlas || pendingImage || atlasImage || host.dataset.textureState === 'failed') return;
    const token = revision, image = new Image(); pendingImage = image; image.decoding = 'async'; host.dataset.textureState = 'loading';
    image.onload = () => { if (!disposed && token === revision) { atlasImage = image; paint(); host.dataset.textureState = 'ready'; pendingImage = undefined; } };
    // Keep solid, readable 3D keycaps if an optional illustration is unavailable.
    image.onerror = () => { if (!disposed && token === revision) { host.dataset.textureState = 'failed'; pendingImage = undefined; } };
    image.src = new URL('assets/' + palette.atlas, document.baseURI).href;
  }
  try { paint(); resize(); } catch (error) { dispose(); throw error; }
  return { press, dispose, setState(state) {
    if (disposed) return;
    const nextUppercase = state.uppercase ?? uppercase, nextChinese = state.chinese ?? chinese;
    // Ordinary typing does not change legends: avoid repainting/uploading all key textures.
    if (nextUppercase === uppercase && nextChinese === chinese) return;
    uppercase = nextUppercase; chinese = nextChinese; paint();
  }, setSkin(value) {
    if (disposed) return;
    revision++; palette = normalizeKeyboardSkin(value); atlasImage = undefined;
    if (pendingImage) { pendingImage.onload = null; pendingImage.onerror = null; pendingImage = undefined; }
    [keyMat, functionMat, enterMat, baseMat].forEach((m, i) => m.color.set(palette.colors[i]));
    paint(); host.dataset.textureState = palette.atlas ? 'pending' : 'ready'; requestAtlas();
  } };
}
