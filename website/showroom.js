import { getCandidates } from './demo-model.mjs';

export const SKINS = {
  cream: { name: '好好原色', colors: ['#f7f2e5', '#a1c6b1', '#eab65d', '#aab4a5', '#453b32', '#e8ece2'] },
  blue: { name: '雾蓝', colors: ['#edf4fa', '#b5cbdc', '#88b5d3', '#8da5b9', '#263e53', '#e2eaf1'] },
  apricot: { name: '暖杏', colors: ['#fff0e4', '#eac4ac', '#e5b490', '#c6ae9f', '#563829', '#f0e0d3'] },
  graphite: { name: '石墨', colors: ['#515b64', '#637e85', '#cbd5cb', '#242b30', '#f7f8f9', '#343d43'] },
  taffy: { name: '永雏塔菲', colors: ['#fff1ed', '#efb9c4', '#f4cd82', '#c7929f', '#633943', '#f2ddd8'], atlas: 'keycap-taffy.webp' },
  raiden: { name: '雷电将军', colors: ['#efe8f8', '#bca6db', '#e1c894', '#9580b0', '#36234d', '#e1d8ed'], atlas: 'keycap-raiden.webp' },
  yasuo: { name: '亚索', colors: ['#e7f0ef', '#9fbfc5', '#dfc78f', '#7c9ca7', '#1a3a45', '#dce5e3'], atlas: 'keycap-yasuo.webp' },
  nailong: { name: '奶龙', colors: ['#fff7df', '#edcf7e', '#efbe60', '#bb9d5c', '#543c21', '#f7e6b5'], atlas: 'keycap-nailong.webp' },
  kun: { name: '蔡徐坤', colors: ['#f4f2ec', '#bfc2c4', '#e6b488', '#8c8f91', '#282b2d', '#dedfdb'], atlas: 'keycap-kun.webp' },
  lanyangyang: { name: '懒羊羊', colors: ['#fff7e8', '#efd8b4', '#ecbd80', '#bc9d7a', '#574024', '#f1e4cf'], atlas: 'keycap-lanyangyang.webp' },
};
export const KEY_ROWS = [
  [...'QWERTYUIOP'], [...'ASDFGHJKL'],
  ['SHIFT', ...'ZXCVBNM', 'BACKSPACE'], ['LANG', ',', 'SPACE', '.', 'ENTER'],
];
const labels = { SHIFT: '大写', BACKSPACE: '退格', LANG: '中 / EN', SPACE: '空格', ENTER: '回车' };
const widths = { SHIFT: 1.4, BACKSPACE: 1.4, LANG: 1.6, SPACE: 4.4, ENTER: 1.8 };
const LIMIT = 280;
const segmenter = new Intl.Segmenter('zh', { granularity: 'grapheme' });

export function editStudioText(value, start, end, key, uppercase = false) {
  const from = Math.max(0, Math.min(start ?? value.length, value.length));
  const to = Math.max(from, Math.min(end ?? from, value.length));
  if (key === 'BACKSPACE') {
    const previous = [...segmenter.segment(value.slice(0, from))].at(-1)?.index ?? 0;
    const removeFrom = from === to ? previous : from;
    return { value: value.slice(0, removeFrom) + value.slice(to), caret: removeFrom };
  }
  const insert = key === 'SPACE' ? ' ' : key === 'ENTER' ? '\n' : /^[A-Z]$/.test(key) ? uppercase ? key : key.toLowerCase() : /^[,.]$/.test(key) ? key : '';
  if (!insert || value.length - (to - from) + insert.length > LIMIT) return { value, caret: from };
  return { value: value.slice(0, from) + insert + value.slice(to), caret: from + insert.length };
}

