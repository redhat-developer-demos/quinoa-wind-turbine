import React, {useEffect, useState} from 'react'
import styled from 'styled-components'
import WindTurbineButton from './WindTurbineButton';
import { gameApi, powerApi } from '../../api';

const Container = styled.div`
  text-align: center;
  color: white;
  font-size: 2rem;
  margin: 20px;
`

const Status = styled.div`
  margin-top: 20px;
  padding: 20px;
  height: 200px;
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

const TEAM_COLORS = ['#ba2f34', '#b87832'];

const WindTurbine = (props) => {
    const [user, setUser] = useState();
    const [status, setStatus] = useState("offline");
    const [counter, setCounter] = useState(0);

    useEffect(() => {
        gameApi.assign().then(setUser);
    }, [])

    function generatePower() {
        setCounter((c) => ++c);
        powerApi.generate(user).then(r => {});
    }

    useEffect(() => gameApi.events((e) => {
        console.log(`=> Received game event: ${e.type}`);
        switch (e.type) {
            case "start":
                setStatus("started");
                break;
            case "stop":
                setStatus("online");
                break;
            case "ping":
                setStatus(s => s !== "started" ? "online" : "started");
                break;
        }
    }), [setStatus]);

    return (
        <Container>
            {user && (
                <>
                    <Input type="text" readOnly value={user.name}  /> is powering team&nbsp;
                    <Input type="text" readOnly value={user.team} style={{width: "30px"}}  />
                    <Status>DESTINATION IS {status.toUpperCase()}</Status>
                    {status === "started" &&
                        (
                            <>
                                <WindTurbineButton onClick={generatePower} color={TEAM_COLORS[user.team - 1]} />
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