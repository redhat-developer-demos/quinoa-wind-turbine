import React, { useEffect, useState } from 'react';
import styled, { keyframes } from 'styled-components';
import { RACE_ANIMATION_MS } from '../Dashboard/RaceAnimation';
import { calculateRank } from './RankModalUtils';

const pulse = keyframes`
  0% { transform: scale(0.95); opacity: 0.8; }
  50% { transform: scale(1.05); opacity: 1; }
  100% { transform: scale(0.95); opacity: 0.8; }
`;

const RankModalOverlay = styled.div`
  position: fixed;
  top: 70px;
  bottom: 0;
  left: 0;
  right: 0;
  background-color: rgba(0, 0, 0, 0.85);
  display: flex;
  flex-direction: column;
  backdrop-filter: blur(4px);
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 20px;
  
  h1 {
    font-size: 2.2rem;
    text-transform: uppercase;
    margin-bottom: 1rem;
    letter-spacing: 1px;
  }
  
  .rank {
    font-size: 6rem;
    font-weight: bold;
    color: #fdd835;
  }

  .flag {
    font-size: 4.5rem;
    animation: ${pulse} 1.2s ease-in-out infinite;
  }

  .subtitle {
    font-size: 1.3rem;
    color: #e0e0e0;
    margin-top: 0.5rem;
  }
`;

export default function RankModal(props) {
  const [showRank, setShowRank] = useState(false);

  useEffect(() => {
    // Delay rank reveal so the car visually crosses the finish line on the dashboard
    const timer = setTimeout(() => {
      setShowRank(true);
    }, RACE_ANIMATION_MS);
    return () => clearTimeout(timer);
  }, []);

  const rank = calculateRank(props.data?.overall, props.user?.name);

  if (!showRank) {
    return (
      <RankModalOverlay id="finish-animation">
        <div className="flag">🏁</div>
        <h1>Finish Line!</h1>
        <div className="subtitle">Watching the cars cross...</div>
      </RankModalOverlay>
    );
  }

  return (
    <RankModalOverlay id="rank-modal">
      <h1>Overall Rank</h1>
      <div className="rank">{rank}</div>
    </RankModalOverlay>
  );
}
