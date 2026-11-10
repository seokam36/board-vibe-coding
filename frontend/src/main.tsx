import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import './index.css'
import App from './App.tsx'
import { AuthProvider } from './auth/AuthContext'
import { ConfirmProvider } from './components/ConfirmDialog'
import { VisaPlanProvider } from './visa/VisaPlanProvider'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* BrowserRouter: 주소(URL)에 따라 화면을 바꿔 주는 라우터. 앱 전체를 감싸야 Link·Routes가 동작한다 */}
    <BrowserRouter>
      {/* AuthProvider: 앱 어디서든 로그인 정보를 쓸 수 있게 감싼다 (라우터 안쪽) */}
      <AuthProvider>
        {/* ConfirmProvider: 삭제 같은 동작 전에 뜨는 확인 창을 앱 맨 위에서 한 번만 그린다 */}
        <ConfirmProvider>
          {/* VisaPlanProvider: 내 비자 일정을 앱 전체에서 한 번만 불러와 상단 띠와 일정 화면이 같이 쓴다 (로그인 정보가 필요해 AuthProvider 안쪽) */}
          <VisaPlanProvider>
            <App />
          </VisaPlanProvider>
        </ConfirmProvider>
      </AuthProvider>
    </BrowserRouter>
  </StrictMode>,
)
