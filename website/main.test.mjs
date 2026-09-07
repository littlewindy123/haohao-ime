import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import vm from "node:vm";
import * as model from "./demo-model.mjs";

const source = (await readFile(new URL("./main.js", import.meta.url), "utf8"))
  .replace(/^import[^\n]+\n/, "");

class Element {
  constructor() {
    this.events = new Map();
    this.attributes = {};
    this.dataset = {};
    this.textContent = "";
    this.children = [];
    const classes = new Set();
    this.classList = {
      add: (name) => classes.add(name),
      remove: (name) => classes.delete(name),
      contains: (name) => classes.has(name),
      toggle(name, active) { if (active) classes.add(name); else classes.delete(name); },
    };
  }
  addEventListener(name, callback) {
    if (!this.events.has(name)) this.events.set(name, []);
    this.events.get(name).push(callback);
  }
  emit(name, event = {}) { this.events.get(name)?.forEach((callback) => callback(event)); }
  setAttribute(name, value) { this.attributes[name] = value; }
  setSelectionRange(start, end) { this.selectionStart = start; this.selectionEnd = end; }
  replaceChildren(...children) { this.children = children; }
  querySelector(selector) { return this.parts?.[selector]; }
  querySelectorAll() { return this.children; }
}

function setup({ hash = "", reducedMotion = false, observerSupported = true, withGsap = false } = {}) {
  const input = new Element();
  input.value = "nihao";
  input.setSelectionRange(5, 5);
  const list = new Element();
  const cells = Array.from({ length: 3 }, () => {
    const cell = new Element();
    cell.parts = Object.fromEntries(["strong", "span", "small"].map((name) => [name, new Element()]));
    return cell;
  });
  list.children = cells;
  const status = new Element();
  const toggle = new Element();
  toggle.checked = true;
  const install = new Element();
  const installLink = new Element();
  const examples = ["nihao", "xuexi", "zhongwen"].map((example) => {
    const button = new Element();
    button.dataset.example = example;
    return button;
  });
  const keys = ["a", "clear", "backspace"].map((key) => {
    const button = new Element();
    button.dataset.key = key;
    return button;
  });
  const document = new Element();
  const logo = new Element();
  const introCopy = [new Element(), new Element(), new Element(), new Element()];
  document.hidden = false;
  document.querySelector = (selector) => ({
    "#pinyin-input": input, "#candidate-list": list, "#candidate-status": status,
    "#phonetic-toggle": toggle, "#install": install, '[data-intro="logo"]': logo,
  })[selector];
  const reveals = [new Element()];
  let animateCount = 0;
  let cancelCount = 0;
  reveals[0].animate = () => {
    animateCount++;
    const animation = new Element();
    animation.cancel = () => { cancelCount++; animation.emit("cancel"); };
    return animation;
  };
  document.querySelectorAll = (selector) => ({
    "[data-example]": examples, "[data-key]": keys, ".reveal": reveals,
    'a[href="#install"]': [installLink],
    '[data-intro="copy"] > *': introCopy,
  })[selector] || [];
  document.createElement = () => new Element();
  const motion = new Element();
  motion.matches = reducedMotion;
  const timers = new Map();
  let nextTimer = 0;
  const window = new Element();
  window.location = { hash };
  window.matchMedia = () => motion;
  window.setTimeout = (callback, delay) => { timers.set(++nextTimer, { callback, delay }); return nextTimer; };
  window.clearTimeout = (id) => timers.delete(id);
  const gsapState = { contexts: [], timelines: [], tweens: [] };
  if (withGsap) window.gsap = {
    context(callback) {
      const context = { reverted: false, revert() { this.reverted = true; } };
      gsapState.contexts.push(context);
      callback();
      return context;
    },
    timeline(options) {
      const timeline = {
        options, steps: [],
        addLabel(name, at) { this.steps.push({ name, at }); return this; },
        fromTo(target, from, to, at) { this.steps.push({ target, from, to, at }); return this; },
      };
      gsapState.timelines.push(timeline);
      return timeline;
    },
    fromTo(target, from, to) { gsapState.tweens.push({ target, from, to }); },
  };
  let observed;
  class Observer {
    constructor(callback) { observed = callback; }
    observe() {}
    unobserve() {}
  }
  if (observerSupported) window.IntersectionObserver = Observer;
  vm.runInNewContext(source, { ...model, window, document, IntersectionObserver: Observer });
  return {
    input, list, cells, status, toggle, examples, keys, document, window, install, installLink, timers, motion, gsapState,
    animations: () => ({ started: animateCount, canceled: cancelCount }),
    intersect: () => observed?.([{ isIntersecting: true, target: reveals[0] }]),
    flush() { const pending = [...timers.values()]; timers.clear(); pending.forEach(({ callback }) => callback()); },
  };
}

test("initial demo immediately shows Chinese, English and optional IPA", () => {
  const page = setup();
  assert.equal(page.cells[0].parts.strong.textContent, "你好");
  assert.equal(page.cells[0].parts.span.textContent, "hello");
  assert.equal(page.cells[0].parts.small.textContent, model.getCandidates("nihao")[0].phonetic);
  assert.equal(page.list.classList.contains("is-waiting"), false);
  assert.equal(page.examples[0].attributes["aria-pressed"], "true");
  assert.equal(page.timers.size, 0);
});

