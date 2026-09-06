import { applyKey, getCandidates, normalize, REVEAL_DELAY_MS } from "./demo-model.mjs";

const input = document.querySelector("#pinyin-input");
const list = document.querySelector("#candidate-list");
const status = document.querySelector("#candidate-status");
const phoneticToggle = document.querySelector("#phonetic-toggle");
const exampleButtons = [...document.querySelectorAll("[data-example]")];
const keyButtons = [...document.querySelectorAll("[data-key]")];
const cells = [...list.querySelectorAll(".candidate-item")].map((element) => ({
  element,
  chinese: element.querySelector("strong"),
  english: element.querySelector("span"),
  phonetic: element.querySelector("small"),
}));
const emptyMessage = document.createElement("p");
emptyMessage.className = "candidate-empty";
let revealTimer;
let isComposing = false;
let showingCandidates = true;

function cancelReveal() {
  window.clearTimeout(revealTimer);
  revealTimer = undefined;
}

function finishReveal() {
  list.classList.remove("is-waiting");
  status.textContent = phoneticToggle.checked ? "英文与音标已就绪" : "英文释义已就绪";
}

function renderCandidates({ wait = true } = {}) {
  cancelReveal();
  const normalized = normalize(input.value);
  const candidates = getCandidates(normalized);
  exampleButtons.forEach((button) => {
    const active = button.dataset.example === normalized;
    button.classList.toggle("is-active", active);
    button.setAttribute("aria-pressed", String(active));
  });

  if (!candidates.length) {
    list.classList.remove("is-waiting");
    emptyMessage.textContent = input.value ? "网页示例尚未收录这组拼音" : "输入拼音，看看双语候选";
    if (showingCandidates) list.replaceChildren(emptyMessage);
    showingCandidates = false;
    status.textContent = input.value ? "请选择下方的示例试试" : "等待输入";
    return;
  }

  if (!showingCandidates) list.replaceChildren(...cells.map((cell) => cell.element));
  showingCandidates = true;
  candidates.forEach((candidate, index) => {
    const cell = cells[index];
    // Reuse these three cells; keystrokes do not recreate the candidate DOM.
    cell.chinese.textContent = candidate.chinese;
    cell.english.textContent = candidate.english;
    cell.phonetic.textContent = phoneticToggle.checked ? candidate.phonetic : "";
  });

  if (!wait) {
    finishReveal();
    return;
  }
  list.classList.add("is-waiting");
  status.textContent = "候选正在稳定…";
  revealTimer = window.setTimeout(() => {
    revealTimer = undefined;
    finishReveal();
  }, REVEAL_DELAY_MS);
}

input.addEventListener("compositionstart", () => {
  isComposing = true;
  cancelReveal();
});
input.addEventListener("compositionend", () => {
  isComposing = false;
  renderCandidates();
});
input.addEventListener("input", (event) => {
  if (!isComposing && !event.isComposing) renderCandidates();
});
phoneticToggle.addEventListener("change", () => {
  if (!isComposing) renderCandidates({ wait: false });
});

exampleButtons.forEach((button) => {
  button.addEventListener("click", () => {
    isComposing = false;
    input.value = button.dataset.example;
    input.setSelectionRange(input.value.length, input.value.length);
    renderCandidates();
    // Do not focus the input: that would summon the phone's native keyboard.
  });
});

keyButtons.forEach((button) => {
  button.addEventListener("mousedown", (event) => event.preventDefault());
  button.addEventListener("click", () => {
    if (isComposing) return;
    const next = applyKey(input.value, input.selectionStart, input.selectionEnd, button.dataset.key);
    input.value = next.value;
    input.setSelectionRange(next.caret, next.caret);
    renderCandidates();
  });
});

const motionPreference = window.matchMedia("(prefers-reduced-motion: reduce)");
const activeAnimations = new Set();
const gsapContexts = new Set();
function stopAnimations() {
  for (const animation of activeAnimations) animation.cancel();
  activeAnimations.clear();
  // Context reversion restores visible CSS state even if the page is hidden mid-intro.
  for (const context of gsapContexts) context.revert();
  gsapContexts.clear();
}

function startBrandIntro() {
  const gsap = window.gsap;
  if (!gsap || motionPreference.matches || document.hidden || window.location.hash) return;
  const logo = document.querySelector('[data-intro="logo"]');
  const copy = [...document.querySelectorAll('[data-intro="copy"] > *')];
  if (!logo || !copy.length) return;
  // The mascot establishes recognition; the name and download action follow together.
  const context = gsap.context(() => {
    const intro = gsap.timeline({
      defaults: { duration: 0.65, ease: "power3.out", clearProps: "transform,opacity,visibility" },
      onComplete: () => gsapContexts.delete(context),
    });
    intro.addLabel("welcome", 0)
      .fromTo(logo, { y: 22, scale: 0.965, rotation: -2 }, { y: 0, scale: 1, rotation: 0, duration: 0.85 }, "welcome")
      .fromTo(copy, { y: 14, autoAlpha: 0.25 }, { y: 0, autoAlpha: 1, stagger: 0.07 }, "welcome+=0.08");
  });
  gsapContexts.add(context);
}
// Wait for deferred self-hosted GSAP. Failure to load it never blocks input or downloads.
if (document.readyState === "complete") startBrandIntro();
else window.addEventListener("load", startBrandIntro, { once: true });

function revealSection(element) {
  if (window.gsap) {
    const context = window.gsap.context(() => {
      window.gsap.fromTo(element, { y: 18, autoAlpha: 0.25 }, {
        y: 0, autoAlpha: 1, duration: 0.6, ease: "power3.out",
        clearProps: "transform,opacity,visibility",
        onComplete: () => gsapContexts.delete(context),
      });
    });
    gsapContexts.add(context);
    return;
  }
  if (!element.animate) return;
  const animation = element.animate(
    [{ opacity: 0.25, transform: "translateY(14px)" }, { opacity: 1, transform: "none" }],
    { duration: 460, easing: "cubic-bezier(.22, 1, .36, 1)" },
  );
  activeAnimations.add(animation);
  const forget = () => activeAnimations.delete(animation);
  animation.addEventListener("finish", forget, { once: true });
  animation.addEventListener("cancel", forget, { once: true });
}
motionPreference.addEventListener?.("change", (event) => {
  if (event.matches) stopAnimations();
});
if ("IntersectionObserver" in window) {
  const observer = new IntersectionObserver((entries) => {
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      observer.unobserve(entry.target);
      if (motionPreference.matches || document.hidden) continue;
      // Elements are never pre-hidden; script/observer failure cannot blank a section.
      revealSection(entry.target);
    }
  }, { threshold: 0.08 });
  document.querySelectorAll(".reveal").forEach((element) => observer.observe(element));
}
document.addEventListener("visibilitychange", () => {
  if (document.hidden) {
    cancelReveal();
    stopAnimations();
  } else if (!isComposing) {
    renderCandidates({ wait: false });
  }
});
window.addEventListener("pagehide", () => {
  cancelReveal();
  stopAnimations();
});
const installGuide = document.querySelector("#install");
function openInstallGuide() {
  installGuide.open = true;
}
document.querySelectorAll('a[href="#install"]').forEach((link) => {
  link.addEventListener("click", openInstallGuide);
});
function followInstallHash() {
  if (window.location.hash === "#install") openInstallGuide();
}
window.addEventListener("hashchange", followInstallHash);
followInstallHash();
renderCandidates({ wait: false });
