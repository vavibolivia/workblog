import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { BrandMark } from '../components/Icons'

/** 한쪽 면을 칠하는 패널 없이, 연회색 바탕 가운데 카드 하나. */
export default function LoginPage() {
  const { user, login, loading } = useAuth()
  const navigate = useNavigate()
  const loc = useLocation() as { state?: { from?: string } }
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [signupEnabled, setSignupEnabled] = useState(false)

  useEffect(() => {
    api.auth.options().then((o) => setSignupEnabled(o.signupEnabled)).catch(() => setSignupEnabled(false))
  }, [])

  if (!loading && user) return <Navigate to={loc.state?.from ?? '/'} replace />

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true); setErr(null)
    try {
      await login(username.trim(), password)
      navigate(loc.state?.from ?? '/', { replace: true })
    } catch (e) {
      setErr(errorMessage(e, '로그인하지 못했습니다.'))
    } finally { setBusy(false) }
  }

  return (
    <main className="login">
      <div className="login__wrap">
        <div className="brand login__brand">
          <span className="brand__mark" aria-hidden><BrandMark size={32} /></span>
          <span className="brand__name">WorkBlog</span>
          <span className="brand__sub">사내 지식 공유</span>
        </div>
        <form className="login__box" onSubmit={submit}>
          <div>
            <h1>로그인</h1>
            <p>관리자가 발급한 사내 계정을 사용합니다.</p>
          </div>
          <div className="field">
            <label htmlFor="username">아이디</label>
            <input id="username" className="input" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required />
          </div>
          <div className="field">
            <label htmlFor="password">비밀번호</label>
            <input id="password" type="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </div>
          {err && <div className="alert alert--error" role="alert">{err}</div>}
          <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={busy}>{busy ? '확인 중…' : '로그인'}</button>
          {signupEnabled
            ? <p className="small muted">아직 계정이 없나요? <Link to="/signup" className="login__link">회원 가입</Link></p>
            : <p className="small muted">계정이 없다면 시스템 관리자에게 요청하세요.</p>}
        </form>
        <p className="login__tagline">일하며 알게 된 것을 적어 두면, 회사 전체의 지식이 됩니다.</p>
      </div>
    </main>
  )
}
