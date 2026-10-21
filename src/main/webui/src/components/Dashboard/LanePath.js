// Draw a race track from a single centerline instead of a fixed image. The centerline is a
// closed loop of [x, y] control points, and each lane is a parallel offset of a smooth curve
// through those points, so the same track fits any number of cars. Lane paths feed the cars'
// CSS offset-path, so each one has to be a closed path that stays inside the view box.

function catmullRomClosed(points, samplesPerSegment) {
  const n = points.length;
  const sampled = [];
  for (let i = 0; i < n; i += 1) {
    const p0 = points[(i - 1 + n) % n];
    const p1 = points[i];
    const p2 = points[(i + 1) % n];
    const p3 = points[(i + 2) % n];
    for (let s = 0; s < samplesPerSegment; s += 1) {
      const t = s / samplesPerSegment;
      const t2 = t * t;
      const t3 = t2 * t;
      const x = 0.5 * ((2 * p1[0])
        + (-p0[0] + p2[0]) * t
        + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2
        + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3);
      const y = 0.5 * ((2 * p1[1])
        + (-p0[1] + p2[1]) * t
        + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2
        + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3);
      sampled.push([x, y]);
    }
  }
  return sampled;
}

function offsetPoints(samples, distance) {
  const n = samples.length;
  return samples.map((point, i) => {
    const prev = samples[(i - 1 + n) % n];
    const next = samples[(i + 1) % n];
    const tx = next[0] - prev[0];
    const ty = next[1] - prev[1];
    const length = Math.hypot(tx, ty) || 1;
    // The normal is the unit tangent turned 90 degrees.
    const nx = -ty / length;
    const ny = tx / length;
    return [point[0] + nx * distance, point[1] + ny * distance];
  });
}

function toPath(points, viewBox) {
  const clamp = (value, max) => Math.max(0, Math.min(max, value));
  const round = (value) => Math.round(value * 100) / 100;
  const d = points.map((point, i) => {
    const x = round(clamp(point[0], viewBox.width));
    const y = round(clamp(point[1], viewBox.height));
    return `${i === 0 ? 'M' : 'L'} ${x} ${y}`;
  }).join(' ');
  return `${d} Z`;
}

function validate(centerline, laneCount) {
  if (!Array.isArray(centerline) || centerline.length < 3) {
    throw new Error('A track centerline needs at least three points');
  }
  if (!Number.isInteger(laneCount) || laneCount < 1) {
    throw new Error('A track needs at least one lane');
  }
}

// One closed lane path per team, each offset from the centerline so the lanes stay centered
// on the road. Coordinates are clamped to the view box so a generated track can never send a
// car off-screen.
export function generateLanePaths({
  centerline, viewBox, laneCount, laneSpacing = 24, samplesPerSegment = 12,
}) {
  validate(centerline, laneCount);
  const samples = catmullRomClosed(centerline, samplesPerSegment);
  const paths = [];
  for (let lane = 0; lane < laneCount; lane += 1) {
    const distance = (lane - (laneCount - 1) / 2) * laneSpacing;
    paths.push(toPath(offsetPoints(samples, distance), viewBox));
  }
  return paths;
}

// The smooth centerline as a closed path, used to draw the road itself.
export function centerlinePath({ centerline, viewBox, samplesPerSegment = 12 }) {
  validate(centerline, 1);
  return toPath(catmullRomClosed(centerline, samplesPerSegment), viewBox);
}
