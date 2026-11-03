import React from 'react';
import { BrowserRouter, Route, Routes } from 'react-router-dom';
import Dashboard from './components/Dashboard';
import GameController from './components/GameController';
import { Login, LoginError } from './components/Login';
import { ThemeProvider } from './ThemeContext';
import { InputModeProvider } from './InputModeContext';

function App() {
  return (
    <ThemeProvider>
      <InputModeProvider>
        <BrowserRouter>
          <Routes>
            <Route path="/" element={<GameController />} />
            <Route path="/dashboard" element={<Dashboard />} />
            <Route path="/login" element={<Login />} />
            <Route path="/login-error" element={<LoginError />} />
          </Routes>
        </BrowserRouter>
      </InputModeProvider>
    </ThemeProvider>
  );
}

export default App;
