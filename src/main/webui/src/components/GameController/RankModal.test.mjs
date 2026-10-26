import test from 'node:test';
import assert from 'node:assert/strict';
import { calculateRank } from './RankModalUtils.js';

test('calculateRank returns 1-based rank for existing player in overall standings', () => {
  const overall = [
    { name: 'Alice', generated: 1500 },
    { name: 'Bob', generated: 1400 },
    { name: 'Charlie', generated: 1300 },
  ];
  assert.equal(calculateRank(overall, 'Alice'), 1);
  assert.equal(calculateRank(overall, 'Bob'), 2);
  assert.equal(calculateRank(overall, 'Charlie'), 3);
});

test('calculateRank returns dash when user is not found or data is missing', () => {
  const overall = [{ name: 'Alice', generated: 1500 }];
  assert.equal(calculateRank(overall, 'Unknown'), '-');
  assert.equal(calculateRank(null, 'Alice'), '-');
  assert.equal(calculateRank(overall, null), '-');
});
