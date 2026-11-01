import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App.tsx'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* BrowserRouter: 주소(URL)에 따라 화면을 바꿔 주는 라우터. 앱 전체를 감싸야 Link·Routes가 동작한다 */}
    <BrowserRouter>
      <App />
    </BrowserRouter>
  </StrictMode>,
)
