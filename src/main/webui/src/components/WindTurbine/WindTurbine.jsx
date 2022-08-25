import React, {useEffect, useState} from 'react'
import styled from 'styled-components'
import WindTurbineButton from './WindTurbineButton';
import { gameApi, powerApi, volumeMeter } from '../../api';
import { Bolt } from '@styled-icons/boxicons-solid';
import { CloudDone, CloudOffline } from '@styled-icons/ionicons-outline';
import { Game }  from '@styled-icons/boxicons-regular'

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
      transform:rotate(0deg);
    }
    to {
      transform:rotate(360deg);
    }
  }
`

const User = styled.div`
    flex-grow: 1;
  
  svg {
    margin-right: 10px;
  }
`

const Team =  styled.div`
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

const Generated = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 3rem;
  font-weight: bold;
  line-height: 3rem;
  margin-top: 40px;
  &:after {
    content: 'MW';
    font-size: 1.5rem;
    line-height: 1.5rem;
    font-weight: bold;
  }
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
    const statusColor = status !== 'offline' ? 'green' : 'grey';
    const color = user && gameApi.TEAM_COLORS[user.team - 1];
    return (
        <Container>
            {user && (
                <>
                    <TopBar color={color}>
                        <User><Game size={32}/>{user.name}</User>
                        <Team>{user.team}</Team>
                        <Status color={statusColor}>
                            {status === 'started' && <Bolt size={32}/>}
                            {status === 'offline' && <CloudOffline size={32}/>}
                            {status === 'online' && <CloudDone size={32}/>}
                        </Status>
                    </TopBar>
                    {status === "started" ? (
                        <>
                            <WindTurbineButton generatePower={generatePower} color={color} generated={generated}/>
                            <Generated>{generated}</Generated>
                        </>
                    ):(
                        <Loading><div><img src="./car-1.png" /></div><div>Waiting for game...</div></Loading>
                    )}


                </>
            )}
        </Container>
    )
}

export default WindTurbine;