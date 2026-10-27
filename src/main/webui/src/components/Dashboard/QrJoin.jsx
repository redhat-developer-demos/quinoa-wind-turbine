import React from 'react';
import styled from 'styled-components';
import QRCode from 'react-qr-code';

// The join QR points at the origin, not at window.location.href: the dashboard lives under
// /dashboard on the same host as the player controller at /, so scanning lands players on the
// game, not on the admin login.
const QrBox = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  margin: 0 auto 18px;
  padding: 8px;
  border-radius: 8px;
  background-color: white;
  width: 156px;

  svg {
    display: block;
  }

  span {
    color: #262626;
    font-size: 0.7rem;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.06em;
  }
`;

export default function QrJoin() {
  return (
    <QrBox aria-label={`Scan to join: ${window.location.origin}`}>
      <QRCode value={window.location.origin} size={140} />
      <span>Scan to join</span>
    </QrBox>
  );
}
