import React, {useEffect, useState} from 'react';
import styled from 'styled-components'
import { powerApi, gameApi } from '../../api';
import { Play, Pause, Refresh } from '@styled-icons/heroicons-outline';
import { Trophy } from '@styled-icons/ionicons-outline';
import Car from './Car';
import _ from 'lodash';
import { StopWatch } from './StopWatch';
import { SHOW_TOP, CLICK_POWER, NB_CLICK_NEEDED_PER_USER } from '../../Config';

const Title = styled.h1`
  text-align: center;
  font-weight: 700;
  font-size: 3rem;
  color: white;
`

const RaceContainer = styled.div`
  text-align: center;
  display: flex;
  align-items: center;
  position: fixed;
  left: 350px;
  top: 0;
  right: 0;
  bottom: 0;
  
  svg {
    height: auto;
    width: 100%;
  }
  
  .car {
    transition: offset-distance 300ms ease;
    
  }
  
  #car1 {
    offset-path: path('M 745 243 Q 750 340 750 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 Q 670 30 700 50 Q 720 80 720 110 Q 740 180 750 320 ');
    offset-distance: ${props => `${props.distance1}%` };
    transform-origin: 25px 14px;
  }
  #car2 {
    offset-path: path('M 720 230 Q 735 335 720 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 Q 690 80 705 115 Q 720 180 720 320 ');
    offset-distance: ${props => `${props.distance2}%` };
    transform-origin: 25px 14px;
  }
  #car3 {
    offset-distance: ${props => `${props.distance3}%` };
    transform-origin: 25px 14px;
  }
`

const Teams = styled.div`
  display: flex;
  flex-grow: 1;
  flex-direction: column;
  margin-left: 20px;
  margin-right: 20px;
  
  .stopwatch {
    font-size: 1.5rem;
    
    &:before {
      content: 'Time: ';
      font-weight: bold;
    }
  }
`

const Team = (props) => {
    let team = _.values(props.team);
    team = team.sort((a, b) => b.generated - a.generated);
    team = team.slice(0, SHOW_TOP);
    return (
        <div className={props.className}>
            <h1 style={{color: gameApi.TEAM_COLORS[props.id - 1]}}>
                {props.winner === props.id && <Trophy size={32}/>}
                Team {props.id}{props.winner < 0 && <>- {props.generated} MW</>}
            </h1>
            <ol>
                {team.length > 0 ? team.map((u, id) => (
                    <li key={id}>{u.id} - {u.generated} MW</li>
                )) : (<li>Waiting for players...</li>)
                }
            </ol>
            {props.time && <StopWatch time={props.time} running={false} />}
        </div>
    );
}

const StyledTeam = styled(Team)`
  font-size: 1.0rem;
  color: white;
  
  h1 {
    border-bottom: 1px solid white;
    
    svg {
      margin-right: 10px;
      margin-top: -10px;
    }
  }
  
  li {
    line-height: 25px;
  }
`;

const Control = ({className, status, reset}) => (
    <div className={className}>
        {status === 'started' ? (
            <button onClick={() => gameApi.sendEvent('pause')}><Pause /></button>
        ) : (
            <button onClick={() => gameApi.sendEvent('start')}><Play /></button>
        )}
        <button onClick={() => gameApi.sendEvent('reset')}><Refresh /></button>
    </div>
);

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
`;

const Race = (props) => (
    <RaceContainer distance1={props.distance1} distance2={props.distance2}>
        <Car />
    </RaceContainer>
);



function computeDistance(power, nbUsers) {
    if (nbUsers === 0) {
        return 0;
    }
    return (power * 100) / (CLICK_POWER * NB_CLICK_NEEDED_PER_USER * nbUsers);
}

function resetTeam(t) {
    _.forEach(t, u => u.generated = 0);
    return t;
}

function computePower(t) {
    return _.values(t).reduce((a, u) => a + u.generated, 0);
}

function computeWinner(result) {
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

const RawRaceTrack = (props) => {
    const [result, setResult] = useState({});
    const [status, setStatus] = useState("offline");
    const [team1, setTeam1] = useState({});
    const [team2, setTeam2] = useState({});
    const [time, setTime] = useState(0);
    const reset = () => {
        setTeam1(resetTeam);
        setTeam2(resetTeam);
        setTime(0);
        setResult({});
    };
    useEffect(() => powerApi.consume([setTeam1, setTeam2]),
        [setTeam1, setTeam2]);
    useEffect(() => gameApi.events(setStatus, reset), [setStatus]);

    const power1 = computePower(team1);
    const power2 = computePower(team2);
    const nbUsers = 1;
    const distance1 = computeDistance(power1, nbUsers);
    const distance2 = computeDistance(power2, nbUsers);
    useEffect(() => {
        if (!result.team1 && distance1 >= 100) {
            setResult(p => ({...p, team1: time }))
        } else if (!result.team2 && distance2 >= 100) {
            setResult(p => ({...p, team2: time }))
        }
    }, [time, distance1, distance2, setResult, result])
    const winner = computeWinner(result);
    return (
        <div className={props.className}>
            <div className="left-bar">
                <Title>The Race</Title>
                <Teams>
                    <StyledTeam id={1} team={team1} generated={power1} time={result.team1} winner={winner} />
                    <StyledTeam id={2} team={team2} generated={power2} time={result.team2} winner={winner} />
                </Teams>
                <StopWatch time={time} setTime={setTime} running={status === 'started'} />
                <StyledControl status={status} reset={reset} />
            </div>
            <Race distance1={distance1} distance2={distance2}/>
        </div>
    )
}

const RaceTrack = styled(RawRaceTrack)`
  .left-bar {
    position: fixed;
    left: 0;
    top: 0;
    bottom: 0;
    width: 350px;
    background-color: #262626;
    display: flex;
    flex-direction: column;
  }
`

export default RaceTrack;