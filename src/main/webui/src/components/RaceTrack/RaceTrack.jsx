import React, {useEffect, useState} from 'react';
import styled from 'styled-components'

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


function powerConsumer(team, setPower, setTeam) {
    const powerStream = new EventSource(`/api/power/stream/${team}`);

    function getRealtimeData(n) {
        if (n.quantity > 0) {
            setPower((p) => p + n.quantity);
            setTeam((p) => new Set([...Array.from(p), n.nickname]));
        }
    }

    powerStream.onmessage = m => getRealtimeData(JSON.parse(m.data));
    powerStream.onerror = (e) => {
        console.error(e);
        powerStream.close();
    }
    return () => {
        powerStream.close();
    };
}

const RaceTrack = (props) => {
    const [power1, setPower1] = useState(0);
    const [power2, setPower2] = useState(0);
    const [team1, setTeam1] = useState(new Set());
    const [team2, setTeam2] = useState(new Set());

    useEffect(() => {
        return powerConsumer(1, setPower1, setTeam1);
    }, [setPower1]);

    useEffect(() => {
        return powerConsumer(2, setPower2, setTeam2);
    }, [setPower2]);

    return(
        <>
            <h2>Team 1</h2>
            <p>{Array.from(team1).join(", ")}</p>
            <h2>Team 2</h2>
            <p>{Array.from(team2).join(", ")}</p>
        <RaceContainer>
            <img src="car-1.png" className="car car-1" style={ { left: power1 + '%' } } />
            <img src="car-2.png" className="car car-2" style={ { left: power2 + '%' } }/>
        </RaceContainer>
        </>
    )
}

export default RaceTrack;