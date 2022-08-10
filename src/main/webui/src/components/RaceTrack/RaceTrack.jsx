import React, {useEffect, useState} from 'react';
import styled from 'styled-components'
import { powerApi, gameApi } from '../../api';
import { PlayBtn, StopBtn } from '@styled-icons/bootstrap';
import Car from "./Car";

const RaceContainer = styled.div`
  text-align: center;
  
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
  flex-direction: column;
  margin-left: 20px;
  margin-right: 20px;
`

const Team = (props) => (
    <div className={props.className}>
        <h1 style={{color: gameApi.TEAM_COLORS[props.id - 1]}}>Team {props.id}</h1>
        <ul>
            {props.team.size > 0 ? Array.from(props.team).map((u, id) => (
            <li key={id}>{u}</li>
            )) : (<li>Waiting for players...</li>)
            }
        </ul>
    </div>
);

const StyledTeam = styled(Team)`
  font-size: 1.0rem;
  color: white;
  
  h1 {
    border-bottom: 1px solid white;
  }
`;

const Control = ({className, status}) => (
    <div className={className}>
        {status === 'started' ? (
            <button onClick={() => gameApi.sendEvent('stop')}><StopBtn /></button>
        ) : (
            <button onClick={() => gameApi.sendEvent('start')}><PlayBtn /></button>
        )}
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

const CLICK_POWER = 30;
const NB_CLICK_NEEDED_PER_USER = 50;

function computeDistance(power, nbUsers) {
    if (nbUsers === 0) {
        return 0;
    }
    return (power * 100) / (CLICK_POWER * NB_CLICK_NEEDED_PER_USER * nbUsers);
}

const RawRaceTrack = (props) => {
    const [status, setStatus] = useState("offline");
    const [power1, setPower1] = useState(0);
    const [power2, setPower2] = useState(0);
    const [team1, setTeam1] = useState(new Set());
    const [team2, setTeam2] = useState(new Set());


    useEffect(() => powerApi.consume([setPower1, setPower2], [setTeam1, setTeam2]),
        [setPower1, setPower2, setTeam1, setTeam2]);
    useEffect(() => gameApi.events(setStatus), [setStatus]);
    const nbUsers = 1;
    return (
        <div className={props.className}>
            <div className="left-bar">
                <StyledControl status={status}/>
                <Teams>
                    <StyledTeam id={1} team={team1}/>
                    <StyledTeam id={2} team={team2}/>
                </Teams>
            </div>
            <Race distance1={computeDistance(power1, nbUsers)} distance2={computeDistance(power2, nbUsers)}/>
        </div>
    )
}

const RaceTrack = styled(RawRaceTrack)`
  margin: 20px auto;
  display: flex;
  width: 1127px;
  .left-bar {
    flex-basis: 350px;
    background-color: #262626;
  }
`

export default RaceTrack;