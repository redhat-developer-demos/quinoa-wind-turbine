// The explicit .js extension keeps this module loadable by the Node test runner, which resolves
// imports strictly; Vite accepts it too.
import { TEAMS_CONFIG } from '../../Config.js';

// Race statuses from the server, mapped to the names the dashboard controls use
const STATUSES = {
  WAITING: 'initial',
  STARTED: 'started',
  PAUSED: 'paused',
  FINISHED: 'finished',
};

export function sortTeams(team) {
  team.sort((a, b) => {
    if (a.generated === b.generated) {
      return a.id - b.id;
    }
    return b.generated - a.generated;
  });
}

/**
 * The players sorted by generated power, best first, capped to count. The winner popup shows the
 * top few instead of the whole field, so a large race doesn't render a 1000-row list.
 * Returns a new array and never mutates the input, which the live race state also uses.
 */
export function topPlayers(players, count) {
  return [...players]
    .sort((a, b) => b.generated - a.generated || a.name.localeCompare(b.name))
    .slice(0, count);
}

/**
 * Turns the race state computed by the server into what the dashboard components render.
 */
export function toDashboardModel(race) {
  const players = race ? race.players : [];
  const teams = TEAMS_CONFIG.map((config, i) => {
    const id = i + 1;
    const state = race && race.teams.find((t) => t.id === id);
    return {
      id,
      team: players.filter((p) => p.team === id),
      generated: state ? state.power : 0,
      distance: state ? state.distance : 0,
      time: state ? state.finishTimeMs : null,
    };
  });
  const winner = race && race.winner ? race.winner : -1;
  return {
    status: race ? STATUSES[race.status] : 'initial',
    teams,
    rank: {
      winner,
      overall: players,
      team1: teams[0].team,
      team2: teams[1].team,
    },
  };
}

/**
 * Race time the server would report now. Snapshots only arrive when the race changes,
 * so a running race keeps counting from the snapshot time.
 */
export function elapsedNow(race) {
  if (race.status !== 'STARTED') {
    return race.elapsedMs;
  }
  return race.elapsedMs + Math.max(0, Date.now() - race.updatedAt);
}

/**
 * Determines whether text should be dark (#111827) or light (#ffffff) to ensure high contrast
 * and readability against any given background hex or CSS color.
 */
export function getContrastColor(color) {
  if (!color) return '#ffffff';
  let hex = color.trim().toLowerCase();
  if (hex === 'white' || hex === '#fff' || hex === '#ffffff') return '#111827';
  if (hex === 'black' || hex === '#000' || hex === '#000000') return '#ffffff';
  if (hex.startsWith('#')) {
    hex = hex.slice(1);
    if (hex.length === 3) {
      hex = hex.split('').map((c) => c + c).join('');
    }
    if (hex.length === 6) {
      const r = parseInt(hex.substring(0, 2), 16) / 255;
      const g = parseInt(hex.substring(2, 4), 16) / 255;
      const b = parseInt(hex.substring(4, 6), 16) / 255;
      const toLinear = (c) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4);
      const luminance = 0.2126 * toLinear(r) + 0.7152 * toLinear(g) + 0.0722 * toLinear(b);
      return luminance > 0.4 ? '#111827' : '#ffffff';
    }
  }
  return '#ffffff';
}
