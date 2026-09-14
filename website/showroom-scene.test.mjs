import assert from 'node:assert/strict';
import test from 'node:test';
import { createKeyboardScene } from './scene.js';
import { SKINS } from './showroom.js';

class Target {
  constructor() { this.events = new Map(); this.dataset = {}; this.children = []; this.removed = false; }
  addEventListener(name, fn) { if (!this.events.has(name)) this.events.set(name, new Set()); this.events.get(name).add(fn); }
  removeEventListener(name, fn) { this.events.get(name)?.delete(fn); }
  emit(name, event = {}) { [...(this.events.get(name) ?? [])].forEach(fn => fn(event)); }
  setAttribute() {}
  append(child) { this.children.push(child); }
  remove() { this.removed = true; }
  listeners() { return [...this.events.values()].reduce((sum, set) => sum + set.size, 0); }
}
function setup(t, { failRender = false } = {}) {
  const document = new Target(), window = new Target(), motion = new Target(), connection = new Target(), host = new Target();
  document.hidden = false; document.baseURI = 'https://example.test/'; motion.matches = false; connection.saveData = false;
  window.matchMedia = () => motion; window.document = document;
  const canvases = [], images = [], frames = new Map(), observers = [], sizeObservers = [], layouts = [], keys = [];
  let nextFrame = 0, ready = 0, failures = 0;
  document.createElement = () => {
    const canvas = new Target();
    canvas.context = { images: [], labels: [], paints: 0, clearRect() { this.paints++; this.images = []; this.labels = []; }, save() {}, restore() {}, beginPath() {}, roundRect() {}, clip() {}, fillRect() {}, stroke() {},
      drawImage(image) { this.images.push(image); }, fillText(text) { this.labels.push({ text, ink: this.fillStyle }); } };
    canvas.getContext = () => canvas.context; canvases.push(canvas); return canvas;
  };
  host.rect = { width: 1000, height: 1000 / 2.15, left: 0, top: 0 }; host.getBoundingClientRect = () => host.rect;
  const values = { document, window, navigator: { connection }, devicePixelRatio: 3,
    requestAnimationFrame: fn => { frames.set(++nextFrame, fn); return nextFrame; }, cancelAnimationFrame: id => frames.delete(id),
    Image: class { constructor() { this.naturalWidth = this.naturalHeight = 1024; images.push(this); } },
    IntersectionObserver: class { constructor(fn) { this.fn = fn; observers.push(this); } observe() {} disconnect() { this.disconnected = true; } },
    ResizeObserver: class { constructor(fn) { this.fn = fn; sizeObservers.push(this); } observe() {} disconnect() { this.disconnected = true; } },
  };
  const previous = new Map(Object.keys(values).map(key => [key, Object.getOwnPropertyDescriptor(globalThis, key)]));
  for (const [key, value] of Object.entries(values)) Object.defineProperty(globalThis, key, { configurable: true, writable: true, value });
  const renderer = { domElement: new Target(), shadowMap: {}, capabilities: { getMaxAnisotropy: () => 16 }, disposed: 0, renders: 0,
    setPixelRatio(value) { this.pixelRatio = value; }, setSize() {},
    render(scene, camera) { this.scene = scene; this.camera = camera; if (failRender) throw Error('GPU stopped'); this.renders++; }, dispose() { this.disposed++; } };
  const api = createKeyboardScene(host, { interactive: true, rendererFactory: () => renderer,
    onReady: () => ready++, onFail: () => failures++, onLayout: layout => layouts.push(layout), onKey: key => keys.push(key) });
  t.after(() => { api.dispose(); for (const [key, descriptor] of previous) { if (descriptor) Object.defineProperty(globalThis, key, descriptor); else delete globalThis[key]; } });
  const flush = (now = performance.now() + 500) => { const pending = [...frames.values()]; frames.clear(); pending.forEach(fn => fn(now)); };
  return { api, host, document, window, connection, motion, renderer, canvases, images, frames, layouts, keys, observers, sizeObservers, flush, ready: () => ready, failures: () => failures };
}
test('real Three geometry projects all 33 native key targets inside desktop and mobile views', t => {
  const a = setup(t); assert.equal(a.ready(), 0); a.flush(); assert.equal(a.ready(), 1); assert.equal(a.renderer.pixelRatio, 1.5);
  for (const [width, aspect] of [[1000, 2.15], [600, 1.62], [378, 1.62], [304, 1.55]]) {
    a.host.rect = { width, height: width / aspect, left: 0, top: 0 }; a.sizeObservers[0].fn(); a.flush();
    const layout = a.layouts.at(-1); assert.equal(layout.length, 33); assert.equal(new Set(layout.map(key => key.id)).size, 33);
    for (const key of layout) { assert.ok(key.x >= 0 && key.y >= 0 && key.x + key.width <= 100 && key.y + key.height <= 100, `${width}px ${key.id}: ${JSON.stringify(key)}`); assert.ok(key.width * width / 100 >= 24, `${width}px ${key.id} hit width`); }
  }
  assert.equal(a.frames.size, 0, 'idle scene must not keep an animation loop');
});
test('last selected atlas wins even when cancelled image callbacks arrive late', t => {
  const a = setup(t); a.flush(); a.api.setSkin(SKINS.taffy); const old = a.images[0], oldLoad = old.onload;
  a.api.setSkin(SKINS.raiden); const latest = a.images[1]; assert.equal(old.onload, null); oldLoad();
  assert.equal(a.host.dataset.textureState, 'loading'); latest.onload(); assert.equal(a.host.dataset.textureState, 'ready');
  const drawn = a.canvases.flatMap(canvas => canvas.context.images); assert.ok(drawn.includes(latest)); assert.ok(!drawn.includes(old));
  a.api.setSkin(SKINS.blue); assert.equal(a.host.dataset.textureState, 'ready'); assert.equal(a.canvases.flatMap(canvas => canvas.context.images).length, 0);
});
test('changing case and language repaints legends without recreating geometry', t => {
  const a = setup(t); a.flush(); const scene = a.renderer.scene;
  a.api.setSkin(SKINS.graphite); a.api.setState({ uppercase: false, chinese: false });
  assert.equal(a.canvases[0].context.labels[0].text, 'q');
  const enter = a.canvases.at(-1).context.labels[0]; assert.equal(enter.ink, '#17232b');
  a.api.setState({ uppercase: true }); assert.equal(a.canvases[0].context.labels[0].text, 'Q'); a.flush(); assert.equal(a.renderer.scene, scene);
});
test('unchanged editor state schedules no frame, canvas repaint, or texture upload', t => {
  const a = setup(t); a.flush();
  const textures = []; a.renderer.scene.traverse(object => { if (object.material?.map) textures.push(object.material.map); });
  const initialVersions = textures.map(texture => texture.version), initialPaints = a.canvases.map(canvas => canvas.context.paints);
  for (let key = 0; key < 66; key++) a.api.setState({ uppercase: true, chinese: true });
  a.api.setState({});
  assert.deepEqual(a.canvases.map(canvas => canvas.context.paints), initialPaints);
  assert.deepEqual(textures.map(texture => texture.version), initialVersions);
  assert.equal(a.frames.size, 0);
  a.api.setState({ uppercase: false });
  assert.equal(a.frames.size, 1); assert.equal(a.canvases[0].context.labels[0].text, 'q');
  assert.ok(textures.every((texture, i) => texture.version > initialVersions[i]));
  a.flush(); const changedPaints = a.canvases.map(canvas => canvas.context.paints);
  a.api.setState({ uppercase: false }); a.api.setState({ chinese: true });
  assert.deepEqual(a.canvases.map(canvas => canvas.context.paints), changedPaints); assert.equal(a.frames.size, 0);
  a.api.setSkin(SKINS.blue); assert.equal(a.frames.size, 1, 'skin changes still repaint unchanged legends');
});
test('offscreen and background cancel frames and defer optional atlas downloads', t => {
  const a = setup(t); a.flush(); a.observers[0].fn([{ isIntersecting: false }]); a.api.setSkin(SKINS.taffy); a.api.press('Q');
  assert.equal(a.frames.size, 0); assert.equal(a.images.length, 0);
  a.observers[0].fn([{ isIntersecting: true }]); assert.equal(a.images.length, 1); a.flush();
  a.document.hidden = true; a.document.emit('visibilitychange'); a.api.press('W'); assert.equal(a.frames.size, 0);
  a.document.hidden = false; a.document.emit('visibilitychange'); a.flush(); assert.equal(a.frames.size, 0);
});
test('missing illustration remains a working solid keyboard', t => {
  const a = setup(t); a.flush(); a.api.setSkin(SKINS.taffy); a.images[0].onerror();
  assert.equal(a.host.dataset.textureState, 'failed'); assert.equal(a.failures(), 0); a.api.press('Q'); a.flush(); assert.equal(a.renderer.disposed, 0);
});
test('dispose releases every unique GPU resource once and detaches all callbacks', t => {
  const a = setup(t); a.flush(); a.api.setSkin(SKINS.taffy); const pending = a.images[0], late = pending.onload;
  const resources = new Set(); a.renderer.scene.traverse(object => { if (object.geometry) resources.add(object.geometry); if (object.material) { resources.add(object.material); if (object.material.map) resources.add(object.material.map); } });
  const counts = new Map(); resources.forEach(resource => resource.addEventListener('dispose', () => counts.set(resource, (counts.get(resource) ?? 0) + 1)));
  a.api.dispose(); a.api.dispose(); late(); a.api.setSkin(SKINS.kun); a.api.press('Q');
  assert.equal(counts.size, resources.size); assert.ok([...counts.values()].every(count => count === 1)); assert.equal(a.renderer.disposed, 1);
  assert.equal(a.frames.size, 0); assert.equal(pending.onload, null); assert.equal(pending.onerror, null); assert.ok(a.renderer.domElement.removed);
  assert.ok(a.observers[0].disconnected && a.sizeObservers[0].disconnected); assert.equal(a.document.listeners() + a.window.listeners() + a.motion.listeners() + a.connection.listeners() + a.renderer.domElement.listeners(), 0);
});
test('first-frame render failure disposes the renderer without claiming WebGL readiness', t => {
  const a = setup(t, { failRender: true }); a.flush(); assert.equal(a.ready(), 0); assert.equal(a.failures(), 1); assert.equal(a.renderer.disposed, 1); assert.equal(a.frames.size, 0);
});
test('case drag turns only slightly, updates projected targets, and never types on drag release', t => {
  const a = setup(t); a.flush(); const before = a.layouts.at(-1)[0];
  a.renderer.domElement.emit('pointerdown', { clientX: 10, clientY: 10, pointerType: 'mouse' });
  a.renderer.domElement.emit('pointermove', { clientX: 110, clientY: 40, pointerType: 'mouse' }); a.flush();
  const after = a.layouts.at(-1)[0]; assert.notEqual(after.x, before.x);
  a.renderer.domElement.emit('pointerup'); a.renderer.domElement.emit('click', { clientX: 110, clientY: 40 }); a.flush();
  assert.equal(a.keys.length, 0); assert.deepEqual(a.layouts.at(-1)[0], before); assert.equal(a.frames.size, 0);
});
