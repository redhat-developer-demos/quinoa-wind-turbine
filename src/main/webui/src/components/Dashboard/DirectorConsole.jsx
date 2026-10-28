import React, { useEffect, useRef, useState } from 'react';
import styled from 'styled-components';
import { OPS_URL } from '../../Config';

/**
 * The director console: the race-ops agent's status and its proposals, on the dashboard the
 * audience already watches. The director never acts on a boost or a theme by itself — every
 * proposal waits here for the operator to approve or dismiss, and only approval executes it
 * through the game's admin MCP tools.
 *
 * The console is a client of race-ops only. When race-ops is down (the kill beat), polls fail
 * and this panel removes itself: the race continues from the game's own state and the manual
 * controls, which is the degradation story told on stage.
 */
export default function DirectorConsole() {
  const [state, setState] = useState(null);
  // Hidden until the first successful poll; hidden again after consecutive failures.
  const [reachable, setReachable] = useState(false);
  const failures = useRef(0);

  useEffect(() => {
    if (!OPS_URL) {
      return undefined;
    }
    let stopped = false;
    const refresh = async () => {
      try {
        const response = await fetch(`${OPS_URL}/api/ops/state`);
        if (!response.ok) {
          throw new Error(`status ${response.status}`);
        }
        const next = await response.json();
        if (stopped) {
          return;
        }
        failures.current = 0;
        setReachable(true);
        setState(next);
      } catch {
        if (stopped) {
          return;
        }
        // The director is allowed to be down; give it a few polls before disappearing.
        failures.current += 1;
        if (failures.current >= 3) {
          setReachable(false);
        }
      }
    };
    refresh();
    const timer = setInterval(refresh, 2000);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, []);

  if (!OPS_URL || !reachable || !state) {
    return null;
  }
  const engagement = state.race && state.race.engagement;
  const pending = state.pending || [];
  return (
    <Console aria-label="Race director console">
      {engagement && (
        <Status>
          <span className="dot" />
          <span>director</span>
          <span className="race">
            {engagement.raceId}
            {' · '}
            {engagement.status.toLowerCase()}
            {' · '}
            {engagement.teams.reduce((sum, t) => sum + t.tappers, 0)} players
          </span>
        </Status>
      )}
      {pending.map((p) => (
        <Card key={p.proposal.id}>
          <Message>{p.proposal.message}</Message>
          <Actions>
            <button className="approve" onClick={() => decide(p.proposal.id, 'approve')}>
              approve
            </button>
            <button onClick={() => decide(p.proposal.id, 'dismiss')}>dismiss</button>
          </Actions>
          <Meta>
            {p.proposal.type === 'theme'
              ? `theme: ${p.proposal.presetId}`
              : `team ${p.proposal.team} × ${p.proposal.multiplier} for ${p.proposal.seconds}s`}
          </Meta>
        </Card>
      ))}
    </Console>
  );

  async function decide(id, action) {
    try {
      await fetch(`${OPS_URL}/api/ops/proposals/${id}/${action}`, { method: 'POST' });
    } catch {
      // The next poll refreshes; a failed decision can be clicked again.
    }
  }
}

// In the dashboard's top strip, flowing under the commentary ticker. The cards take their
// content width so a long message wraps inside the card instead of stretching across the
// track; the right edge belongs to the theme panel.
const Console = styled.div`
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  max-width: min(420px, 40vw);
`;

const Status = styled.div`
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 12px;
  border-radius: 5px;
  background-color: rgba(38, 38, 38, 0.85);
  color: #9ad;
  font-size: 0.85rem;
  width: fit-content;

  .dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: #6c6;
  }

  .race {
    color: #aaa;
  }
`;

const Card = styled.div`
  border: 1px solid #9ad;
  border-radius: 6px;
  background-color: rgba(22, 34, 44, 0.92);
  padding: 10px 14px;
`;

const Message = styled.p`
  margin: 0 0 8px;
  color: white;
  font-size: 1rem;
  line-height: 1.35;
`;

const Actions = styled.div`
  display: flex;
  gap: 8px;

  button {
    font: inherit;
    font-size: 0.9rem;
    cursor: pointer;
    border-radius: 4px;
    border: 1px solid #888;
    background-color: #2c3e50;
    color: #eee;
    padding: 3px 14px;
  }

  button.approve {
    border-color: #6c6;
    color: #6c6;
  }
`;

const Meta = styled.div`
  margin-top: 6px;
  color: #888;
  font-size: 0.75rem;
`;
