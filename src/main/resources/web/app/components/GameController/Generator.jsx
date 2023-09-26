import React, { useEffect } from 'react';
import styled from 'styled-components';
import {
  TAP_POWER, ENABLE_BLOWING, ENABLE_TAPPING, ENABLE_SWIPING, IS_TOUCH_DEVICE,
} from '../../Config';
import { powerApi, sensors } from '../../api';
import Turbine from "./Turbine";

const GeneratorDiv = styled.div`
  user-select: none;

  svg {
    cursor: pointer;
    height: 400px;
    user-select: none;
    -moz-user-select: none;
    -webkit-user-select: none;
    -ms-user-select: none;
  }

  .turbine {
    transform-origin: 5020px 1250px;
    transition: all 400ms;
    transform: rotate(${(props) => props.generated * 3}deg);
    user-select: none;
    -moz-user-select: none;
    -webkit-user-select: none;
    -ms-user-select: none;
  }

  .turbine-item {
    fill: ${(props) => props.color};
  }
`;

const GeneratedIndicatorDiv = styled.div`
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 3rem;
  font-weight: bold;
  line-height: 3rem;
  margin-top: 40px;

  &:after {
    content: '${(props) => props.unit}';
    font-size: 1.5rem;
    line-height: 1.5rem;
    font-weight: bold;
  }
`;

function Generator(props) {
  // Volume meter
  if (ENABLE_BLOWING) {
    useEffect(() => {
      const interval = setInterval(() => {
        const volume = sensors.getVolume();
        if (!volume) {
          return;
        }
        if (volume > 20) {
          props.generatePower(volume);
        }
      }, 200);
      return () => clearInterval(interval);
    }, []);
  }

  // Swipe
  if (ENABLE_SWIPING) {
    useEffect(() => {
      sensors.startSwipeSensor((d, diff) => {
        const power = Math.min(100, Math.round(Math.abs(diff) / 5));
        props.generatePower(power);
      });
    }, []);
  }

  // Shake
  if (props.shakingEnabled) {
    useEffect(() => {
      sensors.startShakeSensor((magnitude) => {
        const power = Math.min(magnitude, 100);
        props.generatePower(power);
      });
    }, []);
  }

  const onTap = (e) => {
    // Tapping
    if (ENABLE_TAPPING) {
      props.generatePower(TAP_POWER, true);
    }
  };

  const onClick = (e) => {
    // Clicking
    if (!IS_TOUCH_DEVICE && ENABLE_TAPPING) {
      props.generatePower(TAP_POWER, true);
    }
  };

  return (
    <>
      <GeneratorDiv generated={props.generated} color={props.color}>
        <Turbine onTap={onTap} onClick={onClick} />
      </GeneratorDiv>
      <GeneratedIndicatorDiv
        unit={powerApi.humanPowerUnit(props.generated)}
        id="power-generator"
      >
        {powerApi.humanPowerValue(props.generated)}
      </GeneratedIndicatorDiv>
    </>
  );
}

export default Generator;