test("rapid example switches cancel stale work and settle once after 300 ms", () => {
  const page = setup();
  page.examples[1].emit("click");
  page.examples[2].emit("click");
  assert.equal(page.timers.size, 1);
  assert.equal([...page.timers.values()][0].delay, 300);
  assert.equal(page.cells[0].parts.strong.textContent, "中文");
  assert.equal(page.examples[1].attributes["aria-pressed"], "false");
  assert.equal(page.examples[2].attributes["aria-pressed"], "true");
  assert.equal(page.input.selectionStart, 8);
  page.flush();
  assert.equal(page.cells[0].parts.span.textContent, "Chinese");
  assert.equal(page.list.classList.contains("is-waiting"), false);
});

test("unknown input does not invent translations and known input restores the same cells", () => {
  const page = setup();
  page.input.value = "unknown";
  page.input.emit("input");
  assert.match(page.list.children[0].textContent, /尚未收录/);
  assert.equal(page.timers.size, 0);
  page.examples[0].emit("click");
  assert.equal(page.list.children[0], page.cells[0]);
  page.flush();
  assert.equal(page.cells[0].parts.span.textContent, "hello");
});

test("IPA switch updates without delay or changing the Chinese candidates", () => {
  const page = setup();
  page.toggle.checked = false;
  page.toggle.emit("change");
  assert.equal(page.cells[0].parts.small.textContent, "");
  assert.equal(page.cells[0].parts.strong.textContent, "你好");
  assert.equal(page.timers.size, 0);
});

test("composition defers rendering until the native IME finishes", () => {
  const page = setup();
  page.examples[1].emit("click");
  page.input.emit("compositionstart");
  assert.equal(page.timers.size, 0);
  page.input.value = "zhongwen";
  page.input.emit("input", { isComposing: true });
  assert.equal(page.cells[0].parts.strong.textContent, "学习");
  page.input.emit("compositionend");
  assert.equal(page.cells[0].parts.strong.textContent, "中文");
  assert.equal(page.timers.size, 1);
});

test("virtual keyboard preserves caret, supports clear, and does not need native focus", () => {
  const page = setup();
  page.input.setSelectionRange(1, 3);
  page.keys[0].emit("click");
  assert.equal(page.input.value, "naao");
  assert.equal(page.input.selectionStart, 2);
  page.keys[2].emit("click");
  assert.equal(page.input.value, "nao");
  page.keys[1].emit("click");
  assert.equal(page.input.value, "");
  assert.match(page.list.children[0].textContent, /输入拼音/);
});

test("backgrounding cancels pending work and restores candidates when visible", () => {
  const page = setup();
  page.examples[1].emit("click");
  page.intersect();
  page.document.hidden = true;
  page.document.emit("visibilitychange");
  assert.equal(page.timers.size, 0);
  assert.equal(page.animations().canceled, 1);
  page.document.hidden = false;
  page.document.emit("visibilitychange");
  assert.equal(page.list.classList.contains("is-waiting"), false);
});

test("reduced motion and missing observers leave the content functional", () => {
  const reduced = setup({ reducedMotion: true });
  reduced.intersect();
  assert.equal(reduced.animations().started, 0);
  const fallback = setup({ observerSupported: false });
  assert.equal(fallback.cells[0].parts.span.textContent, "hello");
  const live = setup();
  live.intersect();
  live.motion.emit("change", { matches: true });
  assert.equal(live.animations().canceled, 1);
});

test("installation instructions open from links and direct hash navigation", () => {
  assert.equal(setup({ hash: "#install" }).install.open, true);
  const page = setup();
  page.installLink.emit("click");
  assert.equal(page.install.open, true);
  page.install.open = false;
  page.window.location.hash = "#install";
  page.window.emit("hashchange");
  assert.equal(page.install.open, true);
});

test("brand timeline starts after load and introduces the product scene before the copy", () => {
  const page = setup({ withGsap: true });
  assert.equal(page.gsapState.timelines.length, 0);
  page.window.emit("load");
  const timeline = page.gsapState.timelines[0];
  assert.equal(timeline.steps[0].name, "welcome");
  assert.equal(timeline.steps[1].at, "welcome");
  assert.equal(timeline.steps[2].at, "welcome+=0.08");
  assert.equal(timeline.steps[2].to.stagger, 0.07);
  assert.match(timeline.options.defaults.clearProps, /transform/);
});

test("GSAP contexts restore visible styles when hidden or reduced motion is enabled", () => {
  const page = setup({ withGsap: true });
  page.window.emit("load");
  page.intersect();
  assert.equal(page.gsapState.contexts.length, 2);
  page.document.hidden = true;
  page.document.emit("visibilitychange");
  assert.ok(page.gsapState.contexts.every((context) => context.reverted));
  const second = setup({ withGsap: true });
  second.window.emit("load");
  second.motion.emit("change", { matches: true });
  assert.equal(second.gsapState.contexts[0].reverted, true);
});

test("reduced motion, deep links and missing GSAP do not depend on brand animation", () => {
  for (const config of [{ withGsap: true, reducedMotion: true }, { withGsap: true, hash: "#install" }, {}]) {
    const page = setup(config);
    page.window.emit("load");
    assert.equal(page.gsapState.timelines.length, 0);
    assert.equal(page.cells[0].parts.span.textContent, "hello");
  }
});
