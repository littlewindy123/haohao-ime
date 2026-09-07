import * as THREE from 'three';
import { RoundedBoxGeometry } from 'three/addons/geometries/RoundedBoxGeometry.js';
import { KEY_ROWS, DECALS } from './keyboard-model.mjs';

// Complete front-facing board; letter legends are independent of the illustration atlas.
export function createKeyboardScene(host, { onReady, onFail, onKey, interactive = false } = {}) {
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: 'low-power' });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.shadowMap.enabled = true; renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.domElement.className = 'keyboard-canvas'; renderer.domElement.setAttribute('aria-hidden', 'true'); host.append(renderer.domElement);
  const scene = new THREE.Scene(), camera = new THREE.OrthographicCamera(-6.2, 6.2, 4, -4, .1, 50);
  camera.position.set(0, interactive ? 12 : 14, interactive ? 9.5 : 6.4); camera.lookAt(0, .25, 0);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x918b82, 2.3));
  const sun = new THREE.DirectionalLight(0xfffaf0, 2.8); sun.position.set(-4, 9, 5); sun.castShadow = true;
  sun.shadow.mapSize.set(1024, 1024); Object.assign(sun.shadow.camera, { left: -8, right: 8, top: 7, bottom: -7 }); sun.shadow.normalBias = .035; scene.add(sun);
  const group = new THREE.Group(); scene.add(group);
  // The hero stays fixed. Only the skin showroom tilts, within a bounded view.
  const homeTilt = 0;
  group.rotation.x = homeTilt;
  let tiltTween;
  function tilt(x, z) {
    if (!interactive || disposed || !visible || document.hidden) return;
    tiltTween?.kill();
    if (window.gsap) tiltTween = window.gsap.to(group.rotation, { x, z, duration: .35, ease: 'power2.out', onUpdate: invalidate });
    else { group.rotation.set(x, 0, z); invalidate(); }
  }
  const materials = [], surfaces = [], keyMeshes = [];
  function mat(color) { const m = new THREE.MeshStandardMaterial({ color, roughness: .65 }); materials.push(m); return m; }
  const keyMat = mat('#f7f2e5'), functionMat = mat('#a1c6b1'), enterMat = mat('#eab65d'), baseMat = mat('#c7c9bc');
  function box(w, h, d, material, x, y, z) {
    const mesh = new THREE.Mesh(new RoundedBoxGeometry(w, h, d, 3, .10), material);
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
      mesh.userData.id = key.id; keyMeshes.push(mesh);
      const canvas = document.createElement('canvas'); canvas.width = Math.round(key.w * 200); canvas.height = 200;
      const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace;
      const faceMat = new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 });
      const face = new THREE.Mesh(new THREE.PlaneGeometry(key.w * .9, .87), faceMat); face.rotation.x = -Math.PI / 2; face.position.y = .235; mesh.add(face);
      surfaces.push({ key, canvas, texture });
    });
  });
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(30, 30), new THREE.ShadowMaterial({ opacity: .13 })); shadow.rotation.x = -Math.PI / 2; shadow.position.y = -.025; shadow.receiveShadow = true; scene.add(shadow);
  let disposed = false, visible = true, frame = 0, pressed, pressStart = 0, revision = 0, pendingImage;
  let palette = { colors: ['#f7f2e5', '#a1c6b1', '#eab65d', '#c7c9bc'], ink: '#453b32' };
  function paint(image) {
    surfaces.forEach(({ key, canvas, texture }) => {
      const ctx = canvas.getContext('2d'), w = canvas.width, h = canvas.height, tile = DECALS[key.id];
      ctx.clearRect(0, 0, w, h);
      if (image && tile !== undefined) {
        const size = image.naturalWidth / 2, height = image.naturalHeight / 2;
        ctx.save(); ctx.beginPath(); ctx.roundRect(0, 0, w, h, 18); ctx.clip();
        ctx.fillStyle = palette.colors[key.id === 'ENTER' ? 2 : 0]; ctx.fillRect(0, 0, w, h);
        if (key.id === 'SPACE') {
          ctx.globalAlpha = .22; ctx.drawImage(image, size, height, size, height, 0, 0, w, h); ctx.globalAlpha = 1;
          ctx.drawImage(image, 0, 0, size, height, (w - h * 1.3) / 2, 0, h * 1.3, h);
        } else {
          ctx.globalAlpha = key.id.length > 1 ? .32 : 1;
          ctx.drawImage(image, (tile % 2) * size, Math.floor(tile / 2) * height, size, height, 0, 0, w, h);
        }
        ctx.restore();
      }
      const text = key.label ?? key.id;
      ctx.fillStyle = palette.ink; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
      ctx.font = (text.length > 3 ? 36 : 80) + 'px "Segoe UI", sans-serif';
      if (image && tile !== undefined && key.id.length === 1) {
        ctx.fillStyle = palette.colors[0]; ctx.fillRect(5, 5, 54, 61); ctx.fillStyle = palette.ink; ctx.font = '46px "Segoe UI", sans-serif'; ctx.fillText(text, 32, 37);
      } else ctx.fillText(text, w / 2, h / 2 + 2);
      texture.needsUpdate = true;
    }); invalidate();
  }
  function render(now = performance.now()) {
    frame = 0; if (disposed || !visible || document.hidden) return;
    if (pressed) { const t = Math.min(1, (now - pressStart) / 170); pressed.position.y = .69 - Math.sin(t * Math.PI) * .15; if (t === 1) pressed = undefined; }
    renderer.render(scene, camera); host.dataset.renderedFrames = String(Number(host.dataset.renderedFrames || 0) + 1);
    if (pressed) invalidate();
  }
  function invalidate() { if (!frame && !disposed && visible && !document.hidden) frame = requestAnimationFrame(render); }
  function resize() {
    const { width, height } = host.getBoundingClientRect(); if (!width || !height) return;
    const aspect = width / height, halfW = Math.max(6.05, 3 * aspect), halfH = halfW / aspect;
    Object.assign(camera, { left: -halfW, right: halfW, top: halfH, bottom: -halfH }); camera.updateProjectionMatrix(); renderer.setSize(width, height, false); invalidate();
  }
  function press(id) { if (pressed) pressed.position.y = .69; pressed = keyMeshes.find(k => k.userData.id === id.toUpperCase()); host.dataset.lastKey = id.toUpperCase(); pressStart = performance.now(); invalidate(); }
  function pause() { tiltTween?.kill(); cancelAnimationFrame(frame); frame = 0; if (!document.hidden) invalidate(); }
  const ray = new THREE.Raycaster();
  let drag;
  function down(event) { if (interactive && event.isPrimary !== false) drag = { x: event.clientX, y: event.clientY, moved: false }; }
  function move(event) {
    if (!interactive) return;
    const r = host.getBoundingClientRect();
    if (drag) {
      const dx = event.clientX - drag.x, dy = event.clientY - drag.y;
      if (Math.abs(dx) > 7) drag.moved = true;
      if (event.pointerType === 'touch' && Math.abs(dy) > Math.abs(dx)) return;
      tilt(homeTilt + Math.max(-.08, Math.min(.10, dy / r.height * .3)), Math.max(-.13, Math.min(.13, dx / r.width * .5)));
    } else if (event.pointerType === 'mouse') tilt(homeTilt + (event.clientY-r.top-r.height/2)/r.height*.08, -(event.clientX-r.left-r.width/2)/r.width*.12);
  }
  function leave() { drag = undefined; tilt(homeTilt, 0); }
  function up() { if (drag?.moved) tilt(homeTilt, 0); }
  function click(event) { if (!interactive) return; if (drag?.moved) { drag = undefined; return; } drag = undefined; const r = host.getBoundingClientRect(); scene.updateMatrixWorld(true); ray.setFromCamera(new THREE.Vector2((event.clientX-r.left)/r.width*2-1, -(event.clientY-r.top)/r.height*2+1), camera); const hit = ray.intersectObjects(keyMeshes, false)[0]; if (hit) { press(hit.object.userData.id); onKey?.(hit.object.userData.id); } }
  function lost(event) { event.preventDefault(); dispose(); onFail?.(); }
  renderer.domElement.addEventListener('click', click); renderer.domElement.addEventListener('webglcontextlost', lost);
  const pointerEvents = { pointerdown: down, pointermove: move, pointerleave: leave, pointerup: up, pointercancel: leave };
  if (interactive) for (const [name, handler] of Object.entries(pointerEvents)) renderer.domElement.addEventListener(name, handler);
  document.addEventListener('visibilitychange', pause);
  const observer = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; pause(); }); observer.observe(host);
  const sizeObserver = new ResizeObserver(resize); sizeObserver.observe(host);
  function dispose() {
    if (disposed) return; disposed = true; revision++; tiltTween?.kill(); cancelAnimationFrame(frame); observer.disconnect(); sizeObserver.disconnect();
    for (const [name, handler] of Object.entries(pointerEvents)) renderer.domElement.removeEventListener(name, handler);
    if (pendingImage) { pendingImage.onload = null; pendingImage.onerror = null; pendingImage = undefined; }
    document.removeEventListener('visibilitychange', pause); renderer.domElement.removeEventListener('click', click); renderer.domElement.removeEventListener('webglcontextlost', lost);
    const mats = new Set(materials); scene.traverse(o => { o.geometry?.dispose(); if (o.material) mats.add(o.material); }); mats.forEach(m => m.dispose()); surfaces.forEach(s => s.texture.dispose()); renderer.dispose(); renderer.domElement.remove();
  }
  paint(); resize(); onReady?.();
  return { press, dispose, setSkin(skin) {
    const token = ++revision; palette = skin;
    if (pendingImage) { pendingImage.onload = null; pendingImage.onerror = null; pendingImage = undefined; }
    [keyMat, functionMat, enterMat, baseMat].forEach((m, i) => m.color.set(skin.colors[i]));
    paint(); host.dataset.textureState = skin.atlas ? 'loading' : 'ready';
    if (!skin.atlas) return;
    const image = new Image(); pendingImage = image;
    image.onload = () => { if (!disposed && token === revision) { paint(image); host.dataset.textureState = 'ready'; pendingImage = undefined; } };
    image.onerror = () => { if (!disposed && token === revision) { host.dataset.textureState = 'failed'; onFail?.(); } };
    image.src = new URL('assets/' + skin.atlas, document.baseURI).href;
  } };
}
