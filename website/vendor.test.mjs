import assert from "node:assert/strict";
import { createRequire } from "node:module";
import test from "node:test";

const require = createRequire(import.meta.url);
const { gsap } = require("./vendor/gsap-3.15.0.min.js");

test("pinned GSAP runs a real timeline and reverts its scoped targets", () => {
  assert.equal(gsap.version, "3.15.0");
  const mascot = { y: 0 };
  const text = { y: 0 };
  let timeline;
  const context = gsap.context(() => {
    timeline = gsap.timeline({ paused: true });
    timeline.addLabel("welcome", 0)
      .fromTo(mascot, { y: 22 }, { y: 0, duration: 0.85 }, "welcome")
      .fromTo(text, { y: 14 }, { y: 0, duration: 0.65 }, "welcome+=0.08");
  });
  timeline.progress(1);
  assert.equal(mascot.y, 0);
  assert.equal(text.y, 0);
  context.revert();
  assert.equal(mascot.y, 0);
  gsap.ticker.sleep();
});
