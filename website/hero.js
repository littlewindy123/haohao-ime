import { greetingEvents, mountKeyboard } from './keyboard-model.mjs';
const host = document.querySelector('#keyboard-scene'), text = document.querySelector('#hero-greeting'), button = document.querySelector('#hero-pause');
const fallback = mountKeyboard(host), motion = matchMedia('(prefers-reduced-motion: reduce)');
let scene, timeline, visible = true, userPaused = false, gone = false, failed = false, loading = false;
function staticView() { scene?.dispose(); scene = undefined; host.classList.remove('is-ready'); }
function sync() {
  const active = !gone && visible && !document.hidden && !motion.matches && !navigator.connection?.saveData && !userPaused;
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
    if (event.key) { scene?.press(event.key); if (!scene) fallback.press(event.key); }
  }, [], event.time);
  timeline.to({}, { duration: .01 }, sequence.duration); sync();
}
async function loadScene() {
  if (scene || loading || failed || gone || !visible || document.hidden || motion.matches || navigator.connection?.saveData) return;
  loading = true;
  try {
    const { createKeyboardScene } = await import("./vendor/scene-3d.min.js?v=20260908-keycaps");
    if (gone || document.hidden || !visible || motion.matches) return;
    scene = createKeyboardScene(host, { interactive: false, onReady: () => host.classList.add('is-ready'), onFail: () => { failed = true; staticView(); } });
  } catch { failed = true; staticView(); host.querySelector('canvas')?.remove(); }
  finally { loading = false; }
}
button.addEventListener('click', () => { userPaused = !userPaused; sync(); });
const observer = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; sync(); if (visible) loadScene(); });
observer.observe(host);
motion.addEventListener('change', () => { staticView(); setupTimeline(); loadScene(); });
document.addEventListener('visibilitychange', () => { sync(); if (!document.hidden) loadScene(); });
window.addEventListener('pagehide', () => { gone = true; timeline?.kill(); timeline = undefined; staticView(); observer.disconnect(); });
window.addEventListener('pageshow', () => { gone = false; observer.observe(host); if (!timeline) setupTimeline(); loadScene(); });
setupTimeline(); loadScene();
