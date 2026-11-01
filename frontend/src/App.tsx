import { Route, Routes } from 'react-router'
import PostListPage from './pages/PostListPage'
import PostCreatePage from './pages/PostCreatePage'
import PostDetailPage from './pages/PostDetailPage'

// 주소별로 어떤 페이지를 보여줄지 정하는 곳
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<PostListPage />} />
      {/* "/posts/new"와 "/posts/:id"가 겹쳐 보이지만, React Router는 더 구체적인 경로를 우선한다 */}
      <Route path="/posts/new" element={<PostCreatePage />} />
      <Route path="/posts/:id" element={<PostDetailPage />} />
      {/* 위 어디에도 안 맞는 주소 */}
      <Route path="*" element={<p>페이지를 찾을 수 없습니다</p>} />
    </Routes>
  )
}
