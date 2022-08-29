import { CLICK_POWER, NB_CLICK_NEEDED_PER_USER } from '../../Config';
import _ from 'lodash';

export function computeDistance(power, nbUsers) {
    if (nbUsers === 0) {
        return 0;
    }
    return (power * 100) / (CLICK_POWER * NB_CLICK_NEEDED_PER_USER * nbUsers);
}

export function resetTeam(t) {
    _.forEach(t, u => u.generated = 0);
    return t;
}

export function computeNbUsers(team1, team2) {
    return Math.max(_.size(team1), _.size(team2));
}

export function computePower(t) {
    return _.values(t).reduce((a, u) => a + u.generated, 0);
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