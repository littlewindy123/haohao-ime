async page => {
  const assert = (value, message) => { if (!value) throw new Error(message); };
  const results = [];
  const url = page.url().split('#')[0];
  await page.goto(url + '#skin-studio');
  await page.reload();
  const input = page.locator('#skin-input');
  const key = id => page.locator(`[data-studio-key="${id}"]`);
  await page.setViewportSize({ width:1440, height:1080 });
  for (const letter of 'QWERTYUIOPASDFGHJKLZXCVBNM') await key(letter).click();
  assert(await input.inputValue() === 'qwertyuiopasdfghjklzxcvbnm', 'Every physical key must type its own letter');
  await page.locator('.studio-clear').click();
  for (const letter of 'NIHAO') await key(letter).click();
  assert(await input.inputValue() === 'nihao', 'Typed pinyin');
  await key('SPACE').click(); assert(await input.inputValue() === '你好', 'Space commits the displayed Chinese candidate');
  await key('BACKSPACE').click(); assert(await input.inputValue() === '你', 'Backspace deletes the previous character');
  await key('LANG').click(); await key('SHIFT').click(); await key('A').click(); await key('ENTER').click();
  assert(await input.inputValue() === '你A\n', 'Language, case and enter are functional');
  await input.fill('ab👨‍👩‍👧'); await key('BACKSPACE').click(); assert(await input.inputValue() === 'ab', 'Delete a whole grapheme');
  await input.evaluate(el => el.setSelectionRange(0, 2)); await key('Q').click(); assert(await input.inputValue() === 'Q', 'Selection replacement');
  await key('SHIFT').focus(); await page.keyboard.press('Enter'); await key('W').focus(); await page.keyboard.press('Space');
  assert(await input.inputValue() === 'Qw', 'Native button keyboard activation');
  await input.fill('keyboard'); await input.press('End'); await input.pressSequentially(' works');
  assert(await input.inputValue() === 'keyboard works', 'Native physical keyboard input');
  results.push('typing, candidate commit, delete, selection, shift, mode, space, enter, keyboard accessibility');
  for (const width of [320,390,768,1440]) for (const scheme of ['light','dark']) {
    await page.setViewportSize({width,height:900}); await page.emulateMedia({colorScheme:scheme});
    for (const id of ['cream','blue','apricot','graphite','taffy','raiden','yasuo','nailong','kun','lanyangyang']) {
      await page.locator(`[data-skin-choice="${id}"]`).click();
      assert(await input.inputValue() === 'keyboard works', 'Changing skins must preserve input');
      assert(await page.locator(`[data-skin-choice="${id}"]`).getAttribute('aria-pressed') === 'true', 'Selected skin is exposed');
      const geometry = await page.locator('.studio-key').evaluateAll(els => ({
        overflow:document.documentElement.scrollWidth > innerWidth,
        minWidth:Math.min(...els.map(el => el.getBoundingClientRect().width)),
        clipped:els.some(el => {const b=el.getBoundingClientRect();return b.left<0 || b.right>innerWidth;})
      }));
      assert(!geometry.overflow && !geometry.clipped && geometry.minWidth >= 24, `Key geometry ${width}/${scheme}/${id}: ${JSON.stringify(geometry)}`);
    }
    results.push(`${width}px ${scheme}: all 10 skins, no overflow, keys >=24px wide`);
  }
  const context = await page.context().browser().newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true,reducedMotion:'reduce'});
  await context.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function(type,...args) { return /webgl/.test(type) ? null : original.call(this,type,...args); };
  });
  const touch = await context.newPage();
  try {
    await touch.goto(url + '#skin-studio');
    await touch.locator('[data-studio-key="N"]').tap(); await touch.locator('[data-studio-key="I"]').tap();
    assert(await touch.locator('#skin-input').inputValue() === 'ni', 'Touch works without WebGL with reduced motion');
    assert(await touch.locator('#skin-input').evaluate(el=>el!==document.activeElement), 'Virtual typing does not summon the phone keyboard');
    results.push('touch, reduced motion, no WebGL, no forced input focus');
  } finally { await context.close(); }
  await page.setViewportSize({width:1440,height:1080}); await page.emulateMedia({colorScheme:'light'});
  await page.locator('[data-skin-choice="taffy"]').click();
  await page.locator('#skin-studio').screenshot({path:'haohao-studio-final.png'});
  await page.setViewportSize({width:390,height:844});
  await page.locator('#skin-studio').screenshot({path:'haohao-studio-final-mobile.png'});
  return results;
}
