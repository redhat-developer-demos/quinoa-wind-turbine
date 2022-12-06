import React, { useEffect, useState } from 'react';
import { powerApi, gameApi } from '../../api';
import {
  computeDistance, resetTeam, computePower, computeNbUsers, computeWinner,
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

  const reset = () => {
    setTeam1(resetTeam);
    setTeam2(resetTeam);
    setTime(0);
    setResult({});
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
      gameApi.sendEvent('pause');
    }
  }, [status, time, distances, result]);

  const winner = computeWinner(result);
  return (
    <div className={props.className}>
      <LeftBar team1={team1} team2={team2} power={power} result={result} time={time} setTime={setTime} status={status} />
      <RaceTrack distances={distances} />
      {winner > 0 && <Winner winner={winner} team1={team1} team2={team2} />}
    </div>
  );
}

export default Dashboard;
