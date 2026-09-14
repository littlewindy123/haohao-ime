// CI: evaluate this file as a function and call it with an existing Playwright Page.
// Requires Chromium with WebGL enabled; never launches a browser or downloads an APK.
// Pass { baseURL, screenshotDir } for a blank page or a different artifact directory.
async (page, { baseURL, screenshotDir = 'work' } = {}) => {
  const base = baseURL ?? await page.evaluate(() => new URL('./', location.href).href);
  const results = [], errors = [];
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const same = (actual, expected, message) => assert(JSON.stringify(actual) === JSON.stringify(expected), message + ': ' + JSON.stringify(actual));
  const keyIds = [...'QWERTYUIOPASDFGHJKL', 'SHIFT', ...'ZXCVBNM', 'BACKSPACE', 'LANG', ',', 'SPACE', '.', 'ENTER'];
  const skinIds = ['cream', 'blue', 'apricot', 'graphite', 'taffy', 'raiden', 'yasuo', 'nailong', 'kun', 'lanyangyang'];
  const errorListener = error => errors.push(error.message);
  const screenshot = (target, name, fullPage = false) => screenshotDir ? target.screenshot({ path: screenshotDir + '/' + name + '.png', fullPage }) : undefined;
  const centered = locator => locator.evaluate(element => element.scrollIntoView({ block: 'center', behavior: 'instant' }));
  const mediaRequest = url => /\/demo-[^/]+\.(?:webm|mp4)(?:\?|$)/.test(url);
  async function completeBoard(target, selector, attribute) {
    const ids = await target.locator(selector).evaluateAll((keys, name) => keys.map(key => key.getAttribute(name)), attribute);
    same(ids, keyIds, 'Complete 33-key board: ' + selector);
  }
  async function heroFallback(target) {
    assert(await target.locator('#keyboard-scene > .flat-board').count() === 1, 'Exactly one hero fallback board');
    await completeBoard(target, '#keyboard-scene .flat-key', 'data-key');
    assert(await target.locator('#keyboard-scene > .flat-board').isVisible(), 'Complete hero fallback is visible');
  }
  async function posterReady(target) {
    assert(await target.locator('[data-demo-video]').count() === 3, 'Three product story videos');
    const valid = await target.locator('[data-demo-video]').evaluateAll(async videos => {
      return (await Promise.all(videos.map(video => new Promise(resolve => {
        const image = new Image(); image.onload = () => resolve(image.naturalWidth > 0); image.onerror = () => resolve(false); image.src = video.poster;
      })))).every(Boolean);
    });
    assert(valid, 'All three story posters decode');
  }
  async function editorBasics(target) {
    const input = target.getByRole('textbox', { name: '试试这把键盘' });
    const key = id => target.locator('[data-studio-key="' + id + '"]');
    await centered(target.locator('.studio-perspective'));
    await completeBoard(target, 'button[data-studio-key]', 'data-studio-key');
    assert(await key('SHIFT').getAttribute('aria-pressed') === 'false', 'Initial lowercase mode');
    await input.fill('');
    for (const id of ['N', 'I', 'H', 'A', 'O']) await key(id).click();
    assert(await input.inputValue() === 'nihao', 'Virtual keys edit the real textarea');
    assert((await target.locator('.studio-candidates button').first().textContent()).includes('你好'), 'Virtual pinyin candidates');
    await key('SPACE').click(); assert(await input.inputValue() === '你好', 'Virtual space commits candidate');
    for (const [pinyin, chinese, english] of [['nihao', '你好', 'hello'], ['xuexi', '学习', 'study'], ['zhongwen', '中文', 'Chinese']]) {
      await input.fill(pinyin);
      assert((await target.locator('.studio-candidates button').first().textContent()).includes(english), 'Limited example: ' + pinyin);
      await target.locator('.studio-candidates button').first().click();
      assert(await input.inputValue() === chinese, 'Candidate commits into textarea: ' + chinese);
    }
    await input.fill('unknown'); assert(await target.locator('.studio-candidates button').count() === 0, 'Unknown input has no fabricated translation');
    await input.fill('nihao'); await input.press('Space'); assert(await input.inputValue() === '你好', 'Physical space commits pinyin');
    await key('LANG').click(); assert(await key('LANG').getAttribute('aria-pressed') === 'true', 'English mode toggles semantically');
    await input.fill('nihao'); await input.press('Space'); assert(await input.inputValue() === 'nihao ', 'English mode keeps literal input');
    await input.fill('a👨‍👩‍👧‍👦'); await key('BACKSPACE').click(); assert(await input.inputValue() === 'a', 'Backspace removes an entire ZWJ grapheme');
    await input.fill('ab🙂cd'); await input.evaluate(element => element.setSelectionRange(2, 4));
    await key('Q').click(); assert(await input.inputValue() === 'abqcd', 'Virtual key replaces selected Unicode text');
    await key('SHIFT').click(); await key('Q').focus(); await target.keyboard.press('Enter');
    assert(await input.inputValue() === 'abqQcd', 'Keyboard activation and Shift preserve the caret');
    assert(await key('SHIFT').getAttribute('aria-pressed') === 'true', 'Shift reports pressed state');
    await key('SHIFT').click(); await key('LANG').click();
    await input.fill('x'.repeat(280)); await key('Q').click(); assert((await input.inputValue()).length === 280, 'Virtual typing respects the input limit');
    await target.getByRole('button', { name: '清空', exact: true }).click(); assert(await input.inputValue() === '', 'Clear edits actual text');
    return input;
  }
  page.on('pageerror', errorListener);
  try {
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto(base);
    await page.locator('#keyboard-scene.is-ready > canvas').waitFor();
    assert(await page.locator('#keyboard-scene > .flat-board').count() === 1, 'Dynamic hero does not duplicate static board');
    assert(await page.locator('#keyboard-scene [data-static-keyboard]').count() === 0, 'Static board is replaced by complete dynamic fallback');
    await page.waitForFunction(() => Boolean(document.querySelector('#keyboard-scene').dataset.lastKey));
    await page.getByRole('button', { name: '暂停动画', exact: true }).click();
    assert(await page.locator('#hero-pause').getAttribute('aria-pressed') === 'true', 'Hero pause is accessible');
    await page.waitForTimeout(250); // Finish the final 170ms key press before measuring idle rendering.
    const idleFrames = await page.locator('#keyboard-scene').getAttribute('data-rendered-frames');
    await page.waitForTimeout(250);
    assert(await page.locator('#keyboard-scene').getAttribute('data-rendered-frames') === idleFrames, 'Paused hero does not continuously render');
    await page.getByRole('button', { name: '继续动画', exact: true }).click();
    await screenshot(page, 'website-desktop');
    results.push('Hero real WebGL, complete single fallback, automatic typing, accessible pause, idle rendering');

    await posterReady(page);
    const videoLabels = await page.locator('[data-demo-video]').evaluateAll(videos => videos.map(video => ({
      label: video.getAttribute('aria-label'), muted: video.muted, loop: video.loop, inline: video.playsInline, preload: video.preload,
      sources: [...video.querySelectorAll('source')].map(source => source.dataset.src),
    })));
    same(videoLabels.map(video => video.label), ['整句输入实录', '收藏单词实录', '背单词实录'], 'Story order and accessible labels');
    assert(videoLabels.every(video => video.muted && video.loop && video.inline && video.preload === 'none' && video.sources.length === 2), 'Videos are muted, inline, poster-first and have both formats');
    for (const video of await page.locator('[data-demo-video]').all()) {
      await centered(video);
      const label = await video.getAttribute('aria-label');
      await page.waitForFunction(label => { const video = [...document.querySelectorAll('[data-demo-video]')].find(element => element.getAttribute('aria-label') === label); return video.readyState >= 2 && !video.paused; }, label);
      const toggle = video.locator('..').locator('.video-toggle');
      await toggle.click(); assert(await video.evaluate(element => element.paused), 'Story pause button');
      await toggle.click();
      await page.waitForFunction(label => ![...document.querySelectorAll('[data-demo-video]')].find(element => element.getAttribute('aria-label') === label).paused, label);
    }
    await centered(page.locator('#keyboard-scene'));
    await page.waitForFunction(() => [...document.querySelectorAll('[data-demo-video]')].every(video => video.paused));
    results.push('Three story posters, actual playback, controls, offscreen pause');

    await centered(page.locator('.studio-perspective'));
    await page.locator('.studio-perspective.is-ready > canvas').waitFor();
    const input = await editorBasics(page);
    await input.fill('保留🙂文字'); await input.evaluate(element => element.setSelectionRange(2, 4));
    same(await page.locator('[data-skin-choice]').evaluateAll(buttons => buttons.map(button => button.dataset.skinChoice)), skinIds, 'Ten original skin choices');
    for (const id of skinIds) {
      await page.locator('[data-skin-choice="' + id + '"]').click();
      await centered(page.locator('.studio-perspective'));
      await page.waitForFunction(() => document.querySelector('.studio-perspective').dataset.textureState === 'ready');
      assert(await page.locator('#skin-studio').getAttribute('data-skin') === id, 'Selected skin: ' + id);
      assert(await page.locator('[data-skin-choice][aria-pressed="true"]').count() === 1, 'Exactly one selected skin');
      same(await input.evaluate(element => [element.value, element.selectionStart, element.selectionEnd]), ['保留🙂文字', 2, 4], 'Skin preserves text and selection');
    }
    assert(await page.locator('.studio-perspective > canvas').count() === 1, 'Skin changes reuse one renderer');
    await screenshot(page, 'website-desktop-full', true);
    results.push('33 semantic WebGL keys, real editing, selection, Unicode deletion, 3 pinyin examples, English/Shift, ten skins');

    for (const colorScheme of ['light', 'dark']) {
      await page.emulateMedia({ colorScheme });
      for (const width of [320, 390, 768, 1024, 1440]) {
        const height = width < 760 ? 844 : 1000;
        await page.setViewportSize({ width, height }); await page.goto(base);
        await page.locator('#keyboard-scene.is-ready > canvas').waitFor();
        const layout = await page.evaluate(() => ({
          overflow: document.documentElement.scrollWidth > innerWidth + 1,
          ctas: [...document.querySelectorAll('.hero-actions a')].map(link => { const rect = link.getBoundingClientRect(); return rect.top >= 0 && rect.bottom <= innerHeight; }),
          broken: [...document.images].filter(image => image.loading !== 'lazy' && (!image.complete || !image.naturalWidth)).length,
        }));
        assert(!layout.overflow && !layout.broken, 'Layout/eager images: ' + width + 'px ' + colorScheme);
        assert(layout.ctas.length === 2 && layout.ctas.every(Boolean), 'First-viewport CTAs: ' + width + 'px ' + colorScheme);
        if (width === 390) await screenshot(page, 'website-mobile-' + colorScheme);
        await centered(page.locator('.studio-perspective')); await page.locator('.studio-perspective.is-ready > canvas').waitFor();
        const targets = await page.locator('[data-studio-key]').evaluateAll(keys => keys.map(key => { const rect = key.getBoundingClientRect(); return { width: rect.width, height: rect.height, left: rect.left, right: rect.right }; }));
        assert(targets.length === 33 && targets.every(rect => rect.width >= 24 && rect.height >= 24 && rect.left >= 0 && rect.right <= width), 'Complete touch targets: ' + width + 'px ' + colorScheme);
        assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1), 'Showroom has no horizontal overflow');
        if (width === 390) await screenshot(page, 'website-mobile-' + colorScheme + '-full', true);
      }
    }
    results.push('5 widths × light/dark, first-viewport CTAs, full 24px-minimum keyboard targets, no overflow');

    const browser = page.context().browser(); assert(browser, 'CI requires a Playwright Browser for isolated fault contexts');
    for (const [name, options] of [['no-js', { javaScriptEnabled: false }], ['reduced-motion', { reducedMotion: 'reduce' }], ['save-data', {}], ['no-webgl', {}], ['module-failure', {}], ['media-failure', {}]]) {
      const context = await browser.newContext({ viewport: { width: 390, height: 844 }, colorScheme: 'light', ...options });
      try {
        const fallback = await context.newPage(), requested = [];
        fallback.on('request', request => requested.push(request.url()));
        if (name === 'no-webgl') await fallback.addInitScript(() => {
          const get = HTMLCanvasElement.prototype.getContext;
          HTMLCanvasElement.prototype.getContext = function(type, ...args) { return /webgl/.test(type) ? null : get.call(this, type, ...args); };
        });
        if (name === 'save-data') await fallback.addInitScript(() => Object.defineProperty(navigator, 'connection', { configurable: true, value: { saveData: true, addEventListener() {}, removeEventListener() {} } }));
        if (name === 'module-failure') await fallback.route('**/scene-3d.min.js*', route => route.abort());
        if (name === 'media-failure') await fallback.route(/\/assets\/demo-[^/]+\.(?:webm|mp4)(?:\?.*)?$/, route => route.abort());
        await fallback.goto(base);
        if (name !== 'no-js') await fallback.locator('#skin-input').waitFor({ state: 'attached' });
        if (name !== 'media-failure') {
          await heroFallback(fallback);
          assert(!await fallback.locator('#keyboard-scene').evaluate(element => element.classList.contains('is-ready')), name + ': visible DOM mode');
        }
        assert(await fallback.locator('.hero-actions a[download]').isVisible(), name + ': usable download link');
        await posterReady(fallback);
        if (name === 'no-js') {
          assert(await fallback.locator('[data-static-keyboard]').count() === 2, 'Both server-rendered keyboards survive without JS');
          await completeBoard(fallback, '#skin-studio .flat-key', 'data-key');
          assert(await fallback.locator('#skin-studio .static-studio').isVisible(), 'No-JS studio stays complete');
        } else if (name !== 'media-failure') {
          await editorBasics(fallback);
          assert(!await fallback.locator('.studio-perspective').evaluate(element => element.classList.contains('is-ready')), name + ': interactive DOM keyboard');
        }
        if (['no-js', 'reduced-motion', 'save-data'].includes(name)) {
          await centered(fallback.locator('[data-demo-video]').first());
          await fallback.waitForTimeout(250); // Let intersection callbacks run before testing suppressed optional work.
          assert(!requested.some(url => url.includes('scene-3d.min.js')), name + ': no 3D download');
          assert(!requested.some(mediaRequest), name + ': no automatic media download');
          assert(await fallback.locator('[data-demo-video] source[src]').count() === 0, name + ': video sources remain lazy');
        }
        if (name === 'media-failure') {
          const attempted = requested.some(mediaRequest) ? Promise.resolve() : fallback.waitForRequest(request => mediaRequest(request.url()));
          await centered(fallback.locator('[data-demo-video]').first()); await attempted;
          assert(await fallback.locator('.video-toggle').first().isVisible(), 'Failed media retains manual controls');
          assert(await fallback.locator('[data-demo-video]').first().evaluate(video => Boolean(video.poster)), 'Failed media retains its decoded poster');
        }
        await screenshot(fallback, 'website-fallback-' + name); results.push(name + ' fallback');
      } finally { await context.close(); }
    }
    assert(errors.length === 0, 'Unexpected errors in normal mode: ' + errors.join('; '));
    return results;
  } finally {
    page.off('pageerror', errorListener);
    await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
    await page.setViewportSize({ width: 1440, height: 1000 }); await page.goto(base);
  }
}
