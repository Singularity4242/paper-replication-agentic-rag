import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import 'antd/dist/reset.css';
import './styles/global.css';
import { App } from './app/App';

const root = document.getElementById('root');
if (!root) throw new Error('Application root not found');
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
