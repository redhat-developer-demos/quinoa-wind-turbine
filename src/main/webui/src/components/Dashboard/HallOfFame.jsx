import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { hallOfFameApi, powerApi } from '../../api';

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
    margin: 0 0 16px 0;
    text-align: center;
    font-size: 1.7rem;
    letter-spacing: 0.01em;
  }

  .leaderboard {
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    padding-right: 4px;
  }

  table.players {
    width: 100%;
    border-collapse: collapse;
  }

  table.players td {
    padding: 8px 6px;
    border-top: 1px solid #2d2d2d;
  }

  .rank {
    width: 38px;
    color: #9ca3af;
    font-weight: 700;
    font-variant-numeric: tabular-nums;
  }

  .name {
    color: #f3f4f6;
    font-size: 1.05rem;
    display: inline-flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }

  .team {
    color: #9ca3af;
    font-size: 0.9rem;
  }

  .modes {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    margin-left: 4px;
  }

  .mode-icon {
    font-size: 0.95rem;
    cursor: default;
  }

  .power {
    text-align: right;
    font-variant-numeric: tabular-nums;
    color: #fcd700;
    font-weight: 700;
    font-size: 1.05rem;
  }
`;

const MODE_ICONS = {
  shake: { icon: '📳', label: 'Shook phone' },
  tap: { icon: '👆', label: 'Tapped / Clicked' },
  swipe: { icon: '↔️', label: 'Swiped' },
  blow: { icon: '💨', label: 'Blew into mic' },
};

function ModeBadges({ modes }) {
  if (!modes || modes.length === 0) return null;
  return (
    <span className="modes">
      {modes.map((mode) => {
        const item = MODE_ICONS[mode] || { icon: '⚡', label: mode };
        return (
          <span key={mode} className="mode-icon" title={item.label} role="img" aria-label={item.label}>
            {item.icon}
          </span>
        );
      })}
    </span>
  );
}

/**
 * Shows past finished races between races. Aggregates all top generators across past races
 * into a single unified Hall of Fame leaderboard ranked by power, showing their input methods.
 */
export default function HallOfFame() {
  const [races, setRaces] = useState([]);

  useEffect(() => { hallOfFameApi.fetchHallOfFame().then(setRaces); }, []);

  if (!races.length) {
    return null;
  }

  const allPlayers = races.flatMap((race) => {
    const teamNameOf = (id) => (race.teams.find((t) => t.id === id) || {}).name || '';
    return (race.topPlayers || []).map((player) => ({
      name: player.name,
      teamName: teamNameOf(player.team),
      team: player.team,
      modes: Array.from(player.modes || []),
      generated: player.generated,
      raceId: race.raceId,
    }));
  });

  allPlayers.sort((a, b) => b.generated - a.generated);

  return (
    <HallOfFameDiv>
      <h2>Hall of fame</h2>
      <div className="leaderboard">
        <table className="players">
          <tbody>
            {allPlayers.map((player, i) => (
              <tr key={`${player.raceId}-${player.name}-${i}`}>
                <td className="rank">{i + 1}</td>
                <td className="name">
                  <span>{player.name}</span>
                  {player.teamName && (
                    <span className="team">({player.teamName})</span>
                  )}
                  {player.modes && player.modes.length > 0 && (
                    <ModeBadges modes={player.modes} />
                  )}
                </td>
                <td className="power">{powerApi.humanPower(player.generated)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </HallOfFameDiv>
  );
}
