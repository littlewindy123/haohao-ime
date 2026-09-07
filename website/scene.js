import * as THREE from "three";
import { RoundedBoxGeometry } from "three/addons/geometries/RoundedBoxGeometry.js";

// A small, procedural product study. No models, external textures or requests.
export function createKeyboardScene(host, { onReady, onFail, onKey } = {}) {
  const renderer = new THREE.WebGLRenderer({ alpha: true, antialias: true, powerPreference: "low-power" });
  renderer.setPixelRatio(Math.min(devicePixelRatio, 1.5));
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = 1;
  renderer.shadowMap.enabled = true;
  renderer.shadowMap.type = THREE.PCFShadowMap;
  renderer.domElement.setAttribute("aria-hidden", "true");
  renderer.domElement.className = "keyboard-canvas";
  host.append(renderer.domElement);
  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(34, 1, .1, 60);
  camera.position.set(8.3, 10.8, 12.8);
  camera.lookAt(0, .4, 0);
  scene.add(new THREE.HemisphereLight(0xffffff, 0x9c9589, 2.2));
  const light = new THREE.DirectionalLight(0xfff4dd, 3.2);
  light.position.set(-4, 9, 5);
  light.castShadow = true;
  light.shadow.mapSize.set(1024, 1024);
  light.shadow.camera.left = -9; light.shadow.camera.right = 9;
  light.shadow.camera.top = 8; light.shadow.camera.bottom = -8;
  light.shadow.normalBias = .03;
  light.shadow.bias = -.0003;
  light.shadow.radius = 4;
  scene.add(light);
  const rim = new THREE.DirectionalLight(0xd1e9df, 1.5);
  rim.position.set(6, 3, -5); scene.add(rim);
  const group = new THREE.Group();
  group.rotation.y = -.09;
  scene.add(group);
  const materials = [];
  const material = (color, roughness = .55) => {
    const value = new THREE.MeshStandardMaterial({ color, roughness, metalness: .06 });
    materials.push(value); return value;
  };
  const ivory = material(0xf7f2e5), honey = material(0xeab65d), mint = material(0xa1c6b1);
  const base = material(0xc7c9bc), paper = material(0xfdf8e9);
  function box(w, h, d, radius, mat, x, y, z, parent = group) {
    const mesh = new THREE.Mesh(new RoundedBoxGeometry(w, h, d, 3, radius), mat);
    mesh.position.set(x, y, z); mesh.castShadow = true; mesh.receiveShadow = true; parent.add(mesh); return mesh;
  }
  box(8.6, .44, 4.9, .22, base, -.1, .22, .25);
  box(8.5, .16, 4.8, .12, ivory, -.1, .52, .25);
  const keyMeshes = [], textures = [];
  function label(text, mesh, w, d, color = "#564635") {
    const canvas = document.createElement("canvas"); canvas.width = 256; canvas.height = 128;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = color; ctx.textAlign = "center"; ctx.textBaseline = "middle";
    ctx.font = `${text.length > 3 ? 30 : 54}px "Segoe UI", sans-serif`;
    ctx.fillText(text, 128, 67);
    const texture = new THREE.CanvasTexture(canvas); texture.colorSpace = THREE.SRGBColorSpace; textures.push(texture);
    const face = new THREE.Mesh(new THREE.PlaneGeometry(w * .88, d * .65), new THREE.MeshBasicMaterial({ map: texture, transparent: true, depthWrite: false, polygonOffset: true, polygonOffsetFactor: -1 }));
    face.rotation.x = -Math.PI / 2; face.position.y = .3; mesh.add(face);
  }
  function key(text, x, z, w = .89, mat = ivory) {
    const mesh = box(w, .57, .98, .13, mat, x, .97, z);
    mesh.userData.key = text; mesh.userData.restY = .97;
    label(text, mesh, w, .98); keyMeshes.push(mesh); return mesh;
  }
  ["QWERTYUI", "ASDFGHJK", "ZXCVBNM"].forEach((row, r) => {
    [...row].forEach((c, i) => key(c, (i - (row.length - 1) / 2) * 1.01 - .1, (r - 1) * 1.13 + .03, .89, c === "H" ? honey : c === "N" ? mint : ivory));
  });
  key("中 / EN", -2.85, 2.1, 2.05, mint);
  key("", -.18, 2.1, 2.98);
  key("Enter", 2.68, 2.1, 2.35, honey);
  // A small book rests beyond the board, echoing the site's wordbook story.
  const book = new THREE.Group(); group.add(book);
  book.position.set(3.9, .9, -3.3); book.rotation.y = -.26;
  box(2.05, .18, 2.65, .08, mint, 0, 0, 0, book);
  box(1.9, .24, 2.48, .04, paper, .025, .19, -.01, book);
  box(2.05, .09, 2.65, .07, mint, 0, .36, 0, book);
  box(.09, .025, 2.6, .01, honey, -.63, .42, 0, book);
  const shadow = new THREE.Mesh(new THREE.PlaneGeometry(35, 35), new THREE.ShadowMaterial({ opacity: .09 }));
  shadow.rotation.x = -Math.PI / 2; shadow.position.y = -.08; shadow.receiveShadow = true; scene.add(shadow);
  const ray = new THREE.Raycaster(), pointer = new THREE.Vector2();
  let frame = 0, disposed = false, visible = true, targetX = 0, targetY = -.09, pressed = null, pressStart = 0, bookStart = 0;
  function render(now = performance.now()) {
    frame = 0;
    if (disposed || !visible || document.hidden) return;
    group.rotation.x += (targetX - group.rotation.x) * .12;
    group.rotation.y += (targetY - group.rotation.y) * .12;
    let moving = Math.abs(targetX - group.rotation.x) + Math.abs(targetY - group.rotation.y) > .0002;
    if (pressed) {
      const t = Math.min(1, (now - pressStart) / 360);
      pressed.position.y = pressed.userData.restY - Math.sin(t * Math.PI) * .2;
      if (t === 1) pressed = null; else moving = true;
    }
    if (bookStart) {
      const t = Math.min(1, (now - bookStart) / 650);
      book.position.y = .9 + Math.sin(t * Math.PI) * .32;
      if (t === 1) bookStart = 0; else moving = true;
    }
    renderer.render(scene, camera);
    if (moving) invalidate();
  }
  function invalidate() { if (!frame && !disposed && visible && !document.hidden) frame = requestAnimationFrame(render); }
  function resize() {
    const { width, height } = host.getBoundingClientRect();
    if (!width || !height) return;
    camera.aspect = width / height; camera.updateProjectionMatrix();
    renderer.setSize(width, height, false); invalidate();
  }
  function move(event) {
    if (event.pointerType !== "mouse") return;
    const rect = host.getBoundingClientRect();
    targetY = -.09 + ((event.clientX - rect.left) / rect.width - .5) * .13;
    targetX = ((event.clientY - rect.top) / rect.height - .5) * .07; invalidate();
  }
  function leave() { targetX = 0; targetY = -.09; invalidate(); }
  function click(event) {
    const rect = renderer.domElement.getBoundingClientRect();
    pointer.set((event.clientX - rect.left) / rect.width * 2 - 1, -(event.clientY - rect.top) / rect.height * 2 + 1);
    ray.setFromCamera(pointer, camera);
    const hit = ray.intersectObjects(keyMeshes, false)[0];
    if (hit) { press(hit.object.userData.key); onKey?.(hit.object.userData.key); }
  }
  function press(text = "H") {
    if (pressed) pressed.position.y = pressed.userData.restY;
    pressed = keyMeshes.find(k => k.userData.key === text.toUpperCase()) || keyMeshes.find(k => k.userData.key === "H");
    pressStart = performance.now(); invalidate();
  }
  function pause() { if (frame) cancelAnimationFrame(frame); frame = 0; if (!document.hidden) invalidate(); }
  function lost(event) { event.preventDefault(); dispose(); onFail?.(); }
  host.addEventListener("pointermove", move); host.addEventListener("pointerleave", leave);
  renderer.domElement.addEventListener("click", click); renderer.domElement.addEventListener("webglcontextlost", lost);
  document.addEventListener("visibilitychange", pause);
  const observer = typeof IntersectionObserver === "function" ? new IntersectionObserver(entries => {
    visible = entries[0].isIntersecting; pause();
  }, { rootMargin: "40px" }) : null;
  observer?.observe(host);
  const sizeObserver = new ResizeObserver(resize); sizeObserver.observe(host);
  function dispose() {
    if (disposed) return; disposed = true; cancelAnimationFrame(frame); frame = 0;
    observer?.disconnect(); sizeObserver.disconnect();
    host.removeEventListener("pointermove", move); host.removeEventListener("pointerleave", leave);
    document.removeEventListener("visibilitychange", pause);
    renderer.domElement.removeEventListener("click", click); renderer.domElement.removeEventListener("webglcontextlost", lost);
    const geometries = new Set(), mats = new Set(materials);
    scene.traverse(o => { if (o.geometry) geometries.add(o.geometry); if (o.material) mats.add(o.material); });
    geometries.forEach(g => g.dispose()); mats.forEach(m => m.dispose()); textures.forEach(t => t.dispose());
    renderer.dispose(); renderer.domElement.remove();
  }
  resize(); render(); onReady?.();
  return { press, collect() { bookStart = performance.now(); invalidate(); }, dispose };
}
