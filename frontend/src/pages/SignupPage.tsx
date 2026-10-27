import { useEffect, useState, type FormEvent } from 'react'
import { BrandMark } from '../components/Icons'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import axios from 'axios'
import { api, errorMessage, type AuthOptions, type SignupResponse } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'

const USERNAME_RE = /^[a-zA-Z0-9._-]{3,50}$/

/** 아이디 규칙 실시간 안내. 비어 있으면 중립 문구. */
function usernameHint(v: string): { text: string; state: 'ok' | 'bad' | null } {
  if (!v) return { text: '3~50자, 영문·숫자와 . _ - 만 사용', state: null }
  if (v.length < 3) return { text: '3자 이상이어야 합니다.', state: 'bad' }
  if (v.length > 50) return { text: '50자를 넘을 수 없습니다.', state: 'bad' }
  if (!USERNAME_RE.test(v)) return { text: '영문, 숫자, . _ - 만 쓸 수 있습니다.', state: 'bad' }
  return { text: '사용할 수 있는 형식입니다.', state: 'ok' }
}

/** 회원 가입(R13): 로그인과 같은 레이아웃(연회색 바탕 가운데 카드 하나). 아이디는 각자 정한다. */
export default function SignupPage() {
  const { user, loading, loginWithToken } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [options, setOptions] = useState<AuthOptions | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [password2, setPassword2] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [department, setDepartment] = useState('')
  const [email, setEmail] = useState('')
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [done, setDone] = useState<SignupResponse | null>(null)

  useEffect(() => {
    api.auth.options().then(setOptions).catch(() => setOptions({ signupEnabled: false, signupRequiresApproval: false }))
  }, [])

  if (!loading && user) return <Navigate to="/" replace />

  const hint = usernameHint(username)
  const pwMismatch = password2.length > 0 && password !== password2
  const canSubmit = hint.state === 'ok' && password.length >= 4 && !pwMismatch && password2.length > 0 && displayName.trim().length > 0 && !busy

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    if (!canSubmit) return
    setBusy(true); setErr(null)
    try {
      const res = await api.auth.signup({
        username: username.trim(), password, displayName: displayName.trim(),
        email: email.trim() || undefined, department: department.trim() || undefined,
      })
      if (res.approved && res.token) {
        loginWithToken(res.token, res.user)
        toast(res.message || '가입했습니다.', 'success')
        navigate('/', { replace: true })
      } else {
        setDone(res)
      }
    } catch (e) {
      if (axios.isAxiosError(e) && e.response?.status === 409) setErr(errorMessage(e, '이미 사용 중인 아이디입니다.'))
      else setErr(errorMessage(e, '가입하지 못했습니다.'))
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

        {done ? (
          <div className="login__box">
            <div>
              <h1>가입 신청 완료</h1>
              <p>{done.message}</p>
            </div>
            <p className="small muted">아이디 <strong>{done.user.username}</strong> · 관리자 승인 뒤 로그인할 수 있습니다.</p>
            <Link to="/login" className="btn btn--primary btn--lg btn--block">로그인 화면으로</Link>
          </div>
        ) : options && !options.signupEnabled ? (
          <div className="login__box">
            <div>
              <h1>회원 가입</h1>
              <p>지금은 회원 가입이 닫혀 있습니다. 시스템 관리자에게 계정을 요청하세요.</p>
            </div>
            <Link to="/login" className="btn btn--lg btn--block">로그인 화면으로</Link>
          </div>
        ) : (
          <form className="login__box" onSubmit={submit}>
            <div>
              <h1>회원 가입</h1>
              <p>{options?.signupRequiresApproval ? '가입 신청 뒤 관리자가 승인하면 사용할 수 있습니다.' : '아이디를 직접 정해 바로 사용할 수 있습니다.'}</p>
            </div>
            <div className="field">
              <label htmlFor="s-username">아이디</label>
              <input id="s-username" className="input" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus required maxLength={50} aria-describedby="s-username-hint" aria-invalid={hint.state === 'bad'} />
              <span id="s-username-hint" className={`field__hint${hint.state ? ` field__hint--${hint.state}` : ''}`}>{hint.text}</span>
            </div>
            <div className="field">
              <label htmlFor="s-password">비밀번호</label>
              <input id="s-password" type="password" className="input" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required minLength={4} />
              <span className="field__hint">4자 이상</span>
            </div>
            <div className="field">
              <label htmlFor="s-password2">비밀번호 확인</label>
              <input id="s-password2" type="password" className="input" value={password2} onChange={(e) => setPassword2(e.target.value)} autoComplete="new-password" required minLength={4} aria-invalid={pwMismatch} />
              {pwMismatch && <span className="field__hint field__hint--bad">비밀번호가 서로 다릅니다.</span>}
            </div>
            <div className="field">
              <label htmlFor="s-name">이름</label>
              <input id="s-name" className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} autoComplete="name" required maxLength={100} placeholder="글과 댓글에 표시됩니다" />
            </div>
            <div className="field">
              <label htmlFor="s-dept">부서 <span className="muted" style={{ fontWeight: 400 }}>(선택)</span></label>
              <input id="s-dept" className="input" value={department} onChange={(e) => setDepartment(e.target.value)} autoComplete="organization" maxLength={100} placeholder="예) IT전략실" />
            </div>
            <div className="field">
              <label htmlFor="s-email">이메일 <span className="muted" style={{ fontWeight: 400 }}>(선택)</span></label>
              <input id="s-email" type="email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="email" maxLength={200} placeholder="name@example.com" />
            </div>
            {err && <div className="alert alert--error" role="alert">{err}</div>}
            <button type="submit" className="btn btn--primary btn--lg btn--block" disabled={!canSubmit}>{busy ? '가입 중…' : '가입하기'}</button>
            <p className="small muted">이미 계정이 있나요? <Link to="/login" className="login__link">로그인</Link></p>
          </form>
        )}
        <p className="login__tagline">일하며 알게 된 것을 적어 두면, 회사 전체의 지식이 됩니다.</p>
      </div>
    </main>
  )
}
