import React, { useEffect, useState } from 'react';
import _ from 'lodash';
import styled from 'styled-components';
import { Play, Pause } from '@styled-icons/heroicons-outline';
import { Trophy, CloudOffline } from '@styled-icons/ionicons-outline';
import { Plug, Reset } from '@styled-icons/boxicons-regular';
import { gameApi, powerApi } from '../../api';
import StopWatch from './StopWatch';
import { SHOW_TOP, TEAMS_CONFIG } from '../../Config';
import { sortTeams } from './DashboardUtils';

const Title = styled.h1`
  text-align: center;
  font-weight: 700;
  font-size: 3rem;
  color: white;
`;

const LeftBarDiv = styled.div`
  position: fixed;
  left: 0;
  top: 0;
  bottom: 0;
  width: 350px;
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
  margin: 20px 0;
  text-align: center;
  color: white;

  button {
    border: 0;
    background: transparent;
    cursor: pointer;

    svg {
      height: 100px;
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
  margin-left: 20px;
  margin-right: 20px;

  .stopwatch {
    font-size: 1.5rem;

    span:last-child {
      font-size: 1rem;
    }
  }
`;

const Header = styled.div`
  display: flex;
  border-bottom: 1px solid white;
  font-size: 2rem;
  justify-content: space-between;

  & > span:first-child {
    flex-grow: 1;

    svg {
      margin-right: 10px;
      margin-top: -10px;
    }
  }

  & > span:not(:first-child) {
    color: white;
    font-size: 1rem;
    line-height: 2rem;
    font-weight: bold;
  }

  .total-power {
    margin-right: 10px;
  }

  .count-players {
    svg {
      margin-top: -5px;
      margin-right: 5px;
    }
  }


`;

function Team(props) {
  const team = _.values(props.team);
  const top = team.sort((a, b) => b.generated - a.generated).slice(0, SHOW_TOP);
  const teamConfig = TEAMS_CONFIG[props.id - 1];
  return (
    <div className={props.className}>
      <Header style={{ color: teamConfig.color }}>
        <span>
          {props.winner === props.id && <Trophy size={32} />}
          Team
          {' '}
          <br />
          {' '}
          {teamConfig.name}
        </span>
        {props.winner < 0 && <span className="total-power">{powerApi.humanPower(props.generated)}</span>}
        {props.time && <span><StopWatch time={props.time} running={false} /></span>}
        {!props.time && (
          <span className="count-players">
            <Plug size={20} />
            {team.length}
          </span>
        )}
      </Header>
      <ol>
        {top.length > 0 ? top.map((u) => (
          <li key={u.name}>
            {u.name}
            {' '}
            -
            {' '}
            {powerApi.humanPower(u.generated)}
          </li>
        )) : (<li>Waiting for players...</li>)}
      </ol>
    </div>
  );
}

const StyledTeam = styled(Team)`
  font-size: 1.0rem;
  color: white;

  li {
    line-height: 25px;
  }
`;

export default function LeftBar(props) {
  const { winner } = props.rank;
  const teamDef = [
    {
      id: 1, team: props.team1, generated: props.power[0], time: props.result.team1,
    },
    {
      id: 2, team: props.team2, generated: props.power[1], time: props.result.team2,
    },
  ];
  sortTeams(teamDef);
  return (
    <LeftBarDiv>
      <Title>The Race</Title>
      <Teams>
        {teamDef.map((d) => (<StyledTeam key={d.id} {...d} winner={winner} />))}
      </Teams>
      <StopWatch time={props.time} setTime={props.setTime} running={props.status === 'started'} />
      <StyledControl winner={winner} status={props.status} />
    </LeftBarDiv>
  );
}
