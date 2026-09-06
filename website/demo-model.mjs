export const REVEAL_DELAY_MS = 300;
export const MAX_INPUT_LENGTH = 32;

const DEMOS = Object.freeze({
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
});

export function normalize(value) {
  return String(value).toLowerCase().replace(/[^a-z]/g, "").slice(0, MAX_INPUT_LENGTH);
}

export function getCandidates(value) {
  const key = normalize(value);
  // Never fabricate a translation for input this small demo does not know.
  return Object.hasOwn(DEMOS, key) ? DEMOS[key] : [];
}

export function applyKey(value, start, end, key) {
  const text = String(value).slice(0, MAX_INPUT_LENGTH);
  const from = Math.max(0, Math.min(start ?? text.length, text.length));
  const to = Math.max(from, Math.min(end ?? from, text.length));
  if (key === "clear") return { value: "", caret: 0 };
  if (key === "backspace") {
    const removeFrom = from === to ? Math.max(0, from - 1) : from;
    return { value: text.slice(0, removeFrom) + text.slice(to), caret: removeFrom };
  }
  if (!/^[a-z]$/.test(key) || text.length - (to - from) >= MAX_INPUT_LENGTH) {
    return { value: text, caret: from };
  }
  return { value: text.slice(0, from) + key + text.slice(to), caret: from + 1 };
}
