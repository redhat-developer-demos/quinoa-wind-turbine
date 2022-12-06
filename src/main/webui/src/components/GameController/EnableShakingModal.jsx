import { MobileVibration } from '@styled-icons/boxicons-regular';
import React from 'react';
import styled from 'styled-components';

const EnableShakingOverlay = styled.div`
  position: fixed;
  background-color: #4695EB;
  top: 0;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: rgba(0, 0, 0, 0.8);
  display: flex;
  backdrop-filter: blur(3px);
  align-items: center;
  justify-content: center;
`;

const EnableShakingButton = styled.div`
  background-color: red;
  width: 250px;
  padding: 10px;
  color: white;
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
`;

export default function EnableShakingModal(props) {
  return (
    <EnableShakingOverlay>
      <EnableShakingButton onClick={props.onClick}>
        <MobileVibration size={80} />
        Enable Shaking
      </EnableShakingButton>
    </EnableShakingOverlay>
  );
}
