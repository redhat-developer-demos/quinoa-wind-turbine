// Shared with RankModal.test.mjs, which node --test imports directly (the JSX component can't be).
export function calculateRank(overall, userName) {
  if (!overall || !Array.isArray(overall) || !userName) {
    return '-';
  }
  const idx = overall.findIndex((u) => u.name === userName);
  return idx >= 0 ? idx + 1 : '-';
}
