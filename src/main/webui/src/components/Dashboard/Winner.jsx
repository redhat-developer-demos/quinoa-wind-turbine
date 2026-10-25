import _ from 'lodash';
import { Trophy } from '@styled-icons/ionicons-outline';
import React from 'react';
import styled from 'styled-components';
import { powerApi } from '../../api';
import { SHOW_TOP_WINNER } from '../../Config';
import { useTheme } from '../../ThemeContext';
import { topPlayers, getContrastColor } from './DashboardUtils';

const WinnerDiv = styled.div`
  position: fixed;
  color: white;
  border: 2px solid ${(props) => props.color};
  box-shadow: 0 0 32px ${(props) => `${props.color}40`}, 0 20px 50px rgba(0, 0, 0, 0.6);
  left: calc(50% - 400px);
  top: calc(50% - 300px);
  padding: 30px;
  box-sizing: border-box;
  width: 800px;
  height: 600px;
  background-color: #1e1e1e;
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  align-items: center;

  .winner {
    flex-grow: 1;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 24px;
    margin-bottom: 12px;

    .trophy-icon {
      color: #fcd700;
      filter: drop-shadow(0 0 16px rgba(252, 215, 0, 0.45));
    }

    h1 {
      margin: 0;
      font-size: 2.2rem;
      color: white;
      font-weight: 700;
      display: flex;
      align-items: center;
      flex-wrap: wrap;
      gap: 10px;
    }

    .winner-badge {
      display: inline-block;
      padding: 4px 14px;
      border-radius: 6px;
      font-weight: 800;
      box-shadow: 0 3px 10px rgba(0, 0, 0, 0.4);
    }
  }

  .scroller {
    overflow-y: auto;
    padding: 0px 20px 0 20px;
    height: 100%;
  }

  h3 {
    color: white;
    margin-top: 0;
    margin-bottom: 8px;
    text-align: center;
    font-size: 1.1rem;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 8px;
  }

  .team-dot {
    display: inline-block;
    width: 10px;
    height: 10px;
    border-radius: 50%;
  }

  li {
    color: #e5e7eb;
    line-height: 25px;
  }
  
  ol {
    padding-bottom: 30px;
  }
  
  .leaderboards {
    display: flex;
    gap: 16px;
    width: 100%;
  }

  .leaderboards > div {
    flex: 1;
    height: 390px;
    overflow: hidden;
    background: rgba(255, 255, 255, 0.03);
    border: 1px solid rgba(255, 255, 255, 0.07);
    border-radius: 8px;
    padding-top: 12px;
  }
`;

function Leaderboard(props) {
  return (
    <div>
      <h3>
        {props.color && <span className="team-dot" style={{ backgroundColor: props.color }} />}
        {props.title}
      </h3>
      <div className="scroller">
        <ol>
          {props.players.map((u) => (
            <li key={u.name}>
              {u.name}
              {' '}
              -
              {' '}
              {powerApi.humanPower(u.generated)}
            </li>
          ))}
        </ol>
      </div>
    </div>
  );
}

export default function Winner(props) {
  const { teams } = useTheme();
  const teamConfig = teams[props.rank.winner - 1] || teams[0];
  const winnerTextColor = getContrastColor(teamConfig.color);
  return (
    <WinnerDiv color={teamConfig.color}>
      <div className="winner">
        <Trophy size={110} className="trophy-icon" />
        <h1>
          <span
            className="winner-badge"
            style={{ backgroundColor: teamConfig.color, color: winnerTextColor }}
          >
            Team {teamConfig.name}
          </span>
          won the game!
        </h1>
      </div>
      <div className="leaderboards">
        <Leaderboard
          players={topPlayers(props.rank.overall, SHOW_TOP_WINNER)}
          title={`Overall top ${SHOW_TOP_WINNER}`}
        />
        <Leaderboard
          players={topPlayers(props.rank.team1, SHOW_TOP_WINNER)}
          title={`Team ${teams[0].name} top ${SHOW_TOP_WINNER}`}
          color={teams[0].color}
        />
        <Leaderboard
          players={topPlayers(props.rank.team2, SHOW_TOP_WINNER)}
          title={`Team ${teams[1].name} top ${SHOW_TOP_WINNER}`}
          color={teams[1].color}
        />
      </div>
    </WinnerDiv>
  );
}
