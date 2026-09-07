import { getCandidates, REVEAL_DELAY_MS } from "./demo-model.mjs";

const host = document.querySelector("#keyboard-scene");
const study = document.querySelector("#hero-study");
const choices = [...document.querySelectorAll("[data-hero-example]")];
const chinese = document.querySelector("#hero-chinese"), english = document.querySelector("#hero-english");
const collect = document.querySelector("#collect-demo"), saved = document.querySelector("#saved-word");
const status = document.querySelector("#hero-status");
const motion = matchMedia("(prefers-reduced-motion: reduce)");
let scene, timer, selected = "nihao", loading = false, failed = false, gone = false;

function showExample(value, pressKey = true) {
  clearTimeout(timer); selected = value;
  const word = getCandidates(value)[0];
  choices.forEach(button => button.setAttribute("aria-pressed", String(button.dataset.heroExample === value)));
  study.classList.remove("is-collected"); collect.disabled = true;
  collect.textContent = "收藏演示"; saved.textContent = "我的词本";
  chinese.textContent = word.chinese; english.textContent = word.english;
  study.classList.toggle("is-changing", !motion.matches);
  if (pressKey) scene?.press(value[0]);
  timer = setTimeout(() => {
    study.classList.remove("is-changing"); collect.disabled = false;
    status.textContent = `${word.chinese}，${word.english}。可以试试收藏。`;
  }, motion.matches ? 0 : REVEAL_DELAY_MS);
}
choices.forEach(button => button.addEventListener("click", () => showExample(button.dataset.heroExample)));
collect.addEventListener("click", () => {
  study.classList.add("is-collected"); collect.textContent = "已收进示意词本"; collect.disabled = true;
  saved.textContent = `${chinese.textContent} · ${english.textContent}`;
  status.textContent = "收藏演示完成，仅本页展示，不会保存数据。"; scene?.collect();
});
function staticView() { scene?.dispose(); scene = undefined; host.classList.remove("is-ready"); }
async function loadScene() {
  if (scene || loading || failed || gone || motion.matches || navigator.connection?.saveData || document.hidden) return;
  loading = true;
  try {
    const { createKeyboardScene } = await import("./vendor/scene-3d.min.js");
    if (gone || document.hidden || motion.matches || navigator.connection?.saveData) return;
    scene = createKeyboardScene(host, {
      onReady: () => host.classList.add("is-ready"),
      onFail: () => { failed = true; staticView(); },
      onKey: () => showExample(selected, false),
    });
  } catch { failed = true; staticView(); host.querySelector("canvas")?.remove(); }
  finally { loading = false; }
}
motion.addEventListener("change", () => { if (motion.matches) staticView(); else loadScene(); });
document.addEventListener("visibilitychange", () => {
  if (document.hidden) { clearTimeout(timer); study.classList.remove("is-changing"); collect.disabled = study.classList.contains("is-collected"); }
  else loadScene();
});
window.addEventListener("pagehide", () => { gone = true; clearTimeout(timer); staticView(); });
window.addEventListener("pageshow", () => { gone = false; loadScene(); });
loadScene();
