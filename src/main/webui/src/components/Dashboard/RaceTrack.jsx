import * as React from 'react';
import styled from 'styled-components';
import { TEAMS_CONFIG } from '../../Config';

const RaceDiv = styled.div`
  text-align: center;
  display: flex;
  align-items: center;
  position: fixed;
  left: 350px;
  top: 0;
  right: 0;
  bottom: 0;
  
  svg {
    height: auto;
    width: 100%;
  }
  
  .car {
    transition: offset-distance 1000ms ease;
    
  }
  
  #car1 {
    offset-path: path('M 745 243 Q 750 340 750 420 Q 695 515 560 470 Q 490 350 420 360 Q 350 350 310 420 Q 240 510 180 450 L 80 270 Q 70 200 120 150 L 270 90 Q 310 70 350 90 L 410 110 Q 470 110 510 80 L 570 40 Q 600 20 640 20 Q 670 30 700 50 Q 720 80 720 110 Q 740 180 750 320 ');
    offset-distance: ${(props) => `${props.distance1}%`};
    transform-origin: 25px 14px;
  }
  #car2 {
    offset-path: path('M 720 230 Q 735 335 720 430 Q 670 490 570 440 Q 505 335 420 330 Q 330 330 290 410 Q 240 490 190 415 L 105 260 Q 90 195 150 160 L 280 110 Q 310 90 350 110 L 420 130 Q 480 130 530 100 L 590 60 Q 610 40 640 40 Q 690 80 705 115 Q 720 180 720 320 ');
    offset-distance: ${(props) => `${props.distance2}%`};
    transform-origin: 25px 14px;
  }
  #car3 {
    offset-distance: ${(props) => `${props.distance3}%`};
    transform-origin: 25px 14px;
  }
`;

function RaceTrackSvg(props) {
  return (
    <svg
      width={777}
      height={518}
      viewBox="0 0 777 518"
      xmlSpace="preserve"
      xmlnsXlink="http://www.w3.org/1999/xlink"
      xmlns="http://www.w3.org/2000/svg"
      {...props}
    >
      <image
        width={777}
        height={518}
        preserveAspectRatio="none"
        xlinkHref="race-track-1.png"
      />

      <g className="car" id="car1">
        <image
          xlinkHref={`${TEAMS_CONFIG[0].car}.png`}
        />
      </g>
      <g className="car" id="car2">
        <image
          xlinkHref={`${TEAMS_CONFIG[1].car}.png`}
        />
      </g>
    </svg>
  );
}

function RaceTrack(props) {
  return (
    <RaceDiv distance1={props.distances[0]} distance2={props.distances[1]}>
      <RaceTrackSvg />
    </RaceDiv>
  );
}

export default RaceTrack;
