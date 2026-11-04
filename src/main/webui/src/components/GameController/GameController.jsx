import React, { useEffect, useRef, useState } from 'react';
import styled from 'styled-components';
import { gameApi, playerSocket, sensors } from '../../api';
import Generator from './Generator';
import { useTheme } from '../../ThemeContext';
import { useInputMode, useSetInputMode } from '../../InputModeContext';
import { effectiveInputs } from '../../inputMode';
import TopBar from './TopBar';
import FormulaOneCar, { FormulaOneCarDefs } from '../Dashboard/FormulaOneCar';
import EnableShakingModal from './EnableShakingModal';
import ChooseTeamModal from './ChooseTeamModal';
import RankModal from './RankModal';
import GoFlash, { GO_FLASH_MS } from './GoFlash';

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

  svg {
    height: 60px;
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

const StandingDiv = styled.div`
  margin-top: 20px;
  font-size: 1.2rem;
`;

const NotificationDiv = styled.div`
  position: fixed;
  top: 80px;
  left: 10px;
  right: 10px;
  padding: 10px;
  border-radius: 5px;
  background-color: rgba(0, 0, 0, 0.8);
  font-size: 1.5rem;
  font-weight: bold;
`;

function Standing({ standing }) {
  return (
    <StandingDiv id="standing">
      {standing.teams > 1 && <div>Team position: {standing.teamPosition}/{standing.teams}</div>}
      <div>Your rank in the team: {standing.rankInTeam}/{standing.teamPlayers}</div>
    </StandingDiv>
  );
}

function StatusContent(props) {
  const { teams } = useTheme();
  const team = teams[props.user.team - 1];
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
          <div>
            <svg viewBox="0 0 50 28" aria-hidden="true">
              <defs><FormulaOneCarDefs /></defs>
              <FormulaOneCar color={team.color} accent={team.accent || undefined} logo={team.logo || undefined}
                ski={team.car === 'car-ski'} />
            </svg>
          </div>
          <div>Waiting for game...</div>
        </LoadingDiv>
      );
  }
}

export default function GameController() {
  const { teams } = useTheme();
  const inputMode = useInputMode();
  const setInputMode = useSetInputMode();
  const inputs = effectiveInputs(inputMode);
  const [user, setUser] = useState();
  const [status, setStatus] = useState({ value: 'offline' });
  const [generated, setCounter] = useState(0);
  const [standing, setStanding] = useState();
  const [notification, setNotification] = useState();
  const [showGo, setShowGo] = useState(false);
  const previousStatus = useRef();
  const [shakingEnabled, setShakingEnabled] = useState(false);
  const [teamChosen, setTeamChosen] = useState(false);
  const socket = useRef();

  const refreshInputMode = React.useCallback(() => {
    fetch('/api/config')
      .then((r) => r.json())
      .then((cfg) => {
        if (cfg && cfg.inputMode) {
          setInputMode(cfg.inputMode);
        }
      })
      .catch(() => {});
  }, [setInputMode]);

  function reset() {
    setCounter(0);
    setStanding(null);
  }

  function generatePower(quantity, mode = 'tap') {
    if (user && socket.current && status.value === 'started') {
      setCounter((c) => c + quantity);
      socket.current.generate(quantity, mode);
    }
  }

  function enableShaking(e) {
    e.preventDefault();
    sensors.enableShakeSensor();
    setShakingEnabled(true);
  }

  function chooseTeam(team){
    console.log("user chose team " + team);
    setTeamChosen(true);
    gameApi.assign(team).then(setUser);
  }
  
  // The server registers the player when the connection opens, and again after each reset
  useEffect(() => {
    if (!user) {
      return undefined;
    }
    socket.current = playerSocket.connect(user, {
      onOpen: refreshInputMode,
      onInputMode: refreshInputMode,
      onEvent: (e) => gameApi.applyGameEvent(e, setStatus, reset),
      onStanding: setStanding,
      onNotification: setNotification,
      onOffline: () => setStatus({ value: 'offline' }),
    });
    return () => {
      socket.current.close();
      socket.current = null;
    };
  }, [user, refreshInputMode]);
  // Flash GO when the race starts under the player's eyes; not when joining or reconnecting
  // to a race that is already running.
  useEffect(() => {
    const justStarted = previousStatus.current === 'paused' && status.value === 'started';
    previousStatus.current = status.value;
    setShowGo(justStarted);
    if (!justStarted) {
      return undefined;
    }
    const timeout = setTimeout(() => setShowGo(false), GO_FLASH_MS);
    return () => clearTimeout(timeout);
  }, [status.value]);
  useEffect(() => {
    if (!notification) {
      return undefined;
    }
    const timeout = setTimeout(() => setNotification(null), 3000);
    return () => clearTimeout(timeout);
  }, [notification]);
  const teamConfig = user && teams[user.team - 1];
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
          {status.value === 'started' && standing && <Standing standing={standing} />}
          {showGo && <GoFlash />}
          {notification && <NotificationDiv id="notification">{notification}</NotificationDiv>}
          {inputs.shake && !shakingEnabled
                    && <EnableShakingModal onClick={enableShaking} />}
        </>
      )}
      {!teamChosen && <ChooseTeamModal onClick={chooseTeam} />}
    </Container>
  );
}
