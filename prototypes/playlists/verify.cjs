// node prototypes/playlists/verify.cjs <path-to-playwright-module>
const { chromium } = require(process.argv[2] || 'playwright');
const { pathToFileURL } = require('node:url');
const path = require('node:path');
const fs = require('node:fs');
const assert = require('node:assert/strict');
(async () => {
  const browser = await chromium.launch({ headless: true, channel: 'msedge' });
  const page = await browser.newPage({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 1 });
  const errors = [];
  page.on('pageerror', e => errors.push(e.message));
  const click = action => page.locator(`[data-action="${action}"]`).first().click();
  for (const file of fs.readdirSync(__dirname).filter(n => /^\d\d-.*\.html$/.test(n))) {
    await page.goto(pathToFileURL(path.join(__dirname, file)).href);
    assert.equal(await page.locator('.list-card').count(), 5, file);
    if (Number(file.slice(0, 2)) > 10) {
      const cycle = page.locator('[data-action="cycle-focus"]');
      if (await cycle.count()) {
        await cycle.click();
        assert.match(await page.locator('.bold-stage').textContent(), /把周末拉长/);
      }
      const dot = page.locator('[data-action="radar-focus"][data-index="2"]');
      if (await dot.count()) {
        await dot.click();
        assert.match(await page.locator('.radar-readout').textContent(), /有风经过/);
      }
      const surprise = page.locator('[data-action="surprise"]');
      if (await surprise.count()) {
        await surprise.click();
        assert.equal(await page.locator('.detail-heading').count(), 1);
        await click('back');
      }
    }
    for (const width of [320, 390, 430]) {
      await page.setViewportSize({ width, height: 844 });
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `Horizontal overflow: ${file} / ${width}`);
    }
    await page.locator('.list-card').first().click();
    assert.equal(await page.locator('.track').count(), 6);
    await click('edit-tracks');
    await click('down');
    assert.match(await page.locator('.track-main b').first().textContent(), /日落大道/);
    await click('remove-track');
    assert.equal(await page.locator('.track').count(), 5);
    await click('add-tracks');
    await page.locator('.add-check').first().check();
    await click('commit-add');
    assert.equal(await page.locator('.track').count(), 6);
    await click('play-all');
    await click('queue');
    assert.equal(await page.locator('.sheet .track').count(), 6);
    await click('confirm-clear');
    await click('clear-queue');
    assert.equal(await page.locator('.sheet .track').count(), 0);
    await click('close');
    await click('theme');
    await page.locator('[data-color="#ffbe5c"]').click();
    await page.locator('[data-action="light"][data-value="true"]').click();
    await page.locator('[data-action="scene"][data-value="Tape"]').click();
    assert.equal(await page.locator('html').getAttribute('data-scene'), 'Tape');
    await click('close');
    await click('back');
    await click('new');
    await page.locator('#playlist-name').fill('测试歌单 <私藏>');
    await page.locator('#name-form button').click();
    assert.equal(await page.locator('.detail-heading h1').textContent(), '测试歌单 <私藏>');
    await click('list-menu');
    await click('rename');
    await page.locator('#playlist-name').fill('改名后的歌单');
    await page.locator('#name-form button').click();
    assert.equal(await page.locator('.detail-heading h1').textContent(), '改名后的歌单');
    await click('list-menu');
    await click('confirm-delete');
    await click('delete-list');
    assert.equal(await page.locator('.list-card').count(), 5);
    await click('search');
    await page.locator('#search').fill('开往');
    assert.equal(await page.locator('.list-card').count(), 1);
    console.log(`PASS ${file}: mobile widths, edit, reorder, add, queue, theme, create, rename, delete, search`);
  }
  await page.goto(pathToFileURL(path.join(__dirname, 'index.html')).href);
  assert.equal(await page.locator('.study').count(), 10);
  await page.locator('iframe').first().waitFor();
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await page.goto(pathToFileURL(path.join(__dirname, 'bold.html')).href);
  assert.equal(await page.locator('.study').count(), 10);
  await page.locator('#scene').click();
  await page.locator('#light').click();
  assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  assert.deepEqual(errors, []);
  await browser.close();
  console.log('PASS: all prototypes and gallery; no browser JavaScript errors.');
})().catch(e => { console.error(e); process.exit(1); });
