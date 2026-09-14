// Page-level polish only. Editing and candidate state live exclusively in showroom.js.
const motionPreference = window.matchMedia("(prefers-reduced-motion: reduce)");
const contexts = new Set();
let observer;
function stopAnimations() {
  for (const context of contexts) context.revert();
  contexts.clear();
}
function motionAllowed() {
  return !motionPreference.matches && !navigator.connection?.saveData && !document.hidden;
}
function startBrandIntro() {
  const gsap = window.gsap;
  if (!gsap || !motionAllowed() || window.location.hash) return;
  // Keep the headline and introduction at their final position from first paint.
  const copy = [...document.querySelectorAll('[data-intro="copy"] .hero-actions')];
  if (!copy.length) return;
  const context = gsap.context(() => {
    gsap.timeline({ defaults: { duration:.55, ease:"power3.out", clearProps:"transform" } })
      .fromTo(copy, { y:12 }, { y:0, stagger:.055 });
  });
  contexts.add(context);
}
function observeSections() {
  if (!("IntersectionObserver" in window)) return;
  observer?.disconnect();
  observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      if (!window.gsap || !motionAllowed()) continue;
      const context = window.gsap.context(() => {
        window.gsap.fromTo(entry.target, { y:14 }, { y:0, duration:.5, ease:"power3.out", clearProps:"transform" });
      });
      contexts.add(context);
    }
  }, { threshold:.12 });
  document.querySelectorAll(".reveal").forEach(element => observer.observe(element));
}
motionPreference.addEventListener?.("change", event => { if (event.matches) stopAnimations(); });
document.addEventListener("visibilitychange", () => { if (document.hidden) stopAnimations(); });
window.addEventListener("pagehide", () => { observer?.disconnect(); stopAnimations(); });
window.addEventListener("pageshow", observeSections);
if (document.readyState === "complete") startBrandIntro();
else window.addEventListener("load", startBrandIntro, { once:true });
observeSections();

const installGuide = document.querySelector("#install");
function openInstallGuide() { if (installGuide) installGuide.open = true; }
document.querySelectorAll('a[href="#install"]').forEach(link => link.addEventListener("click", openInstallGuide));
function followInstallHash() { if (window.location.hash === "#install") openInstallGuide(); }
window.addEventListener("hashchange", followInstallHash);
followInstallHash();
