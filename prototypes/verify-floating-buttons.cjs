// Run with: node prototypes/verify-floating-buttons.cjs
const fs = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const html = fs.readFileSync(`${__dirname}/floating-buttons-100.html`, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
new vm.Script(script);
const noop = () => {};
const ctx = new Proxy({ createLinearGradient: () => ({ addColorStop: noop }) }, {
  get: (object, key) => key in object ? object[key] : noop,
  set: (object, key, value) => { object[key] = value; return true; }
});
const sandbox = {
  document: { querySelector: noop }, localStorage: { getItem: () => null },
  matchMedia: () => ({ matches: false }), ctx, assert, console
};
vm.runInNewContext(script.split('const visible=new Set()')[0] + `
  assert.equal(designs.length, 100);
  assert.equal(new Set(designs.map(d => d.name)).size, 100);
  assert.equal(families.length, 20);
  for (const d of designs) for (const time of [0, 1, 7]) {
    draw({ctx, d, dpr:1, w:300, h:250, x:200, y:180,
      dx:20, dy:-10, vx:2, vy:3, drag:true, burst:.4}, time);
  }
  console.log('PASS: 100 unique designs, 20 families, syntax and 300 canvas render smoke checks.');
`, sandbox);
