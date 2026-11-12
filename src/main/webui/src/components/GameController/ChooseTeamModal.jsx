import React from 'react';
import styled from 'styled-components';
import { useTheme } from '../../ThemeContext';
import { getContrastColor } from '../Dashboard/DashboardUtils';

const TeamContainer = styled.div`
  position: fixed;
  top: 0;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: rgba(0, 0, 0, 0.8);
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(3px);
  align-items: center;
  justify-content: center;
`;

const TeamHeader = styled.div`
  display:block;
  float:right;
  position:relative;
  clear:both;
  font-size: 2rem;
`;

const TeamSelectionButton = styled.div`
  
  width: 200px;
  text-transform: uppercase;
  cursor: pointer;
  font-weight: bold;
  display: flex;
  justify-content: center;
  align-items: center;
  font-size: 2rem;
  
  svg {
    margin-right: 10px;
  }

  div {
    padding: 10px;
    width: 100%;
    text-align: center;
  }
`;



export default function ChooseTeamModal(props) {
  // The live theme, so an applied theme renames the buttons without a reload.
  const { teams } = useTheme();
  // A light team color needs dark text; white on a white team was unreadable.
  const buttonStyle = (team) => ({
    backgroundColor: team.color,
    color: getContrastColor(team.color),
  });
  return (
    <TeamContainer>  
      <TeamHeader>
        Choose your team:
      </TeamHeader>
      <TeamSelectionButton onClick={() => props.onClick(1)}>
        <div style={buttonStyle(teams[0])}>Team {teams[0].name}</div>
      </TeamSelectionButton>
      <TeamSelectionButton onClick={() => props.onClick(2)}>
        <div style={buttonStyle(teams[1])}>Team {teams[1].name}</div>
      </TeamSelectionButton>          
    </TeamContainer>
  );
}
