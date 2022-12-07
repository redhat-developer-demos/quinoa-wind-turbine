import React, { useEffect, useState } from 'react';
import { gameApi, powerApi } from '../../api';
import {
  computeDistance, computeNbUsers, computePower, computeRank, resetTeam,
} from './DashboardUtils';
import LeftBar from './LeftBar';
import RaceTrack from './RaceTrack';
import Winner from './Winner';

function Dashboard(props) {
  const [result, setResult] = useState({});
  const [status, setStatus] = useState('initial');
  const [team1, setTeam1] = useState({});
  const [team2, setTeam2] = useState({});
  const [time, setTime] = useState(0);
  const [rank, setRank] = useState({ winner: -1 });

  const reset = () => {
    setTeam1(resetTeam);
    setTeam2(resetTeam);
    setTime(0);
    setResult({});
    setRank({ winner: -1 });
  };
  useEffect(
    () => powerApi.consume(status, [setTeam1, setTeam2]),
    [status],
  );
  useEffect(() => gameApi.events(setStatus, reset), []);
  const power = [computePower(team1), computePower(team2)];
  const nbUsers1 = computeNbUsers(power[0], team1);
  const nbUsers2 = computeNbUsers(power[1], team2);
  const distances = [computeDistance(power[0], nbUsers1), computeDistance(power[1], nbUsers2)];

  useEffect(() => {
    if (!result.team1 && distances[0] >= 100) {
      setResult((p) => ({ ...p, team1: time }));
    } else if (!result.team2 && distances[1] >= 100) {
      setResult((p) => ({ ...p, team2: time }));
    }
    if (status === 'started' && result.team1 && result.team2) {
      setRank((prev) => {
        if (prev.winner < 0) {
          const r = computeRank(result, team1, team2);
          gameApi.sendEvent('finish', { overall: r.overall });
          return r;
        }
        return prev;
      });
    }
  }, [status, time, distances, result]);

  return (
    <div className={props.className}>
      <LeftBar team1={team1} team2={team2} power={power} result={result} time={time} setTime={setTime} status={status} rank={rank} />
      <RaceTrack distances={distances} />
      {rank.winner > 0 && <Winner rank={rank} />}
    </div>
  );
}

export default Dashboard;
