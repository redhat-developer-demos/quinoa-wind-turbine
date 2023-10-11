import React, { useEffect, useState } from 'react';
import styled from 'styled-components';
import { gameApi, powerApi, sensors } from '../../api';
import Generator from './Generator';
import { ENABLE_SHAKING, TEAMS_CONFIG } from '../../Config';
import TopBar from './TopBar';
import EnableShakingModal from './EnableShakingModal';
import RankModal from './RankModal';

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
`;

const LoadingDiv = styled.div`
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
`;

function StatusContent(props) {
  switch (props.status.value) {
    case 'started':
      return (
        <Generator
          generatePower={props.generatePower}
          color={props.color}
          generated={props.generated}
          shakingEnabled={props.shakingEnabled}
        />
      );
    case 'finished':
      return (
        <RankModal data={props.status.data} user={props.user} />
      );
    default:
      return (
        <LoadingDiv>
          <div><img src={`./${TEAMS_CONFIG[props.user.team - 1].car}.png`} /></div>
          <div>Waiting for game...</div>
        </LoadingDiv>
      );
  }
}

export default function GameController() {
  const [user, setUser] = useState();
  const [status, setStatus] = useState({ value: 'offline' });
  const [generated, setCounter] = useState(0);
  const [pingTimeout, setPingTimeout] = useState();
  const [shakingEnabled, setShakingEnabled] = useState(false);

  function reset() {
    setCounter(0);
  }

  function generatePower(quantity) {
    if (user && (quantity === 0 || status.value === 'started')) {
      console.log(`Generate: ${quantity}`);
      clearTimeout(pingTimeout);
      setPingTimeout(null);
      setCounter((c) => c + quantity);
      powerApi.generate(user, quantity).then(() => {
      });
    }
  }

  function enableShaking(e) {
    e.preventDefault();
    sensors.enableShakeSensor();
    setShakingEnabled(true);
  }

  useEffect(() => {
    gameApi.assign().then(setUser);
  }, []);
  useEffect(() => {
    if (user && !pingTimeout) {
      setPingTimeout((p) => (!p ? setTimeout(() => generatePower(0), 3000) : null));
    }
    return () => clearTimeout(pingTimeout);
  }, [user, pingTimeout]);
  useEffect(() => gameApi.status(setStatus, reset), []);
  const teamConfig = user && TEAMS_CONFIG[user.team - 1];
  const teamColor = teamConfig && teamConfig.color;
  const teamName = teamConfig && teamConfig.name;
  return (
    <Container>
      {user && (
        <>
          <TopBar color={teamColor} user={user} status={status.value} teamname={teamName} />
          <StatusContent
            user={user}
            status={status}
            generatePower={generatePower}
            color={teamColor}
            generated={generated}
            shakingEnabled={shakingEnabled}
          />
          {ENABLE_SHAKING && !shakingEnabled
                    && <EnableShakingModal onClick={enableShaking} />}
        </>
      )}
    </Container>
  );
}
