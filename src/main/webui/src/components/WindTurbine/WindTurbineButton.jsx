import React, {Component, useEffect, useState} from 'react'
import styled from 'styled-components'
import classNames from "classnames";

const WindTurbineContainer = styled.div`
  display: grid;
  justify-items: center;
  align-items: center;
  height: 600px;
  
  .turbine {
    display: grid;
    justify-items: center;
    align-items: center;
    grid-template-columns: repeat(1, 1fr); /* one row, one column */
    grid-template-rows: repeat(1, 1fr); /* one row, one column */
    cursor: pointer;
    height: 100%;
    width: 100%;
  }
  
  .pole,.pilot {
    grid-row:1;
    grid-column: 1;
  }

  .pole {
    position: relative;
    display: block;
    align-self: end;
    background-color: gray;
    height: 40%;
    width: 10px;
    border-radius: 5px 5px 0 0;
    z-index: 0;
  }

  .pilot {
    position: relative;
    z-index: 1;
  }


  .pilot:after {
    /* This is the central circle */
    content: '';
    display: block;

    position: absolute;
    top: 93px;
    left: -6px;
    z-index: 1;

    height: 27px;
    width: 27px;
    border: 4px solid white;
    border-radius: 50%;

    /*Usually present in a reset sheet*/
    box-sizing: border-box;
  }

  .pilot:after, .pilot .prop {
    background-color: ${props => props.color };
  }


  .pilot .prop-container {
    display: grid;
    grid-template-columns: repeat(1, 1fr); /* one row, one column */
    align-items: center;
    justify-items: center;
    transform-origin: 7px 107px; /* the origin of the spin should be bottom side, middle of the overall rectangle*/
    animation: propeller 1s infinite linear;
    animation-play-state: paused;
  }

  .turbine.turning .pilot .prop-container {
    animation-play-state: running;
  }

  .pilot .prop {
    height: 100px;
    width: 14px;
    border-radius: 50%;
    grid-column: 1;
    grid-row:1; /*Stack the propellers on top of each other*/
    transform-origin: 50% 50%; /*Transform the propeller about its centre */
  }

  /* The the rotateZ rotates the propeller direction, the following transforms rotate around a point of a circle */
  .prop:first-child {
    transform: rotate(360deg) translate(0px) rotate(-360deg);
  }

  .prop:nth-child(2) {
    transform: rotateZ(120deg) rotate(120deg) translate(-100px) rotate(-120deg);
  }

  .prop:last-child {
    transform: rotateZ(240deg) rotate(240deg) translate(100px) rotate(-240deg);
  }


  @keyframes propeller {
    to {
      transform: rotateZ(360deg);
    }
  }
`

const WindTurbineButton = (props) => {
    const [turning, setTurning] = useState(false);
    const [timer, setTimer] = useState();

    function animate(e) {
        if (props.onClick) {
            props.onClick(e);
        }
        if (timer) {
            clearTimeout(timer);
        }
        setTurning(true);
        setTimer(setTimeout(() => {
            setTurning(false);
        }, 300));
    }

    return (
        <WindTurbineContainer color={props.color}>
            <div className={classNames('turbine', { turning } )} onClick={animate}>
                <div className="pilot">
                    <div className="prop-container">
                        <div className="prop"/>
                        <div className="prop"/>
                        <div className="prop"/>
                    </div>
                </div>
                <div className="pole"/>
            </div>
        </WindTurbineContainer>
    )
}

export default WindTurbineButton;