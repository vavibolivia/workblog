import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
// Fonts are bundled with the app (no CDN) so closed networks render the same.
import 'pretendard/dist/web/variable/pretendardvariable-dynamic-subset.css'
import '@fontsource/nanum-gothic/400.css'
import '@fontsource/nanum-gothic/700.css'
import '@fontsource/nanum-myeongjo/400.css'
import '@fontsource/nanum-myeongjo/700.css'
import './styles/global.css'
import App from './App'
import { AuthProvider } from './lib/auth'
import { ToastProvider } from './lib/toast'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <ToastProvider>
        <AuthProvider>
          <App />
        </AuthProvider>
      </ToastProvider>
    </BrowserRouter>
  </StrictMode>,
)
