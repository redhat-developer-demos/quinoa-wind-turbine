import React from 'react';
import styled from 'styled-components'
import RaceTrack from "./components/RaceTrack";
import {BrowserRouter, NavLink, Route, Routes} from "react-router-dom";
import WindTurbine from './components/WindTurbine';

const Title = styled.h1`
  text-align: center;
  font-weight: 700;
  margin: 30px 0 0 0;
  font-size: 4rem;
  color: white;
`

const Menu = styled.ul`
  list-style-type: none;
  text-align: center;
  a {
    text-decoration: none;
    color: #DB3753;
    font-size: 2rem;
    font-weight: bold;
    
    &:hover {
      color: white;
    }
  }
`

const MainPage = () => (
    <div>
        <Title>Quinoa Racer</Title>
        <Menu>
            <li><NavLink to="/team1">Team 1</NavLink></li>
            <li><NavLink to="/team2">Team 2</NavLink></li>
        </Menu>
    </div>
);

const Team1 = () => (
    <div>
        <Title>Team 1</Title>
        <WindTurbine team={1} color="#ba2f34"  />
    </div>
);

const Team2 = () => (
    <div>
        <Title>Team 2</Title>
        <WindTurbine team={2} color="#b87832" />
    </div>
);

const RacePage = () => (
    <div>
        <Title>Race</Title>
        <RaceTrack />
    </div>
);

const App = () =>  {
    return(
        <BrowserRouter>
            <Routes>
                <Route path="/" element={<MainPage />} />
                <Route path="/race" element={<RacePage />} />
                <Route path="/team1" element={<Team1 />} />
                <Route path="/team2" element={<Team2 />} />
            </Routes>
        </BrowserRouter>
    );
}

export default App;


