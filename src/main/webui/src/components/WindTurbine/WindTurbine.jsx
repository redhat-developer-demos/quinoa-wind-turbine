import React, {useEffect, useState} from 'react'
import styled from 'styled-components'
import WindTurbineButton from './WindTurbineButton';
import {gameApi, powerApi, volumeMeter} from '../../api';

const Container = styled.div`
  text-align: center;
  color: white;
  font-size: 2rem;
  margin: 20px;
`

const Status = styled.div`
  margin-top: 20px;
  padding: 20px;
  font-family: monospace;

  &:after {
    content: ' <<';
    color: orange;
  }

  &:before {
    content: '>> ';
    color: orange;
  }
`


const Input = styled.input`
  font-family: "Indie Flower", "Comic Sans MS", sans-serif;
  text-align: center;
  font-size: 2rem;
  border-radius: 5px;
  border: none;
  width: 300px;
`

const WindTurbine = (props) => {
    const [user, setUser] = useState();
    const [status, setStatus] = useState("offline");
    const [generated, setCounter] = useState(0);
    const [power, setPower] = useState(0);
    useEffect(() => {
        gameApi.assign().then(setUser);
    }, [])

    function generatePower(quantity, fromClick) {
        if (user && status === 'started') {
            setPower(quantity);
            setCounter((c) => c + quantity);
            if (quantity > 0) {
                powerApi.generate(user, quantity).then(r => {
                });
            }
        }
    }


    useEffect(() => gameApi.status(setStatus), [setStatus]);

    return (
        <Container>
            {user && (
                <>
                    <Input type="text" readOnly value={user.name}/> is powering team&nbsp;
                    <Input type="text" readOnly value={user.team} style={{width: "30px"}}/>
                    <Status>DESTINATION IS {status.toUpperCase()}</Status>
                    {status === "started" &&
                    (
                        <>
                            <WindTurbineButton generatePower={generatePower} color={gameApi.TEAM_COLORS[user.team - 1]} generated={generated}/>
                            <p>Generated <b>{generated} MW</b></p>
                        </>
                    )
                    }

                </>
            )}
        </Container>
    )
}

export default WindTurbine;