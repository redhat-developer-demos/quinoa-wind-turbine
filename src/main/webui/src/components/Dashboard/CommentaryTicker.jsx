import React from 'react';
import styled from 'styled-components';

// Sits in the dashboard's top strip, below the commentary ticker. A long commentary line
// wraps and grows the ticker, and the strip's column flow keeps this under it.
const Ticker = styled.div`
  align-self: stretch;
  padding: 12px 18px;
  box-sizing: border-box;
  border: 1px solid #666;
  border-radius: 5px;
  background-color: #262626;
  color: white;
  font-size: clamp(1rem, 1.8vw, 1.5rem);
  line-height: 1.4;
  overflow-wrap: anywhere;

  &:empty {
    display: none;
  }
`;

export default function CommentaryTicker({ commentary }) {
  // Keep the live region mounted so assistive technology announces later updates.
  // Text stays still, including when the user prefers reduced motion.
  return (
    <Ticker role="status" aria-live="polite" aria-atomic="true" aria-label="Race commentary">
      {commentary?.text || null}
    </Ticker>
  );
}
