import assert from 'node:assert/strict';
import test from 'node:test';
import { editStudioText, KEY_ROWS, SKINS } from './showroom.js';
import { KEY_ROWS as PHYSICAL_ROWS, normalizeKeyboardSkin, keyFaceColor, readableKeyInk } from './keyboard-model.mjs';

test('every displayed letter types its own value, with case and editing controls', () => {
  for (const key of KEY_ROWS.flat().filter(key => /^[A-Z]$/.test(key))) {
    assert.equal(editStudioText('', 0, 0, key).value, key.toLowerCase());
    assert.equal(editStudioText('', 0, 0, key, true).value, key);
  }
  assert.deepEqual(editStudioText('abc', 1, 2, 'Q'), { value: 'aqc', caret: 2 });
  assert.deepEqual(editStudioText('abc', 1, 1, 'BACKSPACE'), { value: 'bc', caret: 0 });
  assert.equal(editStudioText('hi', 2, 2, 'SPACE').value, 'hi ');
  assert.equal(editStudioText('hi', 2, 2, 'ENTER').value, 'hi\n');
});
test('deletion preserves Unicode graphemes and length limits still allow replacement', () => {
  assert.equal(editStudioText('好👨‍👩‍👧', 9, 9, 'BACKSPACE').value, '好');
  assert.equal(editStudioText('好👍🏽', 5, 5, 'BACKSPACE').value, '好');
  assert.equal(editStudioText('', 0, 0, 'BACKSPACE').value, '');
  assert.equal(editStudioText('a'.repeat(280), 280, 280, 'Q').value.length, 280);
  assert.equal(editStudioText('a'.repeat(280), 0, 280, 'Q').value, 'q');
  assert.equal(Object.values(SKINS).filter(skin => skin.atlas).length, 6);
});
test('DOM and Three keyboards have the same complete key order and width source', () => {
  assert.deepEqual(KEY_ROWS, PHYSICAL_ROWS.map(row => row.map(key => key.id)));
  assert.equal(KEY_ROWS.flat().length, 33);
  assert.equal(Object.keys(SKINS).length, 10);
});
test('backspace inside a surrogate or joined emoji deletes one complete grapheme', () => {
  assert.deepEqual(editStudioText('好👨‍👩‍👧!', 3, 3, 'BACKSPACE'), { value: '好!', caret: 1 });
  assert.deepEqual(editStudioText('a👍🏽b', 2, 2, 'BACKSPACE'), { value: 'ab', caret: 1 });
  assert.deepEqual(editStudioText('ae\u0301b', 2, 2, 'BACKSPACE'), { value: 'ab', caret: 1 });
});
test('all ten skins share their DOM palette with Three and have readable function legends', () => {
  for (const skin of Object.values(SKINS)) {
    const palette = normalizeKeyboardSkin(skin);
    assert.equal(palette.ink, skin.colors[4]);
    assert.deepEqual(palette.colors, skin.colors);
    assert.equal(keyFaceColor(palette, 'Q'), skin.colors[0]);
    assert.equal(keyFaceColor(palette, 'LANG'), skin.colors[1]);
    assert.equal(keyFaceColor(palette, 'ENTER'), skin.colors[2]);
    for (const id of ['Q', 'LANG', 'ENTER']) assert.match(readableKeyInk(palette.ink, keyFaceColor(palette, id)), /^#[a-f\d]{6}$/i);
  }
  const graphite = normalizeKeyboardSkin(SKINS.graphite);
  assert.equal(readableKeyInk(graphite.ink, keyFaceColor(graphite, 'ENTER')), '#17232b');
});
