import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { hallOfFameApi, powerApi } from '../../api';
import { TEAMS_CONFIG } from '../../Config';

const HallOfFameDiv = styled.div`
  position: fixed;
  left: calc(50% - 220px);
  top: calc(50% - 260px);
  width: 640px;
  max-height: 520px;
  box-sizing: border-box;
  padding: 26px 32px 30px;
  background: #1f1f1f;
  border: 1px solid #333;
  border-radius: 10px;
  box-shadow: 0 24px 60px rgba(0, 0, 0, 0.45);
  color: #f2f2f2;
  display: flex;
  flex-direction: column;

  h2 {
    margin: 0 0 20px 0;
    text-align: center;
    font-size: 1.7rem;
    letter-spacing: 0.01em;
  }
  .races {
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    gap: 18px;
  }
  .race-head {
    display: flex;
    align-items: baseline;
    justify-content: space-between;
    margin-bottom: 6px;
  }
  .winner {
    font-size: 1.15rem;
    font-weight: 700;
  }
  .time {
    font-variant-numeric: tabular-nums;
    color: #9a9a9a;
  }
  table.players {
    width: 100%;
    border-collapse: collapse;
  }
  table.players td {
    padding: 5px 0;
    border-top: 1px solid #2d2d2d;
  }
  .rank {
    width: 28px;
    color: #777;
    font-variant-numeric: tabular-nums;
  }
  .name {
    color: #e6e6e6;
  }
  .power {
    text-align: right;
    font-variant-numeric: tabular-nums;
    color: #b7b7b7;
  }
`;

function formatTime(ms) {
  const total = Math.round(ms / 1000);
  const minutes = Math.floor(total / 60);
  const seconds = total % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function teamColor(id) {
  const config = TEAMS_CONFIG[id - 1];
  return config ? config.color : 'white';
}

/**
 * Shows past finished races between races. Fetches once on mount, which is when the dashboard returns to the
 * initial state, so each new race's result appears the next time the board is idle.
 */
export default function HallOfFame() {
  const [races, setRaces] = useState([]);

  useEffect(() => { hallOfFameApi.fetchHallOfFame().then(setRaces); }, []);

  if (!races.length) {
    return null;
  }
  return (
    <HallOfFameDiv>
      <h2>Hall of fame</h2>
      <div className="races">
        {races.map((race) => (
          <div className="race" key={race.raceId}>
            <div className="race-head">
              <span className="winner" style={{ color: teamColor(race.winner) }}>{race.winnerName}</span>
              <span className="time">{formatTime(race.elapsedMs)}</span>
            </div>
            {race.topPlayers.length > 0 && (
              <table className="players">
                <tbody>
                  {race.topPlayers.map((player, i) => (
                    <tr key={player.name}>
                      <td className="rank">{i + 1}</td>
                      <td className="name">{player.name}</td>
                      <td className="power">{powerApi.humanPower(player.generated)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        ))}
      </div>
    </HallOfFameDiv>
  );
}
