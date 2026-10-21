import * as React from 'react';

const CHASSIS = 'M30 48 L61 44 C79 40 88 33 109 33 C129 33 141 38 154 42 '
  + 'L231 47 Q244 48 244 52 Q244 56 231 57 L154 62 C141 66 129 71 109 71 '
  + 'C88 71 79 64 61 60 L30 56 Z';
const SIDEPOD = 'M44 37 C62 34 77 23 98 23 L124 25 Q140 26 146 34 '
  + 'L136 43 L102 45 L61 46 Z';

// The 250 × 104 drawing fits the track's 50 × 28 car box, pointing right and centered at (25, 14).
export default function FormulaOneCar({ color }) {
  return (
    <g filter="url(#f1-shadow)">
      <g transform="translate(0 3.6) scale(0.2)">
        {/* Carbon floor and rear diffuser. */}
        <path d="M27 35 L47 29 Q66 18 98 19 L129 24 L151 35 L146 69 L129 80 L98 85 Q66 86 47 75 L27 69 Z"
          fill="#171d22" stroke="#41484e" strokeWidth="1.4" />
        <path d="M26 38 L52 40 L52 64 L26 66 Z" fill="#080c10" />
        <path d="M30 42 L48 44 M30 48 L48 49 M30 56 L48 55 M30 62 L48 60"
          fill="none" stroke="#485057" strokeWidth="1.3" />

        {/* Wishbones stay visible in the gap between the nose and the front wheels. */}
        <g fill="none" stroke="#11171c" strokeWidth="4" strokeLinejoin="round">
          <path d="M53 23 L91 46 L57 46 Z M53 81 L91 58 L57 58 Z" />
          <path d="M194 23 L151 46 L178 48 Z M194 81 L151 58 L178 56 Z" />
        </g>
        <path d="M54 24 L88 45 M54 80 L88 59 M192 24 L155 45 M192 80 L155 59"
          fill="none" stroke="#7b858d" strokeWidth="1.2" />

        {/* Slick tyres, with tread shoulders instead of visible wheel hubs in this overhead view. */}
        {[[34, 0, 43, 25], [34, 79, 43, 25], [177, 2, 37, 23], [177, 79, 37, 23]].map(([x, y, width, height]) => (
          <g key={`${x}-${y}`} transform={`translate(${x} ${y})`}>
            <rect width={width} height={height} rx="7" fill="url(#f1-tyre)" stroke="#080b0e" strokeWidth="1.4" />
            <path d={`M5 4 H${width - 5} M5 ${height - 4} H${width - 5}`}
              stroke="#626a71" strokeWidth="1" opacity="0.65" />
            <path d={`M9 1.5 V${height - 1.5} M${width - 9} 1.5 V${height - 1.5}`}
              stroke="#080b0e" strokeWidth="1.5" opacity="0.5" />
          </g>
        ))}

        {/* Rear wing: two aero elements and body-colored endplates. */}
        <path d="M4 14 Q15 11 26 16 L26 88 Q15 93 4 90 Z" fill="#11171c" />
        <path d="M12 17 Q18 15 22 18 L22 86 Q18 89 12 87 Z" fill="#edf1f3" />
        <path d="M8 18 V86 M24 19 V85" stroke="#78828a" strokeWidth="1.3" />
        <path d="M2 10 H29 V16 H2 Z M2 88 H29 V94 H2 Z" fill={color} stroke="#171d22" strokeWidth="1" />
        <path d="M4 11 H27 M4 89 H27" stroke="#fff" strokeOpacity="0.45" strokeWidth="1.2" />

        {/* Sculpted sidepods, black radiator mouths and white livery follow the reference silhouette. */}
        {[false, true].map((mirror) => (
          <g key={String(mirror)} transform={mirror ? 'translate(0 104) scale(1 -1)' : undefined}>
            <path d={SIDEPOD} fill={color} stroke="#192127" strokeWidth="1.2" />
            <path d={SIDEPOD} fill="url(#f1-paint)" />
            <path d="M52 37 C78 33 92 25 124 28 L139 32 L134 35 C103 29 88 36 63 41 Z"
              fill="#f4f6f7" />
            <path d="M130 28 Q141 28 145 34 L136 41 L128 39 Z" fill="#111a20" />
            <path d="M132 29 L140 32" stroke="#738089" strokeWidth="1.2" />
            <path d="M79 27 L83 32 M85 25.5 L89 30.5 M91 24.5 L95 29.5"
              stroke="#111a20" strokeWidth="1.5" opacity="0.65" />
          </g>
        ))}

        {/* Swept front wing, split around the nose, with separate flap edges. */}
        {[false, true].map((mirror) => (
          <g key={String(mirror)} transform={mirror ? 'translate(0 104) scale(1 -1)' : undefined}>
            <path d="M218 13 H248 Q241 30 242 46 L228 46 Q225 26 218 13 Z"
              fill="#11171c" stroke="#080c10" strokeWidth="1" />
            <path d="M225 18 H242 Q236 30 238 43 L231 43 Q230 29 225 18 Z" fill="#edf1f3" />
            <path d="M222 18 Q230 33 230 44 M229 18 Q235 32 234 43"
              fill="none" stroke="#879198" strokeWidth="1.1" />
            <path d="M217 10 H249 L248 16 H220 Z" fill={color} stroke="#171d22" strokeWidth="1" />
            <path d="M220 11 H247" stroke="#fff" strokeOpacity="0.5" strokeWidth="1.2" />
          </g>
        ))}
        <path d="M220 44 V60 M229 45 V59" stroke="#11171c" strokeWidth="4" />

        {/* Tapered engine cover, raised monocoque and long, narrow nose. */}
        <path d={CHASSIS} fill={color} stroke="#192127" strokeWidth="1.2" />
        <path d={CHASSIS} fill="url(#f1-paint)" />
        <path d="M36 51 L97 42 L107 45 L96 49 L36 53 Z" fill="#fff" opacity="0.28" />
        <path d="M153 44 L230 49 M153 60 L230 55" stroke="#f4f6f7" strokeWidth="1.8" fill="none" />
        <path d="M160 48 L229 51" stroke="#fff" strokeWidth="2" strokeLinecap="round" opacity="0.35" />
        <path d="M100 45 L91 47 V57 L100 59 Z" fill="#121b21" stroke="#89939a" strokeWidth="1" />

        {/* Open cockpit, driver's helmet and the halo's front support. */}
        <path d="M110 40 Q127 36 137 43 Q145 52 137 61 Q127 68 110 64 Q102 52 110 40 Z"
          fill="#080e13" stroke="#6c7881" strokeWidth="1.3" />
        <path d="M110 43 Q104 52 110 61" stroke="#303c45" strokeWidth="3" fill="none" />
        <ellipse cx="117" cy="52" rx="7" ry="7.5" fill="#f3f2e9" />
        <path d="M119 45.5 Q125 52 119 58.5 L122 57 Q127 52 122 47 Z" fill="#1a2936" />
        <path d="M113 46 Q115 44 118 46" stroke="#fff" strokeWidth="1.3" fill="none" />
        <path d="M110 39 C131 33 146 39 147 52 C146 65 131 71 110 65"
          fill="none" stroke="#162128" strokeWidth="4.5" strokeLinecap="round" />
        <path d="M111 38.5 C130 34 144 40 145 49" fill="none" stroke="#a9b4bb" strokeWidth="1.2" />
        <path d="M147 52 H134" stroke="#162128" strokeWidth="3.5" strokeLinecap="round" />
        <path d="M146 51 H136" stroke="#8c9ba5" strokeWidth="0.8" />

        {/* Mirrors sit outside the cockpit on thin stalks. */}
        <path d="M140 40 L148 32 M140 64 L148 72" stroke="#192127" strokeWidth="2" />
        <path d="M144 28 Q151 26 156 29 L155 33 H144 Z M144 76 Q151 78 156 75 L155 71 H144 Z"
          fill={color} stroke="#192127" strokeWidth="1.2" />
      </g>
    </g>
  );
}

export function FormulaOneCarDefs() {
  return (
    <>
      <filter id="f1-shadow" x="-15%" y="-25%" width="130%" height="160%">
        <feDropShadow dx="0" dy="1.2" stdDeviation="0.8" floodColor="#000" floodOpacity="0.5" />
      </filter>
      <linearGradient id="f1-paint" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stopColor="#fff" stopOpacity="0.32" />
        <stop offset="0.35" stopColor="#fff" stopOpacity="0.05" />
        <stop offset="0.65" stopColor="#000" stopOpacity="0.05" />
        <stop offset="1" stopColor="#000" stopOpacity="0.32" />
      </linearGradient>
      <linearGradient id="f1-tyre" x1="0" y1="0" x2="0" y2="1">
        <stop offset="0" stopColor="#0b0f13" />
        <stop offset="0.22" stopColor="#30383e" />
        <stop offset="0.48" stopColor="#20272d" />
        <stop offset="0.8" stopColor="#151b20" />
        <stop offset="1" stopColor="#080c10" />
      </linearGradient>
    </>
  );
}
