import { getCandidates } from './demo-model.mjs';
import { KEY_ROWS as PHYSICAL_ROWS, normalizeKeyboardSkin, readableKeyInk } from './keyboard-model.mjs';

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
export const KEY_ROWS = PHYSICAL_ROWS.map(row => row.map(key => key.id));
const labels = { SHIFT: '大写', BACKSPACE: '退格', LANG: '中 / EN', SPACE: '空格', ENTER: '回车' };
const widths = Object.fromEntries(PHYSICAL_ROWS.flat().map(key => [key.id, key.w]));
const LIMIT = 280;
const segmenter = new Intl.Segmenter('zh', { granularity: 'grapheme' });

export function editStudioText(value, start, end, key, uppercase = false) {
  const from = Math.max(0, Math.min(start ?? value.length, value.length));
  const to = Math.max(from, Math.min(end ?? from, value.length));
  if (key === 'BACKSPACE') {
    const previous = [...segmenter.segment(value)].filter(part => part.index < from).at(-1);
    const removeFrom = from === to ? previous?.index ?? 0 : from;
    const removeTo = from === to && previous ? Math.max(to, previous.index + previous.segment.length) : to;
    return { value: value.slice(0, removeFrom) + value.slice(removeTo), caret: removeFrom };
  }
  const insert = key === 'SPACE' ? ' ' : key === 'ENTER' ? '\n' : /^[A-Z]$/.test(key) ? uppercase ? key : key.toLowerCase() : /^[,.]$/.test(key) ? key : '';
  if (!insert || value.length - (to - from) + insert.length > LIMIT) return { value, caret: from };
  return { value: value.slice(0, from) + insert + value.slice(to), caret: from + insert.length };
}

// Rendering is an optional, lazy enhancement. This controller never owns editor text or selection.
export function createStudioRenderer(host, {
  getSkin, getState, onKey, onLayout, onFallback = () => {}, onPreferenceChange = () => {},
  loadScene = () => import("./vendor/scene-3d.min.js?v=20260915-showroom"),
  environment = window,
} = {}) {
  const doc = environment.document, motion = environment.matchMedia('(prefers-reduced-motion: reduce)');
  const colors = environment.matchMedia('(forced-colors: active)'), connection = environment.navigator?.connection;
  let scene, loading = false, failed = false, gone = false, disposed = false, generation = 0;
  let visible = !environment.IntersectionObserver;
  const enabled = () => !disposed && !gone && visible && !doc.hidden && !motion.matches && !colors.matches && !connection?.saveData;
  function fallback() {
    generation++; scene?.dispose(); scene = undefined; host.classList.remove('is-ready'); host.dataset.renderer = 'dom'; onFallback();
  }
  async function load() {
    if (!enabled() || scene || loading || failed) return;
    loading = true; const token = generation; host.dataset.renderer = 'loading';
    try {
      const { createKeyboardScene } = await loadScene();
      if (!enabled() || token !== generation) return;
      const live = () => !disposed && !gone && token === generation;
      const created = createKeyboardScene(host, {
        interactive: true,
        onKey: key => { if (live()) onKey?.(key); },
        onLayout: layout => { if (live()) onLayout?.(layout); },
        onReady: () => { if (live()) { host.classList.add('is-ready'); host.dataset.renderer = 'webgl'; } },
        onFail: () => { if (live()) { failed = true; fallback(); } },
      });
      if (!live()) { created.dispose(); return; }
      scene = created; scene.setSkin(getSkin()); scene.setState?.(getState());
    } catch {
      if (token === generation && !disposed) { failed = true; fallback(); }
    } finally {
      loading = false;
      if (!scene && !failed && enabled()) load();
    }
  }
  function preferenceChanged() { fallback(); failed = false; onPreferenceChange(); load(); }
  function visibilityChanged() { if (!doc.hidden) load(); }
  function pagehide() { gone = true; fallback(); observer?.disconnect(); }
  function pageshow() { if (disposed) return; gone = false; failed = false; observer?.observe(host); load(); }
  const observer = environment.IntersectionObserver && new environment.IntersectionObserver(entries => {
    visible = entries[0].isIntersecting; if (visible) { onPreferenceChange(); load(); }
  }, { rootMargin: '100px' });
  observer?.observe(host);
  motion.addEventListener('change', preferenceChanged); colors.addEventListener('change', preferenceChanged);
  connection?.addEventListener?.('change', preferenceChanged);
  doc.addEventListener('visibilitychange', visibilityChanged);
  environment.addEventListener('pagehide', pagehide); environment.addEventListener('pageshow', pageshow);
  host.dataset.renderer = 'dom'; load();
  return {
    press(key) { scene?.press(key); },
    setSkin() { scene?.setSkin(getSkin()); load(); },
    setState() { scene?.setState?.(getState()); },
    artworkAllowed() { return !connection?.saveData && visible; },
    dispose() {
      if (disposed) return; disposed = true; fallback(); observer?.disconnect();
      motion.removeEventListener('change', preferenceChanged); colors.removeEventListener('change', preferenceChanged);
      connection?.removeEventListener?.('change', preferenceChanged); doc.removeEventListener('visibilitychange', visibilityChanged);
      environment.removeEventListener('pagehide', pagehide); environment.removeEventListener('pageshow', pageshow);
    },
  };
}

