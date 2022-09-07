import React, {useEffect, useState} from 'react'
import styled from 'styled-components'
import Generator from './Generator';
import {gameApi, powerApi} from '../../api';
import {Bolt} from '@styled-icons/boxicons-solid';
import {CloudDone, CloudOffline} from '@styled-icons/ionicons-outline';
import {Plug} from '@styled-icons/boxicons-regular'
import {ENABLE_SHAKING} from '../../Config';
import {sensors} from "../../api";

const Container = styled.div`
  text-align: center;
  color: white;
  font-size: 1rem;
  display: flex;
  align-items: center;
  top: 70px;
  bottom: 00px;
  width: 100%;
  position: fixed;
  justify-content: center;
  flex-direction: column;
`

const Status = styled.div`
  margin-left: 10px;
  color: ${props => props.color};
`

const EnableShakingButton = styled.div`
  background-color: #4695EB;
  padding: 10px;
  color: white;
  text-transform: uppercase;
  cursor: pointer;
  position: fixed;
  bottom: 0px;
  left: 0;
  right: 0;
  font-weight: bold;
`

const Loading = styled.div`
  display: flex;
  flex-direction: column;
  font-size: 2rem;

  img {
    height: 40px;
    animation: spin 3s linear infinite;
  }

  & > div:first-child {
    flex-basis: 100px;
  }

  @keyframes spin {
    from {
      transform: rotate(0deg);
    }
    to {
      transform: rotate(360deg);
    }
  }
`

const User = styled.div`
  flex-grow: 1;
  font-size: 1.5rem;

  svg {
    margin-right: 10px;
  }
`

const Team = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 1.5rem;
  font-weight: bold;
  line-height: 1.5rem;

  &:before {
    content: 'Team';
    font-size: 0.7rem;
    line-height: 1rem;
    font-weight: bold;
  }
`

const TopBar = styled.div`
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 70px;
  display: flex;
  color: white;
  font-size: 2rem;
  background-color: ${props => props.color};
  align-items: center;
  padding-right: 10px;
  padding-left: 10px;

  div {
    text-align: left;
  }
`

const GeneratedIndicator = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 3rem;
  font-weight: bold;
  line-height: 3rem;
  margin-top: 40px;

  &:after {
    content: '${props => props.unit}';
    font-size: 1.5rem;
    line-height: 1.5rem;
    font-weight: bold;
  }
`

const GameController = (props) => {
    const [user, setUser] = useState();
    const [status, setStatus] = useState("offline");
    const [generated, setCounter] = useState(0);
    const [power, setPower] = useState(0);
    const [pingTimeout, setPingTimeout] = useState();
    const [shakingEnabled, setShakingEnabled] = useState(false);
    useEffect(() => {
        gameApi.assign().then(setUser);
    }, []);

    function generatePower(quantity, fromClick) {
        if (user && (quantity === 0 || status === 'started')) {
            console.log(`Generate: ${quantity}`);
            setPower(quantity);
            clearTimeout(pingTimeout);
            setPingTimeout(null);
            setCounter((c) => c + quantity);
            powerApi.generate(user, quantity).then(r => {});
        }
    }

    const reset = () => {
        setPower(0);
        setCounter(0);
    };
    useEffect(() => {
        if (user && !pingTimeout) {
            setPingTimeout(p => !p ? setTimeout(() => generatePower(0), 3000) : null);
        }
        return () => clearTimeout(pingTimeout);
    }, [user, pingTimeout, setPingTimeout, setPower, setCounter])
    useEffect(() => gameApi.status(setStatus, reset), [setStatus]);
    const statusColor = status !== 'offline' ? 'green' : 'grey';
    const color = user && gameApi.TEAM_COLORS[user.team - 1];

    function enableShaking() {
        sensors.enableShakeSensor();
        setShakingEnabled(true);
    }

    return (
        <Container>
            {user && (
                <>
                    <TopBar color={color}>
                        <User><Plug size={32}/><span id="user-name">{user.name}</span></User>
                        <Team id="user-team">{user.team}</Team>
                        <Status color={statusColor}>
                            {status === 'started' && <Bolt size={32}/>}
                            {status === 'offline' && <CloudOffline size={32}/>}
                            {status === 'paused' && <CloudDone size={32}/>}
                        </Status>
                    </TopBar>
                    {ENABLE_SHAKING && !shakingEnabled &&
                    <EnableShakingButton onClick={enableShaking}>Enable Shaking</EnableShakingButton>}
                    {status === "started" ? (
                        <>
                            <Generator generatePower={generatePower} color={color} generated={generated}
                                       shakingEnabled={shakingEnabled}/>
                            <GeneratedIndicator
                                unit={powerApi.humanPowerUnit(generated)}>{powerApi.humanPowerValue(generated)}</GeneratedIndicator>
                        </>
                    ) : (
                        <>
                            <Loading>
                                <div><img src={`./car-${user.team}.png`}/></div>
                                <div>Waiting for game...</div>
                            </Loading>
                        </>
                    )}
                </>
            )}
        </Container>
    )
}

export default GameController;