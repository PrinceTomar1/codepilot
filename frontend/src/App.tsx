import { lazy, Suspense, type ReactElement } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import ProtectedRoute from './components/ProtectedRoute'
import { useAuth } from './context/AuthContext'

// Route-level code splitting: the initial bundle was a single unsplit ~640KB chunk, so every
// visitor downloaded and parsed the whole app (including the architecture-graph renderer and
// markdown pipeline) just to see the login screen. Lazy-loading each page means the first paint
// only needs whichever page is actually being visited.
const LoginPage = lazy(() => import('./pages/LoginPage'))
const RegisterPage = lazy(() => import('./pages/RegisterPage'))
const VerifyEmailPage = lazy(() => import('./pages/VerifyEmailPage'))
const OAuthCallbackPage = lazy(() => import('./pages/OAuthCallbackPage'))
const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const RepositoryDetailPage = lazy(() => import('./pages/RepositoryDetailPage'))
const ForgotPasswordPage = lazy(() => import('./pages/ForgotPasswordPage'))
const ResetPasswordPage = lazy(() => import('./pages/ResetPasswordPage'))
const LoginWithCodePage = lazy(() => import('./pages/LoginWithCodePage'))

function PublicOnlyRoute({ children }: { children: ReactElement }) {
  const { isAuthenticated, isInitializing } = useAuth()
  if (isInitializing) return null
  if (isAuthenticated) return <Navigate to="/dashboard" replace />
  return children
}

function RouteFallback() {
  return (
    <div className="flex min-h-screen items-center justify-center bg-slate-950">
      <div className="h-8 w-8 animate-spin rounded-full border-2 border-slate-700 border-t-brand-500" />
    </div>
  )
}

export default function App() {
  return (
    <Suspense fallback={<RouteFallback />}>
      <Routes>
        <Route path="/" element={<Navigate to="/dashboard" replace />} />

        <Route
          path="/login"
          element={
            <PublicOnlyRoute>
              <LoginPage />
            </PublicOnlyRoute>
          }
        />
        <Route
          path="/register"
          element={
            <PublicOnlyRoute>
              <RegisterPage />
            </PublicOnlyRoute>
          }
        />
        <Route path="/verify-email" element={<VerifyEmailPage />} />
        <Route path="/oauth-callback" element={<OAuthCallbackPage />} />
        <Route
          path="/forgot-password"
          element={
            <PublicOnlyRoute>
              <ForgotPasswordPage />
            </PublicOnlyRoute>
          }
        />
        <Route path="/reset-password" element={<ResetPasswordPage />} />
        <Route
          path="/login-with-code"
          element={
            <PublicOnlyRoute>
              <LoginWithCodePage />
            </PublicOnlyRoute>
          }
        />

        <Route
          path="/dashboard"
          element={
            <ProtectedRoute>
              <DashboardPage />
            </ProtectedRoute>
          }
        />
        <Route
          path="/repositories/:id"
          element={
            <ProtectedRoute>
              <RepositoryDetailPage />
            </ProtectedRoute>
          }
        />

        <Route path="*" element={<Navigate to="/dashboard" replace />} />
      </Routes>
    </Suspense>
  )
}
