import assert from 'node:assert/strict';
import test from 'node:test';
import { editStudioText, KEY_ROWS, SKINS } from './showroom.js';

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
