import {useEffect, useState} from 'react';
import styled from "styled-components";

const StopWatchContainer = styled.div`
  text-align: center;
  font-size: 2.5rem;
  color: white;
  font-family: var(--code-font);
`

export const StopWatch = (props) => {
    useEffect(() => {
        let interval;
        if (props.running) {
            interval = setInterval(() => {
                props.setTime((prevTime) => prevTime + 80);
            }, 80);
        } else if (!props.running) {
            clearInterval(interval);
        }
        return () => clearInterval(interval);
    }, [props.running, props.setTime]);
    return (
        <StopWatchContainer className="stopwatch">
            <div className="numbers">
                <span>{("0" + Math.floor((props.time / 60000) % 60)).slice(-2)}:</span>
                <span>{("0" + Math.floor((props.time / 1000) % 60)).slice(-2)}:</span>
                <span>{("0" + ((props.time / 10) % 100)).slice(-2)}</span>
            </div>
        </StopWatchContainer>
    );
};