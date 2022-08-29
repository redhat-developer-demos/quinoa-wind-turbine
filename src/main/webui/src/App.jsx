import React from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import Dashboard from "./components/Dashboard";
import GameController from "./components/GameController";

const App = () =>  {
    return(
        <BrowserRouter>
            <Routes>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/" element={<GameController />} />
            </Routes>
        </BrowserRouter>
    );
}

export default App;


