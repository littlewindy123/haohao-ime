import assert from 'node:assert/strict';
import test from 'node:test';
import { createStudioRenderer, SKINS } from './showroom.js';

const tick = () => new Promise(resolve => setImmediate(resolve));
class Target {
  constructor() { this.events = new Map(); this.dataset = {}; this.classes = new Set(); this.classList = { add: name => this.classes.add(name), remove: name => this.classes.delete(name), contains: name => this.classes.has(name) }; }
  addEventListener(name, fn) { if (!this.events.has(name)) this.events.set(name, new Set()); this.events.get(name).add(fn); }
  removeEventListener(name, fn) { this.events.get(name)?.delete(fn); }
  emit(name) { [...(this.events.get(name) ?? [])].forEach(fn => fn()); }
  listeners() { return [...this.events.values()].reduce((sum, set) => sum + set.size, 0); }
}
function setup({ reduced = false, saveData = false, forcedColors = false, deferred = false, fail = false } = {}) {
  const host = new Target(), document = new Target(), environment = new Target(), motion = new Target(), colors = new Target(), connection = new Target();
  Object.assign(motion, { matches: reduced }); Object.assign(colors, { matches: forcedColors }); Object.assign(connection, { saveData });
  document.hidden = false; environment.document = document; environment.navigator = { connection };
  environment.matchMedia = query => query.includes('forced-colors') ? colors : motion;
  let intersect, imports = 0, release, selected = SKINS.taffy, state = { uppercase: false, chinese: true };
  const instances = [], keys = [], layouts = [];
  environment.IntersectionObserver = class { constructor(fn) { intersect = fn; } observe() {} disconnect() {} };
  const module = { createKeyboardScene(_host, options) {
    const scene = { options, skins: [], states: [], presses: [], disposed: 0,
      setSkin(skin) { this.skins.push(skin); }, setState(value) { this.states.push(value); }, press(key) { this.presses.push(key); }, dispose() { this.disposed++; } };
    instances.push(scene); return scene;
  } };
  const api = createStudioRenderer(host, {
    environment, getSkin: () => selected, getState: () => state,
    onKey: key => keys.push(key), onLayout: layout => layouts.push(layout),
    loadScene: async () => { imports++; if (fail) throw Error('No WebGL'); return deferred ? new Promise(resolve => { release = () => resolve(module); }) : module; },
  });
  return { api, host, document, environment, motion, colors, connection, instances, keys, layouts,
    imports: () => imports, release: () => release(), visible: value => intersect([{ isIntersecting: value }]),
    skin: value => { selected = value; api.setSkin(); }, state: value => { state = value; api.setState(); } };
}
test('Three imports only near the viewport and uses the latest selected skin and mode', async () => {
  const a = setup({ deferred: true }); assert.equal(a.imports(), 0);
  a.visible(true); a.skin(SKINS.raiden); a.skin(SKINS.yasuo); a.state({ uppercase: true, chinese: false });
  assert.equal(a.imports(), 1); a.release(); await tick();
  const scene = a.instances[0]; assert.equal(scene.skins.at(-1), SKINS.yasuo); assert.deepEqual(scene.states.at(-1), { uppercase: true, chinese: false });
  assert.equal(a.host.dataset.renderer, 'loading'); scene.options.onLayout([{ id: 'Q' }]); scene.options.onReady();
  assert.equal(a.host.dataset.renderer, 'webgl'); scene.options.onKey('Q'); a.api.press('Q');
  assert.deepEqual(a.keys, ['Q']); assert.deepEqual(scene.presses, ['Q']); assert.equal(a.layouts.length, 1); a.api.dispose();
});
test('reduced motion, forced colors and save-data do not import Three', async () => {
  for (const options of [{ reduced: true }, { saveData: true }, { forcedColors: true }]) {
    const a = setup(options); a.visible(true); await tick(); assert.equal(a.imports(), 0); assert.equal(a.host.dataset.renderer, 'dom'); a.api.dispose();
  }
});
test('background or offscreen during import never installs an obsolete scene', async () => {
  for (const hidden of [true, false]) {
    const a = setup({ deferred: true }); a.visible(true);
    if (hidden) a.document.hidden = true; else a.visible(false);
    a.release(); await tick(); assert.equal(a.instances.length, 0);
    if (hidden) { a.document.hidden = false; a.document.emit('visibilitychange'); } else a.visible(true);
    a.release(); await tick(); assert.equal(a.instances.length, 1); a.api.dispose();
  }
});
test('preference changes release the old renderer and last selection survives restoration', async () => {
  const a = setup(); a.visible(true); await tick(); const first = a.instances[0]; first.options.onReady();
  a.connection.saveData = true; a.connection.emit('change'); assert.equal(first.disposed, 1); assert.equal(a.host.dataset.renderer, 'dom');
  assert.equal(a.api.artworkAllowed(), false); a.skin(SKINS.kun);
  a.connection.saveData = false; a.connection.emit('change'); await tick();
  assert.equal(a.instances.length, 2); assert.equal(a.instances[1].skins.at(-1), SKINS.kun);
  first.options.onReady(); first.options.onKey('Q'); assert.equal(a.keys.length, 0); assert.equal(a.host.dataset.renderer, 'loading'); a.api.dispose();
});
test('pagehide invalidates pending imports and bfcache restoration creates one renderer', async () => {
  const a = setup({ deferred: true }); a.visible(true); a.environment.emit('pagehide'); a.release(); await tick();
  assert.equal(a.instances.length, 0); a.environment.emit('pageshow'); a.release(); await tick(); assert.equal(a.instances.length, 1);
  a.environment.emit('pagehide'); assert.equal(a.instances[0].disposed, 1); a.environment.emit('pageshow'); a.release(); await tick(); assert.equal(a.instances.length, 2); a.api.dispose();
});
test('failure preserves the DOM keyboard without repeated imports', async () => {
  const a = setup({ fail: true }); a.visible(true); await tick(); assert.equal(a.host.dataset.renderer, 'dom');
  a.skin(SKINS.blue); a.visible(false); a.visible(true); await tick(); assert.equal(a.imports(), 1); a.api.dispose();
});
test('context loss falls back and stale callbacks cannot mutate a newer renderer', async () => {
  const a = setup(); a.visible(true); await tick(); const first = a.instances[0]; first.options.onReady(); first.options.onFail();
  assert.equal(first.disposed, 1); assert.equal(a.host.dataset.renderer, 'dom');
  first.options.onReady(); first.options.onKey('Q'); assert.equal(a.keys.length, 0); assert.equal(a.host.dataset.renderer, 'dom'); a.api.dispose();
});
test('dispose is idempotent, detaches listeners and rejects delayed module completion', async () => {
  const a = setup({ deferred: true }); a.visible(true); a.api.dispose(); a.api.dispose(); a.release(); await tick();
  assert.equal(a.instances.length, 0); assert.equal(a.document.listeners() + a.environment.listeners() + a.motion.listeners() + a.colors.listeners() + a.connection.listeners(), 0);
  a.environment.emit('pageshow'); a.visible(true); await tick(); assert.equal(a.imports(), 1);
});
