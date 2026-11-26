import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { AuthApp } from './features/auth/AuthApp'
import './styles.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {window.location.pathname === '/status' ? <App /> : <AuthApp />}
  </StrictMode>,
)
