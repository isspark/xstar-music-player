// node prototypes/verify-song-linked-buttons.cjs
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(`${__dirname}/song-linked-buttons-100.html`, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
let calls = 0;
const ctx = new Proxy({ createLinearGradient: () => ({ addColorStop() {} }) }, {
  get: (object, key) => key in object ? object[key] : (...args) => {
    calls++;
    for (const arg of args) if (typeof arg === 'number') assert.ok(Number.isFinite(arg), `${key}: non-finite coordinate`);
  },
  set: (object, key, value) => { object[key] = value; return true; }
});
vm.runInNewContext(script.split('const observer=new IntersectionObserver')[0] + `
  assert.equal(families.length, 20);
  assert.equal(designs.length, 100);
  assert.equal(new Set(designs.map(d=>d.name)).size, 100);
  assert.equal(first('  夜航'), '夜');
  assert.equal(first('Golden Road'), 'G');
  assert.equal(first(''), '♪');
  assert.equal(first('🎵 ！'), '♪');
  assert.equal(hash('夜航'), hash('夜航'));
  assert.notEqual(hash('夜航'), hash('蓝海'));
  let renders=0;
  for(const title of ['夜航星河','雨落蓝海','春风红花','Golden Road','','🎵']) {
    song={...songs[renders%4],title};
    for(progress of [0,.119,.38,.67,.8,.93,1]) {
      const m=model();
      assert.ok(m.index>=0&&m.index<song.lyrics.length);
      assert.ok(m.local>=0&&m.local<=1);
      for(const d of designs) for(const mode of [false,true]) {
        light=mode;
        paint({ctx,d,dpr:1,w:300,h:245,x:230,y:177,pop:.4},3,m);
        renders++;
      }
    }
  }
  console.log('PASS: 100 unique designs / 20 families / '+renders+' render checks, including empty and English titles, boundaries and both backgrounds.');
`, { document: {}, localStorage: { getItem: () => null }, matchMedia: () => ({ matches: false }), ctx, assert, console });
assert.ok(calls > 0);
