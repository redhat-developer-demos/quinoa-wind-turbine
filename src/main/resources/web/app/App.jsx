import React from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import Dashboard from './components/Dashboard';
import GameController from './components/GameController';
import { Login, LoginError } from './components/Login';

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<GameController />} />
        <Route path="/dashboard" element={<Dashboard />} />
        <Route path="/login" element={<Login />} />
        <Route path="/login-error" element={<LoginError />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
