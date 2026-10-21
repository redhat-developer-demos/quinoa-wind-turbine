import test from 'node:test';
import assert from 'node:assert/strict';
import { generateLanePaths, centerlinePath } from './LanePath.js';

const viewBox = { width: 777, height: 518 };
const centerline = [
  [390, 120], [560, 150], [650, 260], [610, 380], [390, 420],
  [170, 380], [130, 260], [220, 150],
];

function coordinates(path) {
  const numbers = path.match(/-?\d+(\.\d+)?/g).map(Number);
  const points = [];
  for (let i = 0; i + 1 < numbers.length; i += 2) {
    points.push([numbers[i], numbers[i + 1]]);
  }
  return points;
}

test('generates one closed lane path per team', () => {
  const paths = generateLanePaths({ centerline, viewBox, laneCount: 2 });
  assert.equal(paths.length, 2);
  for (const path of paths) {
    assert.match(path, /^M /);
    assert.match(path, / Z$/);
  }
});

test('every lane coordinate stays inside the view box', () => {
  const paths = generateLanePaths({ centerline, viewBox, laneCount: 2, laneSpacing: 40 });
  for (const path of paths) {
    for (const [x, y] of coordinates(path)) {
      assert.ok(x >= 0 && x <= viewBox.width, `x ${x} within 0..${viewBox.width}`);
      assert.ok(y >= 0 && y <= viewBox.height, `y ${y} within 0..${viewBox.height}`);
    }
  }
});

test('the lane generator scales beyond two lanes', () => {
  assert.equal(generateLanePaths({ centerline, viewBox, laneCount: 4 }).length, 4);
});

test('rejects an invalid centerline or lane count', () => {
  assert.throws(() => generateLanePaths({ centerline: [[0, 0]], viewBox, laneCount: 2 }), /centerline/);
  assert.throws(() => generateLanePaths({ centerline, viewBox, laneCount: 0 }), /lane/);
});

test('the centerline road path is closed and inside the view box', () => {
  const path = centerlinePath({ centerline, viewBox });
  assert.match(path, / Z$/);
  for (const [x, y] of coordinates(path)) {
    assert.ok(x >= 0 && x <= viewBox.width);
    assert.ok(y >= 0 && y <= viewBox.height);
  }
});
