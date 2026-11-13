import { Link, Route, Routes } from 'react-router'
import { useAuth } from './auth/useAuth'
import Loading from './components/Loading'
import NavBar from './components/NavBar'
import RequireLogin from './components/RequireLogin'
import VisaPlanBanner from './components/VisaPlanBanner'
import LoginPage from './pages/LoginPage'
import PostListPage from './pages/PostListPage'
import PostCreatePage from './pages/PostCreatePage'
import PostDetailPage from './pages/PostDetailPage'
import PostEditPage from './pages/PostEditPage'
import MyPage from './pages/MyPage'
import SignupPage from './pages/SignupPage'
import AdminReportsPage from './pages/AdminReportsPage'
import AdminConversationPage from './pages/AdminConversationPage'
import VisaReviewListPage from './pages/VisaReviewListPage'
import VisaReviewStatsPage from './pages/VisaReviewStatsPage'
import VisaReviewDetailPage from'./pages/VisaReviewDetailPage'
import VisaReviewCreatePage from './pages/VisaReviewCreatePage'
import VisaReviewEditPage from './pages/VisaReviewEditPage'
import VisaPlanPage from './pages/VisaPlanPage'
import MarketListPage from './pages/MarketListPage'
import MarketDetailPage from './pages/MarketDetailPage'
import MarketCreatePage from './pages/MarketCreatePage'
import MarketEditPage from './pages/MarketEditPage'
import MessageListPage from './pages/MessageListPage'
import ConversationPage from './pages/ConversationPage'

// 주소별로 어떤 페이지를 보여줄지 정하는 곳
export default function App() {
  const { loading } = useAuth()
  // 시작 시 CSRF 쿠키 확보 + 로그인 확인이 끝나기 전에는 화면을 그리지 않는다 (첫 쓰기 요청보다 쿠키가 먼저여야 하므로)
  if (loading) return <main className="page"><Loading /></main>

  return (
    <>
      <NavBar />
      <VisaPlanBanner />
      {/* page: 본문 틀(최대 720px 가운데) */}
      <main className="page">
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
          {/* 내 정보: 로그인한 사람만 */}
          <Route
            path="/me"
            element={
              <RequireLogin>
                <MyPage />
              </RequireLogin>
            }
          />
          {/* 신고 관리: 관리자 여부는 페이지 안에서 확인한다 */}
          <Route path="/admin/reports" element={<AdminReportsPage />} />
          {/* 신고된 대화 읽기(읽기 전용): :id는 신고 id. 관리자 여부는 페이지 안에서 확인한다 */}
          <Route path="/admin/reports/:id/conversation" element={<AdminConversationPage />} />
          {/* 비자 후기(읽기 전용, 로그인 없이 볼 수 있다) */}
          <Route path="/visa-reviews" element={<VisaReviewListPage />} />
          {/* 작성·수정은 로그인한 사람만. "/visa-reviews/new"는 ":id"보다 구체적이라 먼저 잡힌다 */}
          <Route
            path="/visa-reviews/new"
            element={
              <RequireLogin>
                <VisaReviewCreatePage />
              </RequireLogin>
            }
          />
          {/* 주의: "/visa-reviews/stats"는 반드시 아래 ":id"보다 먼저 선언한다 (안 그러면 "stats"가 id로 읽힌다) */}
          <Route path="/visa-reviews/stats" element={<VisaReviewStatsPage />} />
          <Route path="/visa-reviews/:id" element={<VisaReviewDetailPage />} />
          <Route
            path="/visa-reviews/:id/edit"
            element={
              <RequireLogin>
                <VisaReviewEditPage />
              </RequireLogin>
            }
          />
          {/* 내 비자 일정: 로그인한 사람만 (학교 인증 여부는 페이지 안에서 안내) */}
          <Route
            path="/visa-plan"
            element={
              <RequireLogin>
                <VisaPlanPage />
              </RequireLogin>
            }
          />
          {/* 중고거래·나눔(보기는 로그인 없이 가능) */}
          <Route path="/market" element={<MarketListPage />} />
          {/* 글쓰기·수정은 로그인한 사람만. "/market/new"는 ":id"보다 구체적이라 먼저 잡힌다 */}
          <Route
            path="/market/new"
            element={
              <RequireLogin>
                <MarketCreatePage />
              </RequireLogin>
            }
          />
          <Route path="/market/:id" element={<MarketDetailPage />} />
          <Route
            path="/market/:id/edit"
            element={
              <RequireLogin>
                <MarketEditPage />
              </RequireLogin>
            }
          />
          {/* 쪽지함·대화 화면: 로그인한 사람만 */}
          <Route
            path="/messages"
            element={
              <RequireLogin>
                <MessageListPage />
              </RequireLogin>
            }
          />
          <Route
            path="/messages/:id"
            element={
              <RequireLogin>
                <ConversationPage />
              </RequireLogin>
            }
          />
          {/* 위 어디에도 안 맞는 주소 */}
          <Route
            path="*"
            element={
              <div className="panel">
                <h1>페이지를 찾을 수 없습니다</h1>
                <Link to="/">홈으로</Link>
              </div>
            }
          />
        </Routes>
      </main>
    </>
  )
}
