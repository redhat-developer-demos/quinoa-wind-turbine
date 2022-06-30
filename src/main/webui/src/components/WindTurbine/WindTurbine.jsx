import React, {useState} from 'react'
import styled from 'styled-components'
import WindTurbineButton from './WindTurbineButton';

const Container = styled.div`
  text-align: center;
  color: white;
  font-size: 2rem;
`

const Input = styled.input`
  font-family: "Indie Flower", "Comic Sans MS", sans-serif;
  text-align: center;
  font-size: 2rem;
  border-radius: 5px;
  border: none;
`

const WindTurbine = (props) => {
    const [nickname, setNickname] = useState("");
    const [counter, setCounter] = useState(0);

    function generatePower() {
        setCounter((c) => ++c);
        let fetchOptions = {
            method: "POST",
            body: JSON.stringify({quantity: 1, nickname}),
            headers: { 'Content-Type': 'application/json' },
        };
        fetch(`/api/power/${props.team}`,
            {...fetchOptions})
            .catch(e => console.error(e));
    }

    return (
        <Container>
            <Input type="text" placeholder="Nickname?" value={nickname} onChange={(e) => setNickname(e.target.value)} />
            <p>{counter}</p>
            <WindTurbineButton onClick={generatePower} color={props.color} />
        </Container>
    )
}

export default WindTurbine;