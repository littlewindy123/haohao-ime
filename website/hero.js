import { greetingEvents, mountKeyboard } from './keyboard-model.mjs';
const host = document.querySelector('#keyboard-scene'), text = document.querySelector('#hero-greeting'), button = document.querySelector('#hero-pause');
let fallback = mountKeyboard(host);
const motion = matchMedia('(prefers-reduced-motion: reduce)'), connection = navigator.connection;
let scene, timeline, pendingStart, visible = true, userPaused = false, gone = false, failed = false, loading = false, generation = 0;
function cancelStart() {
  const pending = pendingStart; pendingStart = undefined;
  if (!pending) return;
  if (pending.frame !== undefined) window.cancelAnimationFrame?.(pending.frame);
  if (pending.idle !== undefined) window.cancelIdleCallback?.(pending.idle);
  if (pending.timer !== undefined) window.clearTimeout?.(pending.timer);
}
function staticView() { cancelStart(); generation++; scene?.dispose(); scene = undefined; host.classList.remove('is-ready'); }
function sync() {
  const active = !gone && visible && !document.hidden && !motion.matches && !navigator.connection?.saveData && !userPaused;
  if (gone || !visible || document.hidden || motion.matches || navigator.connection?.saveData) cancelStart();
  if (active) timeline?.play(); else timeline?.pause();
  button.setAttribute('aria-label', userPaused ? '继续动画' : '暂停动画'); button.setAttribute('aria-pressed', String(userPaused)); button.dataset.paused = String(userPaused);
}
function setupTimeline() {
  timeline?.kill(); timeline = undefined;
  if (motion.matches || navigator.connection?.saveData || !window.gsap) {
    text.textContent = '你好，欢迎你来看我的作品。跪求star支持'; button.hidden = true; return;
  }
  button.hidden = false; text.textContent = '';
  const sequence = greetingEvents();
  timeline = window.gsap.timeline({ paused: true, repeat: -1 });
  for (const event of sequence.events) timeline.call(() => {
    if (event.text !== undefined) text.textContent = event.text;
    if (event.key) { scene?.press(event.key); if (!scene) fallback?.press(event.key); }
  }, [], event.time);
  timeline.to({}, { duration: .01 }, sequence.duration); sync();
}
function loadScene() {
  if (pendingStart || scene || loading || failed || gone || !visible || document.hidden || motion.matches || navigator.connection?.saveData) return;
  const pending = { generation }; pendingStart = pending;
  const live = () => {
    if (pendingStart !== pending) return false;
    if (pending.generation !== generation || gone || !visible || document.hidden || motion.matches || navigator.connection?.saveData) { cancelStart(); return false; }
    return true;
  };
  const task = callback => {
    if (window.setTimeout) pending.timer = window.setTimeout(callback, 0);
    else Promise.resolve().then(callback); // Minimal non-browser test environments.
  };
  const frame = callback => {
    if (window.requestAnimationFrame) pending.frame = window.requestAnimationFrame(callback);
    else task(callback);
  };
  // A rAF runs before paint: the second frame gives the complete DOM board a paint first.
  frame(() => { if (live()) frame(() => {
    if (!live()) return;
    const start = () => { if (!live()) return; pendingStart = undefined; startScene(); };
    if (window.requestIdleCallback) pending.idle = window.requestIdleCallback(start, { timeout: 1000 });
    else task(start);
  }); });
}
async function startScene() {
  if (scene || loading || failed || gone || !visible || document.hidden || motion.matches || navigator.connection?.saveData) return;
  loading = true; const token = generation;
  try {
    const { createKeyboardScene } = await import("./vendor/scene-3d.min.js?v=20260915-showroom");
    if (gone || document.hidden || !visible || motion.matches || connection?.saveData || token !== generation) return;
    const live = () => !gone && token === generation;
    const created = createKeyboardScene(host, { interactive: false, onReady: () => { if (live()) host.classList.add('is-ready'); }, onFail: () => { if (live()) { failed = true; staticView(); } } });
    if (!live()) { created.dispose(); return; }
    scene = created;
  } catch { if (token === generation) { failed = true; staticView(); host.querySelector('canvas')?.remove(); } }
  finally { loading = false; if (!failed && !scene && token !== generation) loadScene(); }
}
button.addEventListener('click', () => { userPaused = !userPaused; sync(); });
const observer = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; sync(); if (visible) loadScene(); });
observer.observe(host);
function preferenceChanged() { staticView(); failed = false; setupTimeline(); loadScene(); }
motion.addEventListener('change', preferenceChanged);
connection?.addEventListener?.('change', preferenceChanged);
document.addEventListener('visibilitychange', () => { sync(); if (!document.hidden) loadScene(); });
window.addEventListener('pagehide', () => { gone = true; timeline?.kill(); timeline = undefined; staticView(); fallback?.dispose?.(); fallback = undefined; observer.disconnect(); });
window.addEventListener('pageshow', () => { gone = false; failed = false; fallback ??= mountKeyboard(host); observer.observe(host); if (!timeline) setupTimeline(); loadScene(); });
setupTimeline(); loadScene();
