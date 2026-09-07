// Run this function with the Playwright browser tool against a local or staged site.
async (page) => {
  const base = await page.evaluate(() => location.origin + '/');
  const results = [];
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const settle = () => page.waitForTimeout(750);
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(base);
  await page.locator('#keyboard-scene.is-ready').waitFor();
  await settle();
  for (const [value, translation] of [['nihao', 'hello'], ['xuexi', 'study'], ['zhongwen', 'Chinese']]) {
    await page.locator(`[data-hero-example="${value}"]`).click();
    await page.locator('#collect-demo:not(:disabled)').waitFor();
    assert(await page.locator('#hero-english').textContent() === translation, 'Hero example: ' + value);
    await page.locator('#collect-demo').click();
    assert((await page.locator('#saved-word').textContent()).includes(translation), 'Collection illustration');
  }
  await page.locator('[data-hero-example="nihao"]').focus();
  await page.keyboard.press('Enter');
  await settle();
  assert(await page.locator('#hero-english').textContent() === 'hello', 'Keyboard access to hero');
  await page.screenshot({ path: 'work/website-desktop.png' });
  results.push('3 hero examples, collection reset, keyboard activation');
  for (const [value, translation] of [['nihao', 'hello'], ['xuexi', 'study'], ['zhongwen', 'Chinese']]) {
    await page.locator(`[data-example="${value}"]`).click();
    await settle();
    assert(await page.locator('.candidate-item span').first().textContent() === translation, 'Candidate: ' + value);
  }
  await page.locator('#phonetic-toggle').uncheck();
  assert(await page.locator('.candidate-item small').first().textContent() === '', 'IPA off');
  await page.locator('#phonetic-toggle').check();
  assert((await page.locator('.candidate-item small').first().textContent()).length > 0, 'IPA on');
  await page.locator('#pinyin-input').fill('unknown');
  assert((await page.locator('#candidate-list').textContent()).includes('尚未收录'), 'Unknown input');
  await page.locator('[data-key="clear"]').click();
  await page.locator('[data-key="n"]').click();
  await page.locator('[data-key="i"]').click();
  assert(await page.locator('#pinyin-input').inputValue() === 'ni', 'Virtual keys');
  await page.locator('[data-key="backspace"]').click();
  assert(await page.locator('#pinyin-input').inputValue() === 'n', 'Backspace');
  await page.locator('[data-example="nihao"]').click();
  results.push('3 pinyin examples, IPA, unknown input, virtual keys');
  for (const figure of await page.locator('.learning-image').all()) {
    await figure.scrollIntoViewIfNeeded(); await settle();
    await figure.locator('img').evaluate(img => img.decode());
  }
  await page.screenshot({ path: 'work/website-desktop-full.png', fullPage: true });
  for (const colorScheme of ['light', 'dark']) {
    await page.emulateMedia({ colorScheme });
    for (const width of [320, 390, 768, 1024, 1440]) {
      await page.setViewportSize({ width, height: width < 760 ? 844 : 900 });
      await page.goto(base); await settle();
      const layout = await page.evaluate(() => ({
        overflow: document.documentElement.scrollWidth > innerWidth,
        ctas: [...document.querySelectorAll('.hero-actions a')].map(a => a.getBoundingClientRect().bottom),
        broken: [...document.images].filter(i => i.loading !== 'lazy' && (!i.complete || !i.naturalWidth)).length,
      }));
      assert(!layout.overflow && !layout.broken, `Layout/images ${width} ${colorScheme}`);
      assert(layout.ctas.every(bottom => bottom < (width < 760 ? 844 : 900)), 'First viewport CTAs');
      if (width === 390) {
        await page.screenshot({ path: `work/website-mobile-${colorScheme}.png` });
        for (const figure of await page.locator('.learning-image').all()) {
          await figure.scrollIntoViewIfNeeded(); await settle(); await figure.locator('img').evaluate(img => img.decode());
        }
        await page.screenshot({ path: `work/website-mobile-${colorScheme}-full.png`, fullPage: true });
      }
    }
  }
  results.push('5 widths × light/dark, visible CTAs, no overflow or broken eager images');
  const variants = [
    ['no-js', { javaScriptEnabled: false }],
    ['reduced-motion', { reducedMotion: 'reduce' }],
    ['no-webgl', {}], ['save-data', {}], ['module-failure', {}],
  ];
  for (const [name, options] of variants) {
    const context = await page.context().browser().newContext({ viewport: { width: 390, height: 844 }, ...options });
    const fallback = await context.newPage();
    const requested = [];
    fallback.on('request', req => requested.push(req.url()));
    try {
      if (name === 'no-webgl') await fallback.addInitScript(() => {
        const get = HTMLCanvasElement.prototype.getContext;
        HTMLCanvasElement.prototype.getContext = function(type, ...args) { return /webgl/.test(type) ? null : get.call(this, type, ...args); };
      });
      if (name === 'save-data') await fallback.addInitScript(() => Object.defineProperty(navigator, 'connection', { value: { saveData: true } }));
      if (name === 'module-failure') await fallback.route('**/scene-3d.min.js*', route => route.abort());
      await fallback.goto(base); await fallback.waitForTimeout(600);
      assert(!await fallback.locator('#keyboard-scene').evaluate(el => el.classList.contains('is-ready')), name + ': static mode');
      assert(await fallback.locator('.scene-still').evaluate(img => img.complete && img.naturalWidth > 0 && getComputedStyle(img).opacity === '1'), name + ': poster');
      assert(await fallback.locator('.hero-actions a[download]').isVisible(), name + ': download');
      if (name === 'no-js' || name === 'reduced-motion' || name === 'save-data') assert(!requested.some(url => url.includes('scene-3d.min.js')), name + ': no 3D download');
      if (name !== 'no-js') {
        await fallback.locator('[data-hero-example="xuexi"]').click();
        await fallback.locator('#collect-demo:not(:disabled)').waitFor();
        await fallback.locator('#collect-demo').click();
        assert((await fallback.locator('#saved-word').textContent()).includes('study'), name + ': controls');
      }
      await fallback.screenshot({ path: `work/website-fallback-${name}.png` });
    } finally { await context.close(); }
    results.push(name + ' fallback');
  }
  await page.emulateMedia({ colorScheme: 'light', reducedMotion: 'no-preference' });
  await page.setViewportSize({ width: 1440, height: 1000 });
  await page.goto(base);
  return results;
}
