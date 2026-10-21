import * as React from 'react';
import styled from 'styled-components';
import { useTheme } from '../../ThemeContext';
import { generateLanePaths, centerlinePath } from './LanePath';
import { RACE_ANIMATION_MS } from './RaceAnimation';

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

// A top-down Formula 1 car drawn as SVG and filled with the team color, so any theme (or a generated
// one) recolors it with no sprite files: pointed nose with a front wing, exposed tyres, a cockpit with
// a halo, and a rear wing. Drawn nose-right, centered near (25, 14) to match the transform-origin the
// cars rotate around.
function CarShape({ color }) {
  const body = 'M6 11 C 10 8, 16 7, 22 7.5 C 28 8, 34 10, 40 11.5 L 48 14 L 40 16.5 '
    + 'C 34 18, 28 20, 22 20.5 C 16 21, 10 20, 6 17 Z';
  return (
    <g filter="url(#carShadow)">
      {/* rear wing (left) with light endplates */}
      <rect x="1.5" y="4.5" width="4.5" height="19" rx="1.2" fill={color} stroke="#f5f5f5" strokeWidth="0.7" />
      <rect x="1" y="4" width="5.5" height="2.2" rx="1" fill="#efefef" />
      <rect x="1" y="21.8" width="5.5" height="2.2" rx="1" fill="#efefef" />
      {/* exposed tyres: rounded and shaded so they read as round */}
      <g>
        <rect x="8" y="0.8" width="11" height="6.6" rx="2.8" fill="url(#tyreGloss)" stroke="#000" strokeWidth="0.5" />
        <rect x="11" y="3" width="5" height="2.2" rx="1" fill="#555" />
      </g>
      <g>
        <rect x="8" y="20.6" width="11" height="6.6" rx="2.8" fill="url(#tyreGloss)" stroke="#000" strokeWidth="0.5" />
        <rect x="11" y="22.8" width="5" height="2.2" rx="1" fill="#555" />
      </g>
      <g>
        <rect x="31" y="0.8" width="11" height="6.6" rx="2.8" fill="url(#tyreGloss)" stroke="#000" strokeWidth="0.5" />
        <rect x="34" y="3" width="5" height="2.2" rx="1" fill="#555" />
      </g>
      <g>
        <rect x="31" y="20.6" width="11" height="6.6" rx="2.8" fill="url(#tyreGloss)" stroke="#000" strokeWidth="0.5" />
        <rect x="34" y="22.8" width="5" height="2.2" rx="1" fill="#555" />
      </g>
      {/* front wing (nose, right) with light endplates */}
      <rect x="43" y="4" width="4" height="20" rx="1.2" fill={color} stroke="#f5f5f5" strokeWidth="0.7" />
      <rect x="42.5" y="3.5" width="5" height="2.2" rx="1" fill="#efefef" />
      <rect x="42.5" y="22.3" width="5" height="2.2" rx="1" fill="#efefef" />
      {/* chassis: flared sidepods, pointed nose on the right, light outline for contrast */}
      <path d={body} fill={color} stroke="#f5f5f5" strokeWidth="0.9" />
      {/* accent stripes converging toward the nose */}
      <path d="M9 12.3 C 22 10, 35 12, 46 13.7" stroke="#ffffff" strokeWidth="1.1" fill="none" opacity="0.9" />
      <path d="M9 15.7 C 22 18, 35 16, 46 14.3" stroke="#ffffff" strokeWidth="1.1" fill="none" opacity="0.9" />
      {/* 3D gloss overlay */}
      <path d={body} fill="url(#carGloss)" />
      {/* cockpit and halo */}
      <ellipse cx="23" cy="14" rx="4.2" ry="3" fill="#0d0d0d" />
      <path d="M19 14 Q23 9.5 27 14" stroke="#e8e8e8" strokeWidth="1" fill="none" opacity="0.85" />
    </g>
  );
}

// The cars for either track style: one per team, each riding its own offset path. Keeping the
// offset-path / offset-distance animation (and the #carN ids) preserves finish detection and the
// Playwright test that reads offsetDistance.
function Cars({ teams, lanePaths, distances }) {
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
      <CarShape color={team.color} />
    </g>
  ));
}

const CAR_DEFS = (
  <>
    <filter id="carShadow" x="-60%" y="-70%" width="220%" height="240%">
      <feDropShadow dx="0" dy="3" stdDeviation="3" floodColor="#000" floodOpacity="0.55" />
    </filter>
    {/* Top-lit gloss overlay: a white highlight fading to a dark shade gives the flat body a curved,
        3D look, and works over any team color because it is painted on top. */}
    <linearGradient id="carGloss" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stopColor="#ffffff" stopOpacity="0.55" />
      <stop offset="40%" stopColor="#ffffff" stopOpacity="0.08" />
      <stop offset="60%" stopColor="#000000" stopOpacity="0.08" />
      <stop offset="100%" stopColor="#000000" stopOpacity="0.45" />
    </linearGradient>
    <radialGradient id="tyreGloss" cx="50%" cy="32%" r="75%">
      <stop offset="0%" stopColor="#5c5c5c" />
      <stop offset="55%" stopColor="#1c1c1c" />
      <stop offset="100%" stopColor="#000000" />
    </radialGradient>
  </>
);

// A painted track image with hand-drawn lane paths, like the hand-illustrated default. The image
// carries all the detail (water, bridges, foliage), so the cars just follow the supplied paths.
function ImageTrack({ track, teams, distances }) {
  const { viewBox, lanes } = track;
  return (
    <svg
      width={viewBox.width}
      height={viewBox.height}
      viewBox={`0 0 ${viewBox.width} ${viewBox.height}`}
      xmlSpace="preserve"
      xmlnsXlink="http://www.w3.org/1999/xlink"
      xmlns="http://www.w3.org/2000/svg"
    >
      <defs>{CAR_DEFS}</defs>
      <image
        width={viewBox.width}
        height={viewBox.height}
        preserveAspectRatio="none"
        xlinkHref={track.image}
      />
      <Cars teams={teams} lanePaths={lanes} distances={distances} />
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
        {CAR_DEFS}
      </defs>
      <rect width={viewBox.width} height={viewBox.height} fill="url(#turf)" />
      <path d={road} fill="none" stroke="#f3f3f3" strokeWidth={ROAD_WIDTH + KERB * 2} strokeLinejoin="round" />
      <path d={road} fill="none" stroke="#d7322b" strokeWidth={ROAD_WIDTH + KERB * 2} strokeLinejoin="round" strokeDasharray="18 18" />
      <path d={road} fill="none" stroke="url(#asphalt)" strokeWidth={ROAD_WIDTH} strokeLinejoin="round" />
      <path d={road} fill="none" stroke="#f1f1f1" strokeWidth={2.5} strokeDasharray="16 22" opacity="0.7" />
      <StartLine centerline={track.centerline} width={ROAD_WIDTH} />
      <Cars teams={teams} lanePaths={lanePaths} distances={distances} />
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
