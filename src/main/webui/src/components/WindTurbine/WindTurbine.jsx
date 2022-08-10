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
    const [counter, setCounter] = useState(0);
    const [power, setPower] = useState(0);
    const [speed, setSpeed] = useState(0);
    const [timer, setTimer] = useState();
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
                setTimer(p => {
                    if (p) {
                        clearTimeout(p);
                    }
                    return setTimeout(() => setPower(0), 1000);
                });
            }
        }
    }


    useEffect(() => {
        if(power > 0) {
            let s = 5100 - (power * 5000) / 100;
            console.log(s);
            setSpeed(s);
        } else {
            setSpeed(0);
        }
    }, [power, setSpeed])

    useEffect(() => gameApi.events(setStatus), [setStatus]);

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
                            <WindTurbineButton generatePower={generatePower} color={gameApi.TEAM_COLORS[user.team - 1]} speed={speed}/>
                            <p>Generated <b>{counter} MW</b></p>
                        </>
                    )
                    }

                </>
            )}
        </Container>
    )
}

export default WindTurbine;