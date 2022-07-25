import React, {useEffect, useState} from 'react';
import styled from 'styled-components'
import { powerApi, gameApi } from '../../api';

const RaceTrackContainer = styled.div`
    margin: 10px;
`

const RaceContainer = styled.div`
  
  margin-top:2%;
  background:url('race-track.png') repeat-x;
  height:500px;
  width:100%;
  position: relative; 

  img {
    display:block;
    position:relative;
    z-index: 10;
  }
  
  .car-1 {
    top: 30px;
    transition: left 200ms ease-in-out;
  }

  .car-2 {
    top: 140px;
    transition: left 200ms ease-in-out;
  }
  
  &:after {
    content: '';
    position: absolute;
    display: block;
    top: 0;
    left: 80%;
    height: 416px;
    border-left: 20px dashed white;
}
`

const RaceTrack = (props) => {
    const [power1, setPower1] = useState(0);
    const [power2, setPower2] = useState(0);
    const [team1, setTeam1] = useState(new Set());
    const [team2, setTeam2] = useState(new Set());

    useEffect(() => powerApi.consume([setPower1, setPower2], [setTeam1, setTeam2]),
        [setPower1, setPower2, setTeam1, setTeam2]);

    return(
        <RaceTrackContainer>
            <p><b>Team 1: </b> {Array.from(team1).join(", ")}</p>
            <p><b>Team 2: </b> {Array.from(team2).join(", ")}</p>
            <button onClick={() => gameApi.sendEvent('start')}>Start Game</button>
            <button onClick={() => gameApi.sendEvent('stop')}>Stop Game</button>
            <RaceContainer>
                <img src="car-1.png" className="car car-1" style={ { left: power1/10 + '%' } } />
                <img src="car-2.png" className="car car-2" style={ { left: power2/10 + '%' } }/>
            </RaceContainer>
        </RaceTrackContainer>
    )
}

export default RaceTrack;