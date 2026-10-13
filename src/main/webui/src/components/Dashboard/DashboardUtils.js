import { TEAMS_CONFIG } from '../../Config';

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