function mountStudio(root) {
  // All markup below is fixed product copy. User input only goes into value/textContent.
  root.className = 'skin-studio interactive-studio';
  root.innerHTML = `
    <div class="studio-heading"><h2 id="skin-title">把喜欢，放在指尖。</h2><p>选一款皮肤，亲手敲几个字。</p></div>
    <div class="studio-themes" role="group" aria-label="键盘配色"></div>
    <div class="studio-stage">
      <div class="studio-topline"><h3 id="skin-name"></h3><span class="studio-mode">中文拼音</span></div>
      <div class="studio-editor"><label for="skin-input">试试这把键盘</label><button class="studio-clear" type="button">清空</button>
        <textarea id="skin-input" rows="2" maxlength="280" spellcheck="false" autocapitalize="off" autocomplete="off" placeholder="点击键帽，写点什么…" aria-describedby="studio-help"></textarea>
        <div class="studio-candidates" aria-label="拼音候选"></div>
      </div>
      <div class="studio-perspective"><div class="studio-board" role="group" aria-label="可点击的立体键盘"></div></div>
      <p id="studio-help">点按键帽或直接输入。中文模式可试 nihao、xuexi、zhongwen，空格选词。</p>
    </div>
    <div class="studio-skins"><p>创意皮肤预览</p><div class="studio-characters" role="group" aria-label="角色皮肤"></div></div>
    <p class="studio-disclosure">仅网页体验，不保存输入。角色皮肤为同人设计，暂未内置 App，非官方联名。</p>
    <span id="skin-status" class="sr-only" role="status"></span>`;
  const input = root.querySelector('#skin-input');
  const board = root.querySelector('.studio-board');
  const candidateList = root.querySelector('.studio-candidates');
  const status = root.querySelector('#skin-status');
  const buttons = new Map();
  let uppercase = false, chinese = true, composing = false, pressTimer;
  function press(key) {
    clearTimeout(pressTimer);
    buttons.forEach(button => button.classList.remove('is-down'));
    buttons.get(key)?.classList.add('is-down');
    pressTimer = setTimeout(() => buttons.get(key)?.classList.remove('is-down'), 140);
  }
  function composition() {
    if (!chinese || composing || input.selectionStart !== input.selectionEnd) return null;
    const prefix = input.value.slice(0, input.selectionStart);
    const match = prefix.match(/[a-z]+$/i);
    if (!match) return null;
    const candidates = getCandidates(match[0]);
    return candidates.length ? { start: prefix.length - match[0].length, end: prefix.length, candidates } : null;
  }
  function commit(candidate) {
    const range = composition();
    if (!range) return;
    input.setRangeText(candidate.chinese, range.start, range.end, 'end');
    status.textContent = `已输入${candidate.chinese}`;
    renderCandidates();
  }
  function renderCandidates() {
    candidateList.replaceChildren();
    const range = composition();
    if (range) range.candidates.forEach(candidate => {
      const button = document.createElement('button');
      button.type = 'button'; button.textContent = `${candidate.chinese}${candidate.english ? ' · ' + candidate.english : ''}`;
      button.addEventListener('mousedown', event => event.preventDefault());
      button.addEventListener('click', () => commit(candidate));
      candidateList.append(button);
    });
    else {
      const hint = document.createElement('span');
      hint.textContent = chinese ? '三组拼音示例 · 你好 / 学习 / 中文' : 'English · 直接输入英文';
      candidateList.append(hint);
    }
  }
  function activate(key) {
    if (composing) return;
    press(key);
    if (key === 'SHIFT') { uppercase = !uppercase; buttons.get(key).setAttribute('aria-pressed', String(uppercase)); }
    else if (key === 'LANG') {
      chinese = !chinese; buttons.get(key).setAttribute('aria-pressed', String(!chinese));
      root.querySelector('.studio-mode').textContent = chinese ? '中文拼音' : 'English';
      status.textContent = chinese ? '已切换到中文拼音' : '已切换到英文';
    } else if ((key === 'SPACE' || key === 'ENTER') && composition()) commit(composition().candidates[0]);
    else {
      const next = editStudioText(input.value, input.selectionStart, input.selectionEnd, key, uppercase);
      input.value = next.value; input.setSelectionRange(next.caret, next.caret);
      status.textContent = input.value ? `已输入：${input.value}` : '输入已清空';
    }
    buttons.forEach((button, id) => { if (/^[A-Z]$/.test(id)) button.querySelector('.studio-legend').textContent = uppercase ? id : id.toLowerCase(); });
    renderCandidates();
  }
  KEY_ROWS.forEach(keys => {
    const row = document.createElement('div'); row.className = 'studio-key-row';
    keys.forEach(key => {
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'studio-key'; button.dataset.studioKey = key;
      button.style.flex = String(widths[key] ?? 1);
      button.setAttribute('aria-label', labels[key] ?? key);
      if (['SHIFT', 'LANG'].includes(key)) button.setAttribute('aria-pressed', 'false');
      const face = document.createElement('span'); face.className = 'studio-key-face';
      const legend = document.createElement('span'); legend.className = 'studio-legend'; legend.textContent = labels[key] ?? key.toLowerCase();
      face.append(legend); button.append(face);
      button.addEventListener('mousedown', event => event.preventDefault());
      button.addEventListener('click', () => activate(key));
      row.append(button); buttons.set(key, button);
    });
    board.append(row);
  });
  function setSkin(id) {
    const skin = SKINS[id];
    root.dataset.skin = id;
    ['key', 'accent', 'enter', 'edge', 'ink', 'bg'].forEach((name, index) => root.style.setProperty('--studio-' + name, skin.colors[index]));
    root.style.setProperty('--studio-art', skin.atlas ? `url("assets/${skin.atlas}")` : 'none');
    root.dataset.art = String(!!skin.atlas);
    root.querySelector('#skin-name').textContent = skin.name;
    root.querySelectorAll('[data-skin-choice]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.skinChoice === id)));
    status.textContent = `已切换到${skin.name}皮肤`;
  }
  Object.entries(SKINS).forEach(([id, skin]) => {
    const button = document.createElement('button'); button.type = 'button'; button.dataset.skinChoice = id;
    const sample = document.createElement('span'); sample.className = 'studio-swatch'; sample.setAttribute('aria-hidden', 'true');
    sample.style.backgroundColor = skin.colors[1];
    if (skin.atlas) sample.style.backgroundImage = `url("assets/${skin.atlas}")`;
    const name = document.createElement('span'); name.textContent = skin.name;
    button.append(sample, name); button.addEventListener('click', () => setSkin(id));
    root.querySelector(skin.atlas ? '.studio-characters' : '.studio-themes').append(button);
  });
  root.querySelector('.studio-clear').addEventListener('click', () => { input.value = ''; renderCandidates(); status.textContent = '输入已清空'; });
  input.addEventListener('compositionstart', () => { composing = true; renderCandidates(); });
  input.addEventListener('compositionend', () => { composing = false; renderCandidates(); });
  input.addEventListener('input', renderCandidates);
  input.addEventListener('click', renderCandidates);
  input.addEventListener('keyup', renderCandidates);
  input.addEventListener('keydown', event => {
    if (composing || event.isComposing || event.ctrlKey || event.metaKey || event.altKey) return;
    const key = { ' ': 'SPACE', Enter: 'ENTER', Backspace: 'BACKSPACE', Shift: 'SHIFT' }[event.key] ?? event.key.toUpperCase();
    press(key);
    if ((key === 'SPACE' || key === 'ENTER') && composition()) { event.preventDefault(); commit(composition().candidates[0]); }
  });
  window.addEventListener('pagehide', () => { clearTimeout(pressTimer); buttons.forEach(button => button.classList.remove('is-down')); });
  setSkin('taffy'); renderCandidates();
}

if (typeof document !== 'undefined') {
  const studio = document.querySelector('#skin-studio');
  if (studio) mountStudio(studio);
}
