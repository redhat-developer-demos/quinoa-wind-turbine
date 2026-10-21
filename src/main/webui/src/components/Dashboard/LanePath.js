// Draw a race track from a single centerline instead of a fixed image. The centerline is a
// closed loop of [x, y] control points, and each lane is a parallel offset of a smooth curve
// through those points, so the same track fits any number of cars. Lane paths feed the cars'
// CSS offset-path, so each one has to be a closed path that stays inside the view box.

// Linear interpolation of two points by knot values, for the centripetal spline below.
function lerp(a, b, ta, tb, t) {
  const f = tb - ta === 0 ? 0 : (t - ta) / (tb - ta);
  return [a[0] + (b[0] - a[0]) * f, a[1] + (b[1] - a[1]) * f];
}

// Centripetal Catmull-Rom (alpha = 0.5) through a closed loop. Centripetal parameterization avoids the
// cusps and self-loops that uniform Catmull-Rom produces on sharp or closely spaced points, which would
// otherwise flip a lane's offset direction and make the lanes cross.
function catmullRomClosed(points, samplesPerSegment) {
  const n = points.length;
  const sampled = [];
  const knot = (a, b) => Math.sqrt(Math.hypot(b[0] - a[0], b[1] - a[1]) || 1e-6);
  for (let i = 0; i < n; i += 1) {
    const p0 = points[(i - 1 + n) % n];
    const p1 = points[i];
    const p2 = points[(i + 1) % n];
    const p3 = points[(i + 2) % n];
    const t0 = 0;
    const t1 = t0 + knot(p0, p1);
    const t2 = t1 + knot(p1, p2);
    const t3 = t2 + knot(p2, p3);
    for (let s = 0; s < samplesPerSegment; s += 1) {
      const t = t1 + ((t2 - t1) * s) / samplesPerSegment;
      const a1 = lerp(p0, p1, t0, t1, t);
      const a2 = lerp(p1, p2, t1, t2, t);
      const a3 = lerp(p2, p3, t2, t3, t);
      const b1 = lerp(a1, a2, t0, t2, t);
      const b2 = lerp(a2, a3, t1, t3, t);
      sampled.push(lerp(b1, b2, t1, t2, t));
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
