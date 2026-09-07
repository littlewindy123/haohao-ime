import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';
import vm from 'node:vm';
import * as model from './demo-model.mjs';

const source = (await readFile(new URL('./hero.js', import.meta.url), 'utf8'))
  .replace(/^import[^\n]+\n/, '')
  .replace('import("./vendor/scene-3d.min.js")', 'loadModule()');
class Element {
  constructor() {
    this.events = {}; this.attributes = {}; this.dataset = {}; this.disabled = false;
    const names = new Set();
    this.classList = { add: n => names.add(n), remove: n => names.delete(n), contains: n => names.has(n), toggle: (n, state) => state ? names.add(n) : names.delete(n) };
  }
  addEventListener(name, fn) { (this.events[name] ??= []).push(fn); }
  emit(name) { this.events[name]?.forEach(fn => fn()); }
  setAttribute(name, value) { this.attributes[name] = value; }
  querySelector() { return undefined; }
}
async function setup({ reduced = false, saveData = false, fail = false } = {}) {
  const ids = ['keyboard-scene', 'hero-study', 'hero-chinese', 'hero-english', 'collect-demo', 'saved-word', 'hero-status'];
  const elements = Object.fromEntries(ids.map(id => [id, new Element()]));
  elements['hero-chinese'].textContent = '你好'; elements['hero-english'].textContent = 'hello';
  const choices = ['nihao', 'xuexi', 'zhongwen'].map(value => { const el = new Element(); el.dataset.heroExample = value; return el; });
  const document = new Element(), window = new Element(), motion = new Element();
  document.hidden = false; motion.matches = reduced;
  document.querySelector = selector => elements[selector.slice(1)]; document.querySelectorAll = () => choices;
  const timers = new Map(), presses = []; let serial = 0, imports = 0, callbacks, disposed = 0;
  vm.runInNewContext(source, {
    ...model, document, window, matchMedia: () => motion, navigator: { connection: { saveData } },
    setTimeout: fn => { timers.set(++serial, fn); return serial; }, clearTimeout: id => timers.delete(id),
    loadModule: async () => { imports++; if (fail) throw new Error('offline'); return { createKeyboardScene(host, opts) { callbacks = opts; opts.onReady(); return { press: value => presses.push(value), collect() {}, dispose() { disposed++; } }; } }; },
  });
  await new Promise(resolve => setImmediate(resolve));
  return { elements, choices, document, motion, timers, presses, callbacks, imports: () => imports, disposed: () => disposed,
    flush() { const pending = [...timers.values()]; timers.clear(); pending.forEach(fn => fn()); } };
}
test('canvas callbacks preserve the actually hit key instead of pressing the example initial again', async () => {
  const app = await setup();
  app.callbacks.onKey('Q');
  assert.deepEqual(app.presses, []);
  app.choices[1].emit('click');
  assert.deepEqual(app.presses, ['x']);
});
test('rapid example changes cancel stale reveal, and collection resets for the next word', async () => {
  const app = await setup();
  app.choices[1].emit('click'); app.choices[2].emit('click');
  assert.equal(app.timers.size, 1); app.flush();
  assert.equal(app.elements['hero-english'].textContent, 'Chinese');
  app.elements['collect-demo'].emit('click');
  assert.equal(app.elements['saved-word'].textContent, '中文 · Chinese');
  assert.equal(app.elements['collect-demo'].disabled, true);
  app.choices[0].emit('click'); app.flush();
  assert.equal(app.elements['saved-word'].textContent, '我的词本');
  assert.equal(app.elements['collect-demo'].disabled, false);
});
test('reduced motion and save data skip the module while examples still work', async () => {
  for (const config of [{ reduced: true }, { saveData: true }]) {
    const app = await setup(config); assert.equal(app.imports(), 0);
    app.choices[1].emit('click'); app.flush();
    assert.equal(app.elements['hero-english'].textContent, 'study');
  }
});
test('module failure leaves the static view and usable collection', async () => {
  const app = await setup({ fail: true });
  assert.equal(app.elements['keyboard-scene'].classList.contains('is-ready'), false);
  app.choices[1].emit('click'); app.flush(); app.elements['collect-demo'].emit('click');
  assert.equal(app.elements['saved-word'].textContent, '学习 · study');
});
test('hidden pages cancel pending reveal and reduced motion disposes the live scene', async () => {
  const app = await setup(); app.choices[1].emit('click');
  app.document.hidden = true; app.document.emit('visibilitychange');
  assert.equal(app.timers.size, 0); assert.equal(app.elements['collect-demo'].disabled, false);
  app.motion.matches = true; app.motion.emit('change');
  assert.equal(app.disposed(), 1);
  assert.equal(app.elements['keyboard-scene'].classList.contains('is-ready'), false);
});
