import { Route, Routes } from 'react-router'
import { useAuth } from './auth/useAuth'
import NavBar from './components/NavBar'
import RequireLogin from './components/RequireLogin'
import LoginPage from './pages/LoginPage'
import PostListPage from './pages/PostListPage'
import PostCreatePage from './pages/PostCreatePage'
import PostDetailPage from './pages/PostDetailPage'
import PostEditPage from './pages/PostEditPage'
import SignupPage from './pages/SignupPage'

// 주소별로 어떤 페이지를 보여줄지 정하는 곳
export default function App() {
  const { loading } = useAuth()
  // 시작 시 CSRF 쿠키 확보 + 로그인 확인이 끝나기 전에는 화면을 그리지 않는다 (첫 쓰기 요청보다 쿠키가 먼저여야 하므로)
  if (loading) return <p>불러오는 중...</p>

  return (
    <>
      <NavBar />
      <Routes>
        <Route path="/" element={<PostListPage />} />
        <Route path="/signup" element={<SignupPage />} />
        <Route path="/login" element={<LoginPage />} />
        {/* "/posts/new"와 "/posts/:id"가 겹쳐 보이지만, React Router는 더 구체적인 경로를 우선한다 */}
        {/* 글쓰기·수정 화면은 로그인한 사람만: 비로그인이면 RequireLogin이 /login 으로 보낸다 (단, 로그인이 풀린 그 화면은 예외) */}
        <Route
          path="/posts/new"
          element={
            <RequireLogin>
              <PostCreatePage />
            </RequireLogin>
          }
        />
        <Route path="/posts/:id" element={<PostDetailPage />} />
        <Route
          path="/posts/:id/edit"
          element={
            <RequireLogin>
              <PostEditPage />
            </RequireLogin>
          }
        />
        {/* 위 어디에도 안 맞는 주소 */}
        <Route path="*" element={<p>페이지를 찾을 수 없습니다</p>} />
      </Routes>
    </>
  )
}
