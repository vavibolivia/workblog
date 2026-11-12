import { useEffect, useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { BrandMark } from '../components/Icons'
import BrainArt from '../components/BrainArt'

/* 소개 줄에 쓰는 선 아이콘(공용 Icons 와 같은 규격: 1.8px, currentColor) */
const ic = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const
const IcWrite = () => <svg {...ic}><path d="M4 20h4l10.5-10.5a2.1 2.1 0 0 0-3-3L5 17z" /><path d="m13.5 8 3 3" /></svg>
const IcFind = () => <svg {...ic}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.3-4.3" /></svg>
const IcKeep = () => <svg {...ic}><path d="M4 12a8 8 0 1 0 2.3-5.7" /><path d="M4 4v4.5h4.5" /><path d="M12 8v4l2.8 1.8" /></svg>

/**
 * 로그인 + 프로그램 소개. 한쪽 면을 칠하는 패널 없이 연회색 바탕 하나 위에
 * 왼쪽은 그림(점과 선으로 이어진 두뇌)과 제목, 오른쪽은 로그인 상자. 색은 회색 + 남색 하나.
 * 좁은 화면에서는 로그인 상자가 먼저.
 */
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
    <main className="login login--intro">
      <div className="intro">
        <div className="brand login__brand intro__brand">
          <span className="brand__mark" aria-hidden><BrandMark size={32} /></span>
          <span className="brand__text">
            <span className="brand__name">WorkBlog</span>
            <span className="brand__ver">v{__APP_VERSION__}</span>
          </span>
          <span className="brand__sub">사내 지식 공유</span>
        </div>

        <section className="intro__story" aria-label="WorkBlog 소개">
          <h1 className="intro__title">흩어진 업무 지식이 이어져<br />회사의 두뇌가 됩니다.</h1>

          <BrainArt className="brain" />

          <dl className="intro__points">
            <div><dt><IcWrite /></dt><dd><b>쓰기</b>네이버 블로그처럼<br />엑셀·사진·다이어그램</dd></div>
            <div><dt><IcFind /></dt><dd><b>찾기</b>한 검색창에서<br />첨부 안 글자까지</dd></div>
            <div><dt><IcKeep /></dt><dd><b>남기기</b>저장마다 판이 쌓이고<br />낱말 비교 · 되돌리기</dd></div>
          </dl>
        </section>

        <form className="login__box intro__box" onSubmit={submit}>
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

        <p className="intro__foot">사내 전용 · 글에 적힌 서버·계정 정보는 밖으로 내보내지 마세요.<br />만든 사람 · 정석호 Sukho Jung · <a href="mailto:vavibolivia@gmail.com" className="login__link">vavibolivia@gmail.com</a></p>
      </div>
    </main>
  )
}
