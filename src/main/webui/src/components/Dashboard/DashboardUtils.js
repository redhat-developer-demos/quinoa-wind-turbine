import _ from 'lodash';
import { TAP_POWER, NB_TAP_NEEDED_PER_USER } from '../../Config';

function teamRank(a, b) { return b.generated - a.generated; }

function computeWinner(result) {
  if (result.team1 && result.team2) {
    return result.team1 < result.team2 ? 1 : 2;
  }
  if (result.team1) {
    return 1;
  }
  if (result.team2) {
    return 2;
  }
  return -1;
}


export function computeDistance(power, nbUsers) {
  if (nbUsers === 0) {
    return 0;
  }
  return (power * 100) / (TAP_POWER * NB_TAP_NEEDED_PER_USER * nbUsers);
}

export function resetTeam(p) {
  const t = { ...p };
  _.forEach(t, (u) => u.generated = 0);
  return t;
}

export function computeNbUsers(power, team) {
  return _.size(team);
}

export function computePower(t) {
  const team = _.values(t);
  return team.reduce((a, u) => a + u.generated, 0);
}

export function sortTeams(team) {
  team.sort((a, b) => {
    if (a.generated === b.generated) {
      return a.id - b.id;
    }
    return b.generated - a.generated;
  });
}


export function computeRank(result, mapTeam1, mapTeam2) {
  const team1 = _.values(mapTeam1).sort(teamRank);
  const team2 = _.values(mapTeam2).sort(teamRank);
  return {
    winner: computeWinner(result),
    team1,
    team2,
    overall: [...team1, ...team2].sort(teamRank),
  };
}
