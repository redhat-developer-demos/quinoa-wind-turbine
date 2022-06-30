import React from 'react'
import styled from 'styled-components'
import WindTurbineButton from './WindTurbineButton';

const Container = styled.div`
  text-align: center;
`

const WindTurbine = (props) => {
    function generatePower() {
        let fetchOptions = {
            method: "POST",
            body: JSON.stringify({quantity: 1}),
            headers: { 'Content-Type': 'application/json' },
        };
        fetch(`/api/power/${props.team}`,
            {...fetchOptions})
            .catch(e => console.error(e));
    }

    return (
        <Container>
            <WindTurbineButton onClick={generatePower} color={props.color} />
        </Container>
    )
}

export default WindTurbine;