import React from 'react';
import { Plug } from '@styled-icons/boxicons-regular';
import { Bolt } from '@styled-icons/boxicons-solid';
import { CloudDone, CloudOffline } from '@styled-icons/ionicons-outline';
import styled from 'styled-components';

const TopBarDiv = styled.div`
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  height: 70px;
  display: flex;
  color: white;
  font-size: 2rem;
  background-color: ${(props) => props.color};
  align-items: center;
  padding-right: 10px;
  padding-left: 10px;

  div {
    text-align: left;
  }
`;

const TeamDiv = styled.div`
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
`;

const UserDiv = styled.div`
  flex-grow: 1;
  font-size: 1.5rem;

  svg {
    margin-right: 10px;
  }
`;

const StatusDiv = styled.div`
  margin-left: 10px;
  color: ${(props) => props.color};
`;

function TopBar(props) {
  const statusColor = props.status !== 'offline' ? 'green' : 'grey';
  return (
    <TopBarDiv color={props.color}>
      <UserDiv>
        <Plug size={32} />
        <span id="user-name">{props.user.name}</span>
      </UserDiv>
      <TeamDiv id="user-team">{props.user.team}</TeamDiv>
      <StatusDiv color={statusColor}>
        {props.status === 'started' && <Bolt size={32} />}
        {props.status === 'offline' && <CloudOffline size={32} />}
        {props.status === 'paused' && <CloudDone size={32} />}
      </StatusDiv>
    </TopBarDiv>
  );
}

export default TopBar;
