import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import reportWebVitals from './reportWebVitals';
import { loadConfig } from './Config';

const root = ReactDOM.createRoot(document.getElementById('root'));
loadConfig().then(() => import('./App')).then(({ default: App }) => root.render(<App />)).catch((error) => {
  console.error(error);
  root.render(<p role="alert">Could not load the race configuration. Please reload to try again.</p>);
});

// If you want to start measuring performance in your app, pass a function
// to log results (for example: reportWebVitals(console.log))
// or send to an analytics endpoint. Learn more: https://bit.ly/CRA-vitals
reportWebVitals();
