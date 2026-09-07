export const KEY_ROWS = [
  [...'QWERTYUIOP'].map(id => ({ id, w: 1 })),
  [...'ASDFGHJKL'].map(id => ({ id, w: 1 })),
  [{ id: 'SHIFT', label: '⇧', w: 1.35 }, ...[...'ZXCVBNM'].map(id => ({ id, w: 1 })), { id: 'BACKSPACE', label: '⌫', w: 1.35 }],
  [{ id: 'LANG', label: '中 / EN', w: 1.55 }, { id: ',', w: 1 }, { id: 'SPACE', label: '', w: 4.35 }, { id: '.', w: 1 }, { id: 'ENTER', label: '↵', w: 1.8 }],
];
export const DECALS = { SPACE: 0, Q: 1, E: 2, P: 3, SHIFT: 3, BACKSPACE: 3, LANG: 3, ENTER: 3 };
export const GREETING = [
  [['你', 'ni'], ['好', 'hao']],
  [['欢', 'huan'], ['迎', 'ying'], ['你', 'ni'], ['来', 'lai'], ['看', 'kan'], ['我', 'wo'], ['的', 'de'], ['作', 'zuo'], ['品', 'pin']],
  [['跪', 'gui'], ['求', 'qiu'], ['s', 's'], ['t', 't'], ['a', 'a'], ['r', 'r'], ['支', 'zhi'], ['持', 'chi']],
];
export function greetingEvents() {
  const events = []; let time = .5;
  GREETING.forEach((sentence, index) => {
    events.push({ time, text: '' }); let text = '';
    sentence.forEach(([word, keys]) => {
      for (const key of keys) { events.push({ time, key: key.toUpperCase() }); time += .095; }
      text += word; events.push({ time, text }); time += .06;
    });
    time += index === 2 ? 3 : 1.5;
    events.push({ time, key: 'ENTER' }); time += .3;
  });
  events.push({ time, text: '' }); return { events, duration: time + .65 };
}
export function mountKeyboard(host, { interactive = false } = {}) {
  const board = document.createElement('div'); board.className = 'flat-board'; if (!interactive) board.setAttribute('aria-hidden', 'true');
  const keys = new Map();
  for (const row of KEY_ROWS) {
    const line = document.createElement('div'); line.className = 'flat-row';
    for (const key of row) {
      const face = document.createElement(interactive ? 'button' : 'span'); face.className = 'flat-key'; face.style.flex = String(key.w); face.dataset.key = key.id;
      if (interactive) { face.type = 'button'; face.setAttribute('aria-label', key.id); face.addEventListener('click', () => press(key.id)); }
      if (DECALS[key.id] !== undefined) { const art = document.createElement('i'); art.className = 'key-decal decal-' + DECALS[key.id]; face.append(art); }
      const label = document.createElement('b'); label.textContent = key.label ?? key.id; face.append(label); line.append(face); keys.set(key.id, face);
    }
    board.append(line);
  }
  host.append(board); let timer;
  function press(key) { clearTimeout(timer); keys.forEach(k => k.classList.remove('pressed')); keys.get(key)?.classList.add('pressed'); host.dataset.lastKey = key; timer = setTimeout(() => keys.get(key)?.classList.remove('pressed'), 160); }
  return {
    setSkin(skin) { board.style.setProperty('--key-art', skin.atlas ? `url("assets/${skin.atlas}")` : 'none'); board.dataset.art = String(Boolean(skin.atlas)); },
    press,
    dispose() { clearTimeout(timer); board.remove(); },
  };
}
