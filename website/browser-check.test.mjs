import assert from 'node:assert/strict';
import test from 'node:test';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { KEY_ROWS } from './keyboard-model.mjs';
import { SKINS } from './showroom.js';

const source = await readFile(new URL('./browser-check.js', import.meta.url), 'utf8');
const ids = KEY_ROWS.flat().map(key => key.id);
const literal = name => JSON.parse(JSON.stringify(vm.runInNewContext(source.match(new RegExp('const ' + name + ' = ([^;]+);'))[1])));
// Exercise the real assertion helpers without opening a browser or pretending to test layout.
const helpers = async () => vm.runInNewContext(source.slice(0, source.indexOf("  page.on('pageerror'")) + '\n  return { completeBoard, heroFallback };\n}')(null, { baseURL: 'https://example.test/' });

test('CI browser script is a standalone async function with no browser launch side effect', () => {
  const run = vm.runInNewContext(source);
  assert.equal(typeof run, 'function'); assert.equal(run.constructor.name, 'AsyncFunction');
  assert.doesNotMatch(source, /chromium\.launch|browserType\.launch|ignoreHTTPSErrors|rejectUnauthorized/);
  assert.doesNotMatch(source, /data-hero-example|collect-demo|hero-english|saved-word|phonetic-toggle|candidate-list|learning-image|scene-still/);
});
test('CI fixtures match the real 33-key layout, ten skin IDs and current three story videos', async () => {
  assert.deepEqual(literal('keyIds'), ids); assert.deepEqual(literal('skinIds'), Object.keys(SKINS));
  const html = await readFile(new URL('./index.html', import.meta.url), 'utf8');
  const labels = [...html.matchAll(/<video\b[^>]*data-demo-video[^>]*aria-label="([^"]+)"/g)].map(match => match[1]);
  assert.equal(labels.length, 3); for (const label of labels) assert.ok(source.includes("'" + label + "'"));
  for (const name of ['no-js', 'reduced-motion', 'save-data', 'no-webgl', 'module-failure', 'media-failure']) assert.ok(source.includes("['" + name + "'"));
});
test('CI complete-board assertion rejects missing, duplicate or reordered keys', async () => {
  const { completeBoard } = await helpers();
  const target = values => ({ locator: () => ({ evaluateAll: (fn, name) => fn(values.map(value => ({ getAttribute: () => value })), name) }) });
  await completeBoard(target(ids), '[data-key]', 'data-key');
  for (const bad of [ids.slice(1), [...ids, 'Q'], [...ids].reverse()]) {
    await assert.rejects(completeBoard(target(bad), '[data-key]', 'data-key'), /Complete 33-key board/);
  }
});
test('CI fallback assertion requires exactly one complete visible board', async () => {
  const { heroFallback } = await helpers();
  const target = (count, visible) => ({ locator: () => ({ count: () => count, isVisible: () => visible, evaluateAll: (fn, name) => fn(ids.map(value => ({ getAttribute: () => value })), name) }) });
  await heroFallback(target(1, true));
  await assert.rejects(heroFallback(target(2, true)), /Exactly one/);
  await assert.rejects(heroFallback(target(1, false)), /visible/);
});
