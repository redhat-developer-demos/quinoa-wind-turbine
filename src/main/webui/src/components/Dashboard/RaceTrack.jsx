import * as React from 'react';
import styled from 'styled-components';
import { useTheme } from '../../ThemeContext';
import { generateLanePaths, centerlinePath } from './LanePath';
import { RACE_ANIMATION_MS } from './RaceAnimation';
import FormulaOneCar, { FormulaOneCarDefs } from './FormulaOneCar';

const ROAD_WIDTH = 50;
const KERB = 7;

const RaceDiv = styled.div`
  text-align: center;
  display: flex;
  align-items: center;
  position: fixed;
  left: 200px;
  top: 0;
  right: 0;
  bottom: 0;
  justify-content: center;

  svg {
    width: 100%;
    height: auto;
    max-height: 100%;
  }

  &.generated svg {
    height: 100%;
  }

  .car {
    transition: offset-distance ${RACE_ANIMATION_MS}ms linear;
    transform-origin: 25px 14px;
  }
`;

// The cars for either track style: one per team, each riding its own offset path. Keeping the
// offset-path / offset-distance animation (and the #carN ids) preserves finish detection and the
// Playwright test that reads offsetDistance.
function Cars({
  teams, lanePaths, distances, carScale = 1,
}) {
  // Scale the car about its center (25,14) so bigger tracks keep a similar on-screen car size,
  // without disturbing the offset-path rotation, which pivots around that same center.
  const wrap = carScale === 1 ? null : `translate(${25 - 25 * carScale} ${14 - 14 * carScale}) scale(${carScale})`;
  return teams.map((team, i) => (
    <g
      className="car"
      id={`car${i + 1}`}
      key={team.id}
      style={{
        offsetPath: `path('${lanePaths[i]}')`,
        offsetDistance: `${distances[i]}%`,
      }}
    >
      <g transform={wrap || undefined}><FormulaOneCar color={team.color} /></g>
    </g>
  ));
}

// Keep cars a similar on-screen size across tracks: the default 777-wide track stays at 1, and a
// larger painted track scales its cars up in proportion.
function carScaleFor(viewBox) {
  return Math.max(1, viewBox.width / 1100);
}

// A painted track image with hand-drawn lane paths, like the hand-illustrated default. The image
// carries all the detail (water, bridges, foliage), so the cars just follow the supplied paths.
function ImageTrack({ track, teams, distances }) {
  const { viewBox, lanes } = track;
  // Use the hand-drawn lane strings if the track has them, otherwise offset the centerline into lanes.
  const lanePaths = React.useMemo(
    () => (lanes && lanes.length
      ? lanes
      : generateLanePaths({
        centerline: track.centerline, viewBox, laneCount: teams.length, laneSpacing: track.laneSpacing,
      })),
    [lanes, track, viewBox, teams],
  );
  return (
    <svg
      width={viewBox.width}
      height={viewBox.height}
      viewBox={`0 0 ${viewBox.width} ${viewBox.height}`}
      xmlSpace="preserve"
      xmlnsXlink="http://www.w3.org/1999/xlink"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs><FormulaOneCarDefs /></defs>
      <image
        width={viewBox.width}
        height={viewBox.height}
        preserveAspectRatio="none"
        xlinkHref={track.image}
      />
      <Cars teams={teams} lanePaths={lanePaths} distances={distances} carScale={carScaleFor(viewBox)} />
    </svg>
  );
}

// A checkered start/finish band across the road at the start point, oriented to the track heading
// there so it works for any generated centerline.
function StartLine({ centerline, width }) {
  const n = centerline.length;
  const [sx, sy] = centerline[0];
  const [px, py] = centerline[n - 1];
  const [nx, ny] = centerline[1];
  const angle = (Math.atan2(ny - py, nx - px) * 180) / Math.PI;
  const square = width / 4;
  const squares = [];
  for (let row = 0; row < 2; row += 1) {
    for (let col = 0; col < 4; col += 1) {
      squares.push({ x: (row - 1) * square, y: (col - 2) * square, dark: (row + col) % 2 === 0 });
    }
  }
  return (
    <g transform={`translate(${sx} ${sy}) rotate(${angle})`}>
      {squares.map((s) => (
        <rect key={`${s.x}-${s.y}`} x={s.x} y={s.y} width={square} height={square} fill={s.dark ? '#1b1b1b' : '#f5f5f5'} />
      ))}
    </g>
  );
}

// A track drawn from a centerline, for AI-generated themes where there is no painted image: turf
// with a vignette, a road with red/white kerbs, a dashed divider and a checkered start line.
function GeneratedTrack({ track, teams, distances }) {
  const { viewBox } = track;
  const lanePaths = React.useMemo(() => generateLanePaths({
    centerline: track.centerline, viewBox, laneCount: teams.length, laneSpacing: track.laneSpacing,
  }), [track, teams, viewBox]);
  const road = React.useMemo(
    () => centerlinePath({ centerline: track.centerline, viewBox }), [track, viewBox],
  );
  const bg = track.background || {};
  return (
    <svg
      viewBox={`0 0 ${viewBox.width} ${viewBox.height}`}
      preserveAspectRatio="xMidYMid slice"
      xmlSpace="preserve"
      xmlnsXlink="http://www.w3.org/1999/xlink"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>
        <radialGradient id="turf" cx="50%" cy="42%" r="75%">
          <stop offset="0%" stopColor={bg.turfLight || '#8ab83f'} />
          <stop offset="100%" stopColor={bg.turfDark || '#466f1f'} />
        </radialGradient>
        <linearGradient id="asphalt" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={bg.asphaltTop || '#43484e'} />
          <stop offset="100%" stopColor={bg.asphaltBottom || '#2c3035'} />
        </linearGradient>
        <FormulaOneCarDefs />
      </defs>
      <rect width={viewBox.width} height={viewBox.height} fill="url(#turf)" />
      <path d={road} fill="none" stroke="#f3f3f3" strokeWidth={ROAD_WIDTH + KERB * 2} strokeLinejoin="round" />
      <path d={road} fill="none" stroke="#d7322b" strokeWidth={ROAD_WIDTH + KERB * 2} strokeLinejoin="round" strokeDasharray="18 18" />
      <path d={road} fill="none" stroke="url(#asphalt)" strokeWidth={ROAD_WIDTH} strokeLinejoin="round" />
      <path d={road} fill="none" stroke="#f1f1f1" strokeWidth={2.5} strokeDasharray="16 22" opacity="0.7" />
      <StartLine centerline={track.centerline} width={ROAD_WIDTH} />
      <Cars teams={teams} lanePaths={lanePaths} distances={distances} carScale={carScaleFor(viewBox)} />
    </svg>
  );
}

function RaceTrack(props) {
  const { track, teams } = useTheme();
  const generated = !track.image;
  return (
    <RaceDiv
      className={generated ? 'generated' : 'image'}
      style={{ background: generated ? '#3f6a24' : '#1a1a1a' }}
      onTransitionEnd={(event) => {
        if (event.propertyName === 'offset-distance'
            && parseFloat(getComputedStyle(event.target).offsetDistance) >= 99.99) {
          props.onFinishCrossed?.(Number(event.target.id.replace('car', '')));
        }
      }}
    >
      {generated
        ? <GeneratedTrack track={track} teams={teams} distances={props.distances} />
        : <ImageTrack track={track} teams={teams} distances={props.distances} />}
    </RaceDiv>
  );
}

export default RaceTrack;
