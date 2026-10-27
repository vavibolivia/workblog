import { Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './lib/auth'
import Layout from './components/Layout'
import { lazy, Suspense } from 'react'

// 화면별 코드 분할: 첫 로딩은 로그인/홈만 받는다.
const LoginPage = lazy(() => import('./pages/LoginPage'))
const HomePage = lazy(() => import('./pages/HomePage'))
const PostDetailPage = lazy(() => import('./pages/PostDetailPage'))
const EditorPage = lazy(() => import('./pages/EditorPage'))
const PostHistoryPage = lazy(() => import('./pages/PostHistoryPage'))
const SearchPage = lazy(() => import('./pages/SearchPage'))
const HistoryPage = lazy(() => import('./pages/HistoryPage'))
const AdminUsersPage = lazy(() => import('./pages/AdminUsersPage'))
const ProfilePage = lazy(() => import('./pages/ProfilePage'))
const TrashPage = lazy(() => import('./pages/TrashPage'))
const AdminCategoriesPage = lazy(() => import('./pages/AdminCategoriesPage'))
const SignupPage = lazy(() => import('./pages/SignupPage'))
const HelpPage = lazy(() => import('./pages/HelpPage'))

const Loading = () => <div className="screen-center" style={{ minHeight: '50vh' }}><div className="spinner" aria-label="불러오는 중" /></div>

function RequireAuth({ children, admin = false }: { children: React.ReactElement; admin?: boolean }) {
  const { user, loading } = useAuth()
  const loc = useLocation()
  if (loading) return <div className="screen-center"><div className="spinner" aria-label="불러오는 중" /></div>
  if (!user) return <Navigate to="/login" replace state={{ from: loc.pathname + loc.search }} />
  if (admin && user.role !== 'ADMIN') return <Navigate to="/" replace />
  return children
}

export default function App() {
  return (
    <Suspense fallback={<Loading />}>
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/signup" element={<SignupPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<HomePage />} />
        <Route path="posts" element={<HomePage />} />
        <Route path="posts/:id" element={<PostDetailPage />} />
        <Route path="posts/:id/edit" element={<EditorPage />} />
        <Route path="posts/:id/history" element={<PostHistoryPage />} />
        <Route path="write" element={<EditorPage />} />
        <Route path="search" element={<SearchPage />} />
        <Route path="history" element={<HistoryPage />} />
        <Route path="me" element={<ProfilePage />} />
        <Route path="trash" element={<TrashPage />} />
        <Route path="help" element={<HelpPage />} />
        <Route path="help/:doc" element={<HelpPage />} />
        <Route path="admin/users" element={<RequireAuth admin><AdminUsersPage /></RequireAuth>} />
        <Route path="admin/categories" element={<RequireAuth admin><AdminCategoriesPage /></RequireAuth>} />
      </Route>
      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
    </Suspense>
  )
}
