import React, { useEffect, useState } from 'react';
import _ from 'lodash';
import styled from 'styled-components';
import { Play, Pause } from '@styled-icons/heroicons-outline';
import { Trophy, CloudOffline } from '@styled-icons/ionicons-outline';
import { Plug, Reset } from '@styled-icons/boxicons-regular';
import { gameApi, powerApi } from '../../api';
import StopWatch from './StopWatch';
import QrJoin from './QrJoin';
import { SHOW_TOP } from '../../Config';
import { useTheme } from '../../ThemeContext';
import { sortTeams, getContrastColor } from './DashboardUtils';

const Title = styled.h1`
  text-align: center;
  font-weight: 700;
  font-size: 2.25rem;
  margin: 18px 0;
  color: white;
`;

// Keep this in sync with the track's left offset in RaceTrack.jsx and the panel offsets in
// HallOfFame.jsx and Winner.jsx. A narrow sidebar leaves the race track as much width as possible.
const LeftBarDiv = styled.div`
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: 200px;
  background-color: #262626;
  display: flex;
  flex-direction: column;
`;

function Control({ className, status, winner }) {
  const [clicked, setClicked] = useState(false);

  function send(type) {
    setClicked(true);
    gameApi.sendEvent(type);
  }

  useEffect(() => {
    setClicked(false);
  }, [status]);
  return (
    <div className={className}>
      {status === 'offline' ? (<button disabled><CloudOffline /></button>) : (
        <>
          {winner < 0 && (status === 'started' ? (
            <button id="pause" onClick={() => send('pause')} disabled={clicked}><Pause /></button>
          ) : (
            <button id="play" onClick={() => send('start')} disabled={clicked}><Play /></button>
          ))}
          {status !== 'initial'
                    && <button id="reset" onClick={() => send('reset')} disabled={clicked}><Reset /></button>}
        </>
      )}

    </div>
  );
}

const StyledControl = styled(Control)`
  margin: 14px 0 24px;
  text-align: center;
  color: white;

  button {
    border: 0;
    background: transparent;
    cursor: pointer;

    svg {
      height: 68px;
      color: white;
    }
  }

  button:disabled {
    cursor: not-allowed;

    svg {
      color: #a2a2a2;
    }
  }
`;

const Teams = styled.div`
  display: flex;
  flex-grow: 1;
  flex-direction: column;
  margin-left: 14px;
  margin-right: 14px;

  .stopwatch {
    font-size: 1.5rem;

    span:last-child {
      font-size: 1rem;
    }
  }
`;

// Stacked so a long team name never collides with the stats in the narrow sidebar.
const Header = styled.div`
  border-bottom: 1px solid rgba(255, 255, 255, 0.2);
  padding-bottom: 8px;
  margin-bottom: 6px;

  .team-badge {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    padding: 4px 10px;
    border-radius: 6px;
    font-size: 1.15rem;
    font-weight: 700;
    line-height: 1.2;
    box-shadow: 0 2px 4px rgba(0, 0, 0, 0.35);
  }

  .team-stats {
    display: flex;
    align-items: center;
    gap: 16px;
    margin-top: 8px;
    color: white;
    font-size: 0.95rem;
    font-weight: bold;
  }

  .count-players {
    display: inline-flex;
    align-items: center;
    gap: 5px;
  }
`;

function Team(props) {
  const { teams } = useTheme();
  const team = _.values(props.team);
  const top = team.sort((a, b) => b.generated - a.generated).slice(0, SHOW_TOP);
  const teamConfig = teams[props.id - 1] || { name: `Team ${props.id}`, color: '#9e9e9e' };
  const teamTextColor = getContrastColor(teamConfig.color);
  return (
    <div className={props.className}>
      <Header>
        <div
          className="team-badge"
          style={{ backgroundColor: teamConfig.color, color: teamTextColor }}
        >
          {props.winner === props.id && <Trophy size={20} />}
          <span>Team {teamConfig.name}</span>
        </div>
        <div className="team-stats">
          {props.winner < 0 && <span className="total-power">{powerApi.humanPower(props.generated)}</span>}
          {props.time && <StopWatch time={props.time} running={false} />}
          {!props.time && (
            <span className="count-players">
              <Plug size={18} />
              {team.length}
            </span>
          )}
        </div>
      </Header>
      <ol>
        {top.length > 0 ? top.map((u) => (
          <li key={u.name}>
            <span className="player-name" title={u.name}>{u.name}</span>
            <span className="player-power">{powerApi.humanPower(u.generated)}</span>
          </li>
        )) : (<li className="waiting">Waiting for players...</li>)}
      </ol>
    </div>
  );
}

// Each row is one line: the name truncates and the power stays put, so a long name or a
// growing number never wraps and shifts the rest of the menu.
const StyledTeam = styled(Team)`
  font-size: 0.95rem;
  color: white;

  ol {
    list-style: none;
    margin: 0;
    padding: 0;
    counter-reset: rank;
  }

  li {
    display: flex;
    align-items: baseline;
    gap: 6px;
    line-height: 25px;
    counter-increment: rank;
  }

  li::before {
    content: counter(rank) '.';
    flex: none;
    width: 1.3em;
    text-align: right;
  }

  li.waiting::before {
    content: none;
  }

  .player-name {
    flex: 1 1 auto;
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .player-power {
    flex: none;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
  }
`;

export default function LeftBar(props) {
  const { winner } = props.rank;
  const teamDef = [...props.teams];
  sortTeams(teamDef);
  return (
    <LeftBarDiv>
      <Title>The Race</Title>
      <Teams>
        {teamDef.map((d) => (<StyledTeam key={d.id} {...d} winner={winner} />))}
      </Teams>
      <StopWatch time={props.time} setTime={props.setTime} running={props.status === 'started'} />
      <StyledControl winner={winner} status={props.status} />
      <QrJoin />
    </LeftBarDiv>
  );
}
