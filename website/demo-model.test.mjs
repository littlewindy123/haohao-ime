import assert from "node:assert/strict";
import test from "node:test";
import { applyKey, getCandidates, MAX_INPUT_LENGTH, normalize, REVEAL_DELAY_MS } from "./demo-model.mjs";

test("candidate delay stays at the app's 300 ms default", () => assert.equal(REVEAL_DELAY_MS, 300));
test("normalization accepts upper case and spaced pinyin", () => assert.equal(normalize(" NI HAO! "), "nihao"));
test("normalization caps pasted input", () => assert.equal(normalize("a".repeat(100)).length, MAX_INPUT_LENGTH));
test("all three examples preserve their first translation", () => {
  for (const [key, expected] of [["nihao", "hello"], ["xuexi", "study"], ["zhongwen", "Chinese"]]) {
    assert.equal(getCandidates(key).length, 3);
    assert.equal(getCandidates(key)[0].english, expected);
  }
});
test("missing phrases never get a made-up translation", () => assert.equal(getCandidates("xuexi")[1].english, ""));
test("empty and unknown input have no candidates", () => {
  for (const value of ["", "unknown", "constructor", "__proto__", "prototype"]) assert.deepEqual(getCandidates(value), []);
});
test("virtual keys insert at the caret", () => assert.deepEqual(applyKey("niho", 3, 3, "a"), { value: "nihao", caret: 4 }));
test("virtual keys replace a selection", () => assert.deepEqual(applyKey("nihao", 2, 5, "a"), { value: "nia", caret: 3 }));
test("backspace removes the selected text", () => assert.deepEqual(applyKey("nihao", 2, 5, "backspace"), { value: "ni", caret: 2 }));
test("backspace respects the caret and start boundary", () => {
  assert.deepEqual(applyKey("nihao", 2, 2, "backspace"), { value: "nhao", caret: 1 });
  assert.deepEqual(applyKey("nihao", 0, 0, "backspace"), { value: "nihao", caret: 0 });
});
test("clear resets both value and caret", () => assert.deepEqual(applyKey("nihao", 2, 2, "clear"), { value: "", caret: 0 }));
test("invalid keys and a full input cannot append", () => {
  assert.equal(applyKey("nihao", 5, 5, "<script>").value, "nihao");
  assert.equal(applyKey("a".repeat(32), 32, 32, "b").value.length, 32);
  assert.equal(applyKey("a".repeat(32), 30, 32, "b").value.length, 31);
});
