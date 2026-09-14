const reduced = matchMedia('(prefers-reduced-motion: reduce)');
let gone = false;
const states = [...document.querySelectorAll('[data-demo-video]')].map(video => ({
  video, button:video.parentElement.querySelector('button'), visible:false,
  loaded:false, manual:false, userPaused:false, failed:false, pending:false, attempt:0,
  sourceErrors:new Set()
}));
function allowed(s) {
  return !gone && s.visible && !document.hidden && !s.userPaused && !s.failed &&
    (s.manual || (!reduced.matches && !navigator.connection?.saveData));
}
function updateLabel(s) {
  if (s.failed) {
    s.button.setAttribute('aria-label','重新播放'+s.video.getAttribute('aria-label'));
    s.button.dataset.error='true'; s.button.firstElementChild.textContent='重试'; return;
  }
  const playing=!s.video.paused;
  s.button.dataset.error='false';
  s.button.setAttribute('aria-label',(playing?'暂停':'播放')+s.video.getAttribute('aria-label'));
  s.button.firstElementChild.textContent=playing?'Ⅱ':'▶';
}
function stop(s) {
  ++s.attempt; s.pending=false; s.video.pause();
}
function fail(s) {
  s.failed=true; stop(s); updateLabel(s);
}
function sync(s) {
  if (!allowed(s)) { stop(s); return; }
  if (!s.loaded) {
    s.sourceErrors.clear();
    s.video.querySelectorAll('source').forEach(source=>{source.src=source.dataset.src;});
    s.video.load(); s.loaded=true;
  }
  if (!s.pending && s.video.paused) {
    s.pending=true; const attempt=++s.attempt;
    s.video.play().then(()=>{if(attempt===s.attempt&&!allowed(s))stop(s);})
      .catch(()=>{if(attempt===s.attempt){s.userPaused=true;updateLabel(s);}})
      .finally(()=>{if(attempt===s.attempt)s.pending=false;});
  }
}
const observer = typeof IntersectionObserver === 'undefined' ? null : new IntersectionObserver(entries=>{
  entries.forEach(entry=>{const s=states.find(s=>s.video===entry.target); if(s){s.visible=entry.isIntersecting;sync(s);}});
},{threshold:.25});
states.forEach(s=>{
  observer?.observe(s.video);
  s.button.addEventListener('click',()=>{
    // Media failures retry only after an explicit click, never on scrolling.
    if(s.failed){s.failed=false;s.loaded=false;}
    s.userPaused=!s.video.paused;s.manual=true;
    if(!observer)s.visible=true;
    updateLabel(s);sync(s);
  });
  s.video.addEventListener('play',()=>{if(!allowed(s))s.video.pause();updateLabel(s);});
  s.video.addEventListener('pause',()=>updateLabel(s));
  s.video.addEventListener('error',()=>fail(s));
  const sources=[...s.video.querySelectorAll('source')];
  // With <source> alternatives, total failure may not emit an error on <video>.
  sources.forEach(source=>source.addEventListener('error',()=>{
    s.sourceErrors.add(source);
    if(s.sourceErrors.size===sources.length)fail(s);
  }));
});
document.addEventListener('visibilitychange',()=>states.forEach(sync));
reduced.addEventListener('change',()=>states.forEach(sync));
navigator.connection?.addEventListener?.('change',()=>states.forEach(sync));
window.addEventListener('pagehide',()=>{gone=true;observer?.disconnect();states.forEach(stop);});
window.addEventListener('pageshow',()=>{gone=false;states.forEach(s=>observer?.observe(s.video));});