export function mountStudio(root, options = {}) {
  // All markup below is fixed product copy. User input only goes into value/textContent.
  root.className = 'skin-studio interactive-studio';
  root.innerHTML = `
    <div class="studio-heading"><h2 id="skin-title">把喜欢，放在指尖。</h2></div>
    <div class="studio-themes" role="group" aria-label="键盘配色"></div>
    <div class="studio-stage">
      <div class="studio-topline"><h3 id="skin-name"></h3><span class="studio-mode">中文拼音</span></div>
      <div class="studio-editor"><label for="skin-input" class="sr-only">试试这把键盘</label><button class="studio-clear" type="button">清空</button>
        <textarea id="skin-input" rows="2" maxlength="280" spellcheck="false" autocapitalize="off" autocomplete="off" placeholder="点击键帽，写点什么…" aria-describedby="studio-help"></textarea>
        <div id="studio-help" class="studio-candidates" aria-label="拼音候选"></div>
      </div>
      <div class="studio-perspective"><div class="studio-board" role="group" aria-label="可点击的立体键盘"></div></div>
    </div>
    <div class="studio-skins"><p>创意皮肤预览</p><div class="studio-characters" role="group" aria-label="角色皮肤"></div></div>
    <span id="skin-status" class="sr-only" role="status"></span>`;
  const input = root.querySelector('#skin-input');
  const board = root.querySelector('.studio-board');
  const candidateList = root.querySelector('.studio-candidates');
  const status = root.querySelector('#skin-status');
  const buttons = new Map();
  let uppercase = false, chinese = true, composing = false, pressTimer, visual, skinId = 'taffy';
  function press(key) {
    clearTimeout(pressTimer);
    buttons.forEach(button => button.classList.remove('is-down'));
    buttons.get(key)?.classList.add('is-down');
    visual?.press(key);
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
      hint.textContent = chinese ? '网页拼音示例：nihao / xuexi / zhongwen。' : 'English · 直接输入英文';
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
    visual?.setState();
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
    if (!Object.hasOwn(SKINS, id)) return;
    skinId = id;
    const skin = normalizeKeyboardSkin(SKINS[id]);
    root.dataset.skin = id;
    ['key', 'accent', 'enter', 'edge', 'ink', 'bg'].forEach((name, index) => root.style.setProperty('--studio-' + name, skin.colors[index]));
    root.style.setProperty('--studio-function-ink', readableKeyInk(skin.ink, skin.colors[1]));
    root.style.setProperty('--studio-enter-ink', readableKeyInk(skin.ink, skin.colors[2]));
    updateArtwork();
    root.dataset.art = String(!!skin.atlas);
    root.querySelector('#skin-name').textContent = skin.name;
    root.querySelectorAll('[data-skin-choice]').forEach(button => button.setAttribute('aria-pressed', String(button.dataset.skinChoice === id)));
    status.textContent = `已切换到${skin.name}皮肤`;
    visual?.setSkin();
  }
  function updateArtwork() {
    const allowed = visual?.artworkAllowed() ?? false, skin = SKINS[skinId];
    root.style.setProperty('--studio-art', allowed && skin.atlas ? `url("assets/${skin.atlas}")` : 'none');
    root.querySelectorAll('[data-skin-choice]').forEach(button => {
      const atlas = SKINS[button.dataset.skinChoice].atlas;
      button.querySelector('.studio-swatch').style.backgroundImage = allowed && atlas ? `url("assets/${atlas}")` : 'none';
    });
  }
  Object.entries(SKINS).forEach(([id, skin]) => {
    const button = document.createElement('button'); button.type = 'button'; button.dataset.skinChoice = id;
    const sample = document.createElement('span'); sample.className = 'studio-swatch'; sample.setAttribute('aria-hidden', 'true');
    sample.style.backgroundColor = skin.colors[1];
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
  const releasePress = () => { clearTimeout(pressTimer); buttons.forEach(button => button.classList.remove('is-down')); };
  window.addEventListener('pagehide', releasePress);
  visual = createStudioRenderer(root.querySelector('.studio-perspective'), {
    getSkin: () => SKINS[skinId], getState: () => ({ uppercase, chinese }), onKey: activate,
    onLayout: layout => layout.forEach(key => {
      const button = buttons.get(key.id);
      for (const prop of ['x', 'y', 'width', 'height']) button.style.setProperty('--key-' + prop, key[prop] + '%');
    }),
    onPreferenceChange: updateArtwork,
    ...options,
  });
  setSkin('taffy'); renderCandidates();
  return { setSkin, activate, dispose() { releasePress(); visual.dispose(); window.removeEventListener('pagehide', releasePress); } };
}

if (typeof document !== 'undefined') {
  const studio = document.querySelector('#skin-studio');
  if (studio) mountStudio(studio);
}
