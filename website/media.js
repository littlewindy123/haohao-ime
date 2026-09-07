const reduced = matchMedia('(prefers-reduced-motion: reduce)');
const states = [...document.querySelectorAll('[data-demo-video]')].map(video => ({ video, button: video.parentElement.querySelector('button'), visible: false, loaded: false, manual: false, userPaused: false, failed: false, pending: false }));
function updateLabel(s) { const playing = !s.video.paused; s.button.setAttribute('aria-label', (playing ? '暂停' : '播放') + s.video.getAttribute('aria-label')); s.button.firstElementChild.textContent = playing ? 'Ⅱ' : '▶'; }
function sync(s) {
  const allowed = s.visible && !document.hidden && !s.userPaused && !s.failed && (s.manual || (!reduced.matches && !navigator.connection?.saveData));
  if (!allowed) { s.video.pause(); return; }
  if (!s.loaded) { s.video.querySelectorAll('source').forEach(source => { source.src = source.dataset.src; }); s.video.load(); s.loaded = true; }
  if (!s.pending && s.video.paused) { s.pending = true; s.video.play().catch(() => { s.userPaused = true; updateLabel(s); }).finally(() => { s.pending = false; }); }
}
const observer = new IntersectionObserver(entries => { entries.forEach(entry => { const s = states.find(s => s.video === entry.target); s.visible = entry.isIntersecting; sync(s); }); }, { threshold: .25 });
states.forEach(s => {
  observer.observe(s.video);
  s.button.addEventListener('click', () => { s.userPaused = !s.video.paused; s.manual = true; if (s.failed) return; sync(s); });
  s.video.addEventListener('play', () => updateLabel(s)); s.video.addEventListener('pause', () => updateLabel(s));
  s.video.addEventListener('error', () => { s.failed = true; s.button.disabled = true; s.button.setAttribute('aria-label', '暂时无法播放'); });
});
document.addEventListener('visibilitychange', () => states.forEach(sync));
reduced.addEventListener('change', () => states.forEach(sync));
window.addEventListener('pagehide', () => { observer.disconnect(); states.forEach(s => s.video.pause()); });
window.addEventListener('pageshow', () => states.forEach(s => observer.observe(s.video)));
