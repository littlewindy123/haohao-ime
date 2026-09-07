import { SKINS, skinFor } from './skins.mjs';
import { mountKeyboard } from './keyboard-model.mjs';
const root = document.querySelector('#skin-studio'), host = document.querySelector('#skin-scene');
const buttons = [...root.querySelectorAll('[data-skin-choice]')], motion = matchMedia('(prefers-reduced-motion: reduce)');
const fallback = mountKeyboard(host, { interactive: true });
let selected = 'taffy', scene, loading = false, failed = false, visible = false, gone = false;
function choose(id) {
  if (!Object.hasOwn(SKINS, id)) return; selected = id; const skin = skinFor(id);
  root.dataset.skin = id; root.querySelector('#skin-name').textContent = skin.name;
  root.querySelector('#skin-status').textContent = skin.name;
  buttons.forEach(b => b.setAttribute('aria-pressed', String(b.dataset.skinChoice === id)));
  ['--skin-key', '--skin-accent', '--skin-enter', '--skin-edge'].forEach((name,i) => root.style.setProperty(name, skin.colors[i]));
  root.style.setProperty('--skin-ink', skin.ink);
  host.setAttribute('aria-label', skin.name + '键帽设计'); fallback.setSkin(skin); scene?.setSkin(skin);
}
buttons.forEach(b => b.addEventListener('click', () => choose(b.dataset.skinChoice)));
function staticView() { scene?.dispose(); scene = undefined; host.classList.remove('is-ready'); }
async function loadScene() {
  if (scene || loading || failed || gone || !visible || motion.matches || navigator.connection?.saveData || document.hidden) return;
  loading = true;
  try {
    const { createKeyboardScene } = await import('./vendor/scene-3d.min.js?v=20260908-keycaps');
    if (gone || !visible || motion.matches || document.hidden) return;
    scene = createKeyboardScene(host, { interactive: true, onReady: () => host.classList.add('is-ready'), onFail: () => { failed = true; staticView(); } });
    scene.setSkin(skinFor(selected));
  } catch { failed = true; staticView(); host.querySelector('canvas')?.remove(); }
  finally { loading = false; }
}
const observer = new IntersectionObserver(entries => { visible = entries[0].isIntersecting; if (visible) loadScene(); }, { rootMargin: '100px' });
observer.observe(root);
motion.addEventListener('change', () => { if (motion.matches) staticView(); else loadScene(); });
document.addEventListener('visibilitychange', () => { if (!document.hidden) loadScene(); });
window.addEventListener('pagehide', () => { gone = true; staticView(); observer.disconnect(); });
window.addEventListener('pageshow', () => { gone = false; observer.observe(root); loadScene(); });
choose(selected);
