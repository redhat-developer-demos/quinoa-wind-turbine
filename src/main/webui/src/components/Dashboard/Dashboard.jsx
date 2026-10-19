import React, { useEffect, useState } from 'react';
import { raceApi } from '../../api';
import { elapsedNow, toDashboardModel } from './DashboardUtils';
import LeftBar from './LeftBar';
import RaceTrack from './RaceTrack';
import Winner from './Winner';
import CommentaryTicker from './CommentaryTicker';
import { RACE_ANIMATION_MS } from './RaceAnimation';

const NO_WINNER = { winner: -1 };

function Dashboard(props) {
  const [race, setRace] = useState();
  const [offline, setOffline] = useState(false);
  const [time, setTime] = useState(0);
  const [showWinner, setShowWinner] = useState(false);
  const [commentary, setCommentary] = useState();

  useEffect(() => raceApi.consume(setRace, setOffline, setCommentary), []);

  // The server owns the race clock. The stopwatch runs locally and resyncs when it drifts.
  useEffect(() => {
    if (race) {
      const serverTime = elapsedNow(race);
      setTime((t) => (race.status !== 'STARTED' || Math.abs(serverTime - t) > 500 ? serverTime : t));
    }
  }, [race]);

  const { status, teams, rank } = toDashboardModel(race);

  useEffect(() => {
    if (rank.winner < 0) {
      setShowWinner(false);
      return undefined;
    }
    // Let the cars cross the finish line before the popup covers the track
    // The transition event normally releases the winner. Allow a fallback for browsers
    // that skip a transition when the dashboard is opened on an already finished race.
    const timeout = setTimeout(() => setShowWinner(true), RACE_ANIMATION_MS + 100);
    return () => clearTimeout(timeout);
  }, [rank.winner, race?.raceId]);

  // showWinner turns off in an effect, after the render that follows a reset, so check the winner as well
  const winnerShown = showWinner && rank.winner > 0;
  const shownRank = winnerShown ? rank : NO_WINNER;
  return (
    <div className={props.className}>
      <LeftBar teams={teams} time={time} setTime={setTime} status={offline ? 'offline' : status} rank={shownRank} />
      <RaceTrack distances={teams.map((t) => t.distance)}
        onFinishCrossed={(team) => { if (team === rank.winner) setShowWinner(true); }} />
      <CommentaryTicker commentary={commentary && commentary.raceId === race?.raceId
        && (commentary.trigger !== 'finish' || winnerShown) ? commentary : undefined} />
      {winnerShown && <Winner rank={rank} />}
    </div>
  );
}

export default Dashboard;
