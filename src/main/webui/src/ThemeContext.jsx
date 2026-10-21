import * as React from 'react';
import { TEAMS_CONFIG } from './Config';

// The track the dashboard draws when nothing overrides it: the hand-painted image with its two
// hand-drawn lane paths. An AI theme can instead send a generated track with a `centerline`, which
// RaceTrack draws as vector turf-and-kerbs; the painted image stays the default because it looks far
// better than anything generated procedurally.
export const DEFAULT_TRACK = {
  image: 'race-track-1.png',
  viewBox: { width: 777, height: 518 },
  lanes: [
    'M 745 243 Q 750 340 750 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 Q 670 30 700 50 Q 720 80 720 110 Q 740 180 750 320 ',
    'M 720 230 Q 735 335 720 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 Q 690 80 705 115 Q 720 180 720 320 ',
  ],
};

const ThemeContext = React.createContext(null);

// Holds the teams and track the UI renders. It is seeded from the configuration that
// loadConfig() already fetched before the app mounted. setTheme is the seam the AI theme
// feature will drive from a theme game event; nothing calls it yet.
export function ThemeProvider({ children }) {
  const [theme, setTheme] = React.useState(() => ({
    teams: TEAMS_CONFIG,
    track: DEFAULT_TRACK,
  }));
  const value = React.useMemo(() => ({ theme, setTheme }), [theme]);
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}

export function useTheme() {
  const value = React.useContext(ThemeContext);
  if (!value) throw new Error('useTheme must be used within a ThemeProvider');
  return value.theme;
}

export function useSetTheme() {
  const value = React.useContext(ThemeContext);
  if (!value) throw new Error('useSetTheme must be used within a ThemeProvider');
  return value.setTheme;
}
