import { CLICK_POWER, NB_CLICK_NEEDED_PER_USER } from '../../Config';
import _ from 'lodash';

export function computeDistance(power, nbUsers) {
    if (nbUsers === 0) {
        return 0;
    }
    return (power * 100) / (CLICK_POWER * NB_CLICK_NEEDED_PER_USER * nbUsers);
}

export function resetTeam(p) {
    const t = {...p};
    _.forEach(t, u => u.generated = 0);
    return t;
}

export function computeNbUsers(power, team) {
    return  _.size(team);
}

export function computePower(t) {
    const team = _.values(t);
    return team.reduce((a, u) => a + u.generated, 0);
}

export function sort(team) {
    team.sort((a, b) => {
        if (a.generated === b.generated) {
            return a.id - b.id;
        }
        return b.generated - a.generated;
    });
}

export function computeWinner(result) {
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
