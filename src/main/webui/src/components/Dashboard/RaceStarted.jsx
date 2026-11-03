import React from 'react';
import styled, { keyframes } from 'styled-components';
import { inputInstruction } from '../../inputInstruction';
import { useInputMode } from '../../InputModeContext';

// Long enough for a room to read it from the back, short enough to leave the track visible.
export const START_BANNER_MS = 3000;

const pop = keyframes`
  0% { opacity: 0; transform: translate(-50%, -50%) scale(0.6); }
  12% { opacity: 1; transform: translate(-50%, -50%) scale(1.08); }
  22% { transform: translate(-50%, -50%) scale(1); }
  85% { opacity: 1; transform: translate(-50%, -50%) scale(1); }
  100% { opacity: 0; transform: translate(-50%, -50%) scale(1.05); }
`;

const Banner = styled.div`
  position: fixed;
  top: 50%;
  left: 50%;
  z-index: 20;
  padding: 24px 64px;
  border: 4px solid #6c6;
  border-radius: 16px;
  background-color: rgba(18, 18, 18, 0.92);
  box-shadow: 0 0 48px rgba(102, 204, 102, 0.55), 0 20px 50px rgba(0, 0, 0, 0.6);
  color: white;
  text-align: center;
  white-space: nowrap;
  pointer-events: none;
  animation: ${pop} ${START_BANNER_MS}ms ease-out forwards;

  h1 {
    margin: 0;
    font-size: clamp(3rem, 9vw, 8rem);
    font-weight: 800;
    letter-spacing: 0.02em;
  }

  p {
    margin: 8px 0 0;
    font-size: clamp(1.2rem, 3vw, 2.6rem);
    color: #cfc;
  }

  @media (prefers-reduced-motion: reduce) {
    animation: none;
  }
`;

/**
 * The cue for the room: the dashboard shows it when the race starts, so everybody knows when
 * to start. What it asks for follows the live input mode.
 */
export default function RaceStarted() {
  const inputMode = useInputMode();
  return (
    <Banner role="status" aria-live="assertive">
      <h1>Race Started!</h1>
      <p>{inputInstruction(inputMode)}</p>
    </Banner>
  );
}
