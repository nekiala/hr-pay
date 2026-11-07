import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './auth'
import App from './App'
import { DialogsProvider } from './components/Dialogs'
import '@fontsource-variable/inter'
import '@fontsource/jetbrains-mono/500.css'
import '@fontsource/jetbrains-mono/600.css'
import './styles.css'
import { applyTheme } from './components/Shell'
import { registerServiceWorker } from './pwa'

applyTheme()
registerServiceWorker()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <DialogsProvider>
          <App />
        </DialogsProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
