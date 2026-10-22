import React from 'react';
import ReactDOM from 'react-dom/client';
import './index.css';
import { loadConfig } from './Config';

const root = ReactDOM.createRoot(document.getElementById('root'));
loadConfig().then(() => import('./App')).then(({ default: App }) => root.render(<App />)).catch((error) => {
  console.error(error);
  root.render(<p role="alert">Could not load the race configuration. Please reload to try again.</p>);
});

