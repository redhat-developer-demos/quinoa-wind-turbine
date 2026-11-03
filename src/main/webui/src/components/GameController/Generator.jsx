import React, { useEffect, useRef } from 'react';
import styled from 'styled-components';
import {
  TAP_POWER, ENABLE_BLOWING, ENABLE_SWIPING, IS_TOUCH_DEVICE,
} from '../../Config';
import { powerApi, sensors } from '../../api';
import { useInputMode } from '../../InputModeContext';
import { effectiveInputs } from '../../inputMode';
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
  const inputMode = useInputMode();
  const inputs = effectiveInputs(inputMode);
  const generatePowerRef = useRef(props.generatePower);
  generatePowerRef.current = props.generatePower;

  // Volume meter
  useEffect(() => {
    if (!ENABLE_BLOWING) {
      return undefined;
    }
    const interval = setInterval(() => {
      const volume = sensors.getVolume();
      if (!volume) {
        return;
      }
      if (volume > 20) {
        generatePowerRef.current(volume, 'blow');
      }
    }, 200);
    return () => clearInterval(interval);
  }, []);

  // Swipe
  useEffect(() => {
    if (!ENABLE_SWIPING) {
      return undefined;
    }
    sensors.startSwipeSensor((d, diff) => {
      const power = Math.min(100, Math.round(Math.abs(diff) / 5));
      generatePowerRef.current(power, 'swipe');
    });
  }, []);

  // Shake
  useEffect(() => {
    if (!inputs.shake || !props.shakingEnabled) {
      return undefined;
    }
    const stopShake = sensors.startShakeSensor((magnitude) => {
      const power = Math.min(magnitude, 100);
      generatePowerRef.current(power, 'shake');
    });
    return () => {
      if (stopShake) {
        stopShake();
      }
    };
  }, [inputs.shake, props.shakingEnabled]);

  const onTap = (e) => {
    // Tapping
    if (inputs.tap) {
      props.generatePower(TAP_POWER, 'tap');
    }
  };

  const onClick = (e) => {
    // Clicking
    if (!IS_TOUCH_DEVICE && inputs.tap) {
      props.generatePower(TAP_POWER, 'tap');
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
