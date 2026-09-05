const DEMOS = {
  nihao: [
    { chinese: "你好", english: "hello", phonetic: "/həˈɫoʊ/" },
    { chinese: "你", english: "you", phonetic: "/ˈju/" },
    { chinese: "好", english: "good", phonetic: "/ˈɡʊd/" },
  ],
  xuexi: [
    { chinese: "学习", english: "study", phonetic: "/ˈstədi/" },
    { chinese: "学习中", english: "", phonetic: "" },
    { chinese: "学习了", english: "", phonetic: "" },
  ],
  zhongwen: [
    { chinese: "中文", english: "Chinese", phonetic: "/tʃaɪˈniz/" },
    { chinese: "中文名", english: "", phonetic: "" },
    { chinese: "中文系", english: "", phonetic: "" },
  ],
};

const input = document.querySelector("#pinyin-input");
const list = document.querySelector("#candidate-list");
const status = document.querySelector("#candidate-status");
const phoneticToggle = document.querySelector("#phonetic-toggle");
const exampleButtons = [...document.querySelectorAll("[data-example]")];
const keyButtons = [...document.querySelectorAll("[data-key]")];

let revealTimer;

function normalize(value) {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

function getCandidates(value) {
  const normalized = normalize(value);
  if (DEMOS[normalized]) return DEMOS[normalized];
  if (!normalized) return [];
  return [
    { chinese: "试试示例", english: "try an example", phonetic: "" },
    { chinese: "你好", english: "hello", phonetic: "/həˈɫoʊ/" },
    { chinese: "学习", english: "study", phonetic: "/ˈstədi/" },
  ];
}

function candidateMarkup(candidate) {
  const phonetic = phoneticToggle.checked && candidate.phonetic ? candidate.phonetic : "";
  return [
    '<article class="candidate-item">',
    "<strong>" + candidate.chinese + "</strong>",
    "<span>" + (candidate.english || "&nbsp;") + "</span>",
    "<small>" + (phonetic || "&nbsp;") + "</small>",
    "</article>",
  ].join("");
}

function renderCandidates({ wait = true } = {}) {
  window.clearTimeout(revealTimer);
  const candidates = getCandidates(input.value);
  list.innerHTML = candidates.map(candidateMarkup).join("");

  exampleButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.example === normalize(input.value));
  });

  if (!candidates.length) {
    list.classList.remove("is-waiting");
    status.textContent = "输入一个示例拼音";
    return;
  }

  if (!wait) {
    list.classList.remove("is-waiting");
    status.textContent = phoneticToggle.checked ? "英文与音标已就绪" : "英文释义已就绪";
    return;
  }

  list.classList.add("is-waiting");
  status.textContent = "候选正在稳定…";
  revealTimer = window.setTimeout(() => {
    list.classList.remove("is-waiting");
    status.textContent = phoneticToggle.checked ? "英文与音标已就绪" : "英文释义已就绪";
  }, 300);
}

input.addEventListener("input", () => renderCandidates());

phoneticToggle.addEventListener("change", () => renderCandidates({ wait: false }));

exampleButtons.forEach((button) => {
  button.addEventListener("click", () => {
    input.value = button.dataset.example;
    renderCandidates();
    input.focus();
  });
});

keyButtons.forEach((button) => {
  button.addEventListener("click", () => {
    const key = button.dataset.key;
    if (key === "clear") input.value = "";
    else if (key === "backspace") input.value = input.value.slice(0, -1);
    else input.value += key;
    renderCandidates();
    input.focus();
  });
});

const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

if (!reduceMotion && "IntersectionObserver" in window) {
  document.documentElement.classList.add("motion-ready");
  const observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (!entry.isIntersecting) return;
        entry.target.classList.add("is-visible");
        observer.unobserve(entry.target);
      });
    },
    { rootMargin: "0px 0px -8%", threshold: 0.08 },
  );

  document.querySelectorAll(".reveal").forEach((element) => observer.observe(element));
}

renderCandidates();
