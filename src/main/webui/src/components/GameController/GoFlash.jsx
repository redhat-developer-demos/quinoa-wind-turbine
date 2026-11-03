import React from 'react';
import styled, { keyframes } from 'styled-components';
import { inputInstruction } from '../../inputInstruction';
import { effectiveInputs } from '../../inputMode';
import { useInputMode } from '../../InputModeContext';

// Quick: the player should be tapping the windmill, not reading.
export const GO_FLASH_MS = 1200;

const flash = keyframes`
  0% { opacity: 0; transform: translateX(-50%) scale(0.7); }
  15% { opacity: 1; transform: translateX(-50%) scale(1.1); }
  30% { transform: translateX(-50%) scale(1); }
  75% { opacity: 1; }
  100% { opacity: 0; }
`;

// The flash never takes a touch: pointer-events none lets every tap fall through to the
// windmill underneath, and it has no backdrop, so the windmill stays fully visible.
const Flash = styled.div`
  position: fixed;
  top: 90px;
  left: 50%;
  z-index: 10;
  text-align: center;
  color: white;
  text-shadow: 0 2px 12px rgba(0, 0, 0, 0.9), 0 0 24px rgba(102, 204, 102, 0.9);
  pointer-events: none;
  user-select: none;
  animation: ${flash} ${GO_FLASH_MS}ms ease-out forwards;

  strong {
    display: block;
    font-size: 5rem;
    line-height: 1;
    font-weight: 900;
  }

  span {
    display: block;
    margin-top: 4px;
    font-size: 1.2rem;
    font-weight: bold;
    white-space: nowrap;
  }

  @media (prefers-reduced-motion: reduce) {
    animation: none;
    transform: translateX(-50%);
  }
`;

/**
 * The phone's start cue: a quick "GO!" over the top of the screen, with what to do.
 */
export default function GoFlash() {
  const inputMode = useInputMode();
  // What this phone accepts: a device without motion sensors can only tap, whatever the mode.
  const { tap, shake } = effectiveInputs(inputMode);
  return (
    <Flash id="go-flash" role="status" aria-live="assertive">
      <strong>GO!</strong>
      <span>{inputInstruction(inputMode, { tap, shake })}</span>
    </Flash>
  );
}
