import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api, errorMessage, formatDate, type TokenInfo } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import Sidebar from '../components/Sidebar'
import Modal from '../components/Modal'

export default function ProfilePage() {
  const { user, refresh } = useAuth()
  const toast = useToast()
  const [displayName, setDisplayName] = useState(user?.displayName ?? '')
  const [email, setEmail] = useState(user?.email ?? '')
  const [department, setDepartment] = useState(user?.department ?? '')
  const [cur, setCur] = useState(''); const [pw1, setPw1] = useState(''); const [pw2, setPw2] = useState('')
  const [busy, setBusy] = useState(false)

  const saveProfile = async (e: FormEvent) => {
    e.preventDefault(); setBusy(true)
    try { await api.users.updateProfile({ displayName: displayName.trim(), email: email.trim() || undefined, department: department.trim() || undefined }); await refresh(); toast('내 정보를 저장했습니다.', 'success') }
    catch (er) { toast(errorMessage(er), 'error') } finally { setBusy(false) }
  }
  const savePw = async (e: FormEvent) => {
    e.preventDefault()
    if (pw1 !== pw2) { toast('새 비밀번호가 서로 다릅니다.', 'error'); return }
    setBusy(true)
    try { await api.users.changePassword(cur, pw1); setCur(''); setPw1(''); setPw2(''); toast('비밀번호를 변경했습니다.', 'success') }
    catch (er) { toast(errorMessage(er), 'error') } finally { setBusy(false) }
  }

  return (
    <div className="shell">
      <Sidebar />
      <main className="stack">
        <div className="page-head"><h2>내 정보</h2><p>이름과 부서는 글과 이력에 표시됩니다.</p></div>
        <form className="card" onSubmit={saveProfile}>
          <div className="card__head"><h3>프로필</h3></div>
          <div className="card__body stack">
            <div className="form-row">
              <div className="field"><label htmlFor="p-id">아이디</label><input id="p-id" className="input" value={user?.username ?? ''} disabled /></div>
              <div className="field"><label htmlFor="p-name">이름</label><input id="p-name" className="input" value={displayName} onChange={(e) => setDisplayName(e.target.value)} required maxLength={100} /></div>
            </div>
            <div className="form-row">
              <div className="field"><label htmlFor="p-dept">부서</label><input id="p-dept" className="input" value={department} onChange={(e) => setDepartment(e.target.value)} maxLength={100} /></div>
              <div className="field"><label htmlFor="p-email">이메일</label><input id="p-email" type="email" className="input" value={email} onChange={(e) => setEmail(e.target.value)} maxLength={200} /></div>
            </div>
            <div><button type="submit" className="btn btn--primary" disabled={busy}>저장</button></div>
          </div>
        </form>
        <form className="card" onSubmit={savePw}>
          <div className="card__head"><h3>비밀번호 변경</h3></div>
          <div className="card__body stack">
            <div className="field"><label htmlFor="pw-cur">현재 비밀번호</label><input id="pw-cur" type="password" className="input" value={cur} onChange={(e) => setCur(e.target.value)} required autoComplete="current-password" /></div>
            <div className="form-row">
              <div className="field"><label htmlFor="pw-1">새 비밀번호</label><input id="pw-1" type="password" className="input" value={pw1} onChange={(e) => setPw1(e.target.value)} required minLength={4} autoComplete="new-password" /></div>
              <div className="field"><label htmlFor="pw-2">새 비밀번호 확인</label><input id="pw-2" type="password" className="input" value={pw2} onChange={(e) => setPw2(e.target.value)} required minLength={4} autoComplete="new-password" /></div>
            </div>
            <div><button type="submit" className="btn" disabled={busy}>비밀번호 변경</button></div>
          </div>
        </form>
        <TokenCard />
      </main>
    </div>
  )
}

type Expiry = '' | '30' | '90' | '365'

function tokenStatus(t: TokenInfo): { label: string; cls: string } {
  if (t.revokedAt) return { label: '폐기됨', cls: 'chip--neutral' }
  if (t.expiresAt && new Date(t.expiresAt).getTime() < Date.now()) return { label: '만료', cls: 'chip--danger' }
  if (!t.active) return { label: '사용 불가', cls: 'chip--neutral' }
  return { label: '사용 중', cls: 'chip--success' }
}

/** 외부 프로그램(Claude Code 등)용 개인 API 토큰 발급·폐기. 토큰 원문은 발급 직후 한 번만 보인다. */
function TokenCard() {
  const toast = useToast()
  const [tokens, setTokens] = useState<TokenInfo[] | null>(null)
  const [name, setName] = useState('')
  const [expiry, setExpiry] = useState<Expiry>('')
  const [busy, setBusy] = useState(false)
  const [issued, setIssued] = useState<{ token: string; info: TokenInfo } | null>(null)
  const [copied, setCopied] = useState(false)
  const [revoking, setRevoking] = useState<TokenInfo | null>(null)

  const load = () => api.tokens.list().then(setTokens).catch((e) => toast(errorMessage(e, '토큰 목록을 불러오지 못했습니다.'), 'error'))
  useEffect(() => { load() }, [])

  const create = async (e: FormEvent) => {
    e.preventDefault()
    const n = name.trim()
    if (!n) return
    setBusy(true)
    try {
      const res = await api.tokens.create(n, expiry ? Number(expiry) : undefined)
      setIssued(res); setCopied(false); setName(''); setExpiry('')
      load()
    } catch (er) { toast(errorMessage(er, '토큰을 발급하지 못했습니다.'), 'error') } finally { setBusy(false) }
  }

  const copy = async () => {
    if (!issued) return
    try { await navigator.clipboard.writeText(issued.token); setCopied(true); toast('토큰을 복사했습니다.', 'success') }
    catch { toast('복사하지 못했습니다. 토큰을 직접 선택해 복사하세요.', 'error') }
  }

  const revoke = async () => {
    if (!revoking) return
    setBusy(true)
    try { await api.tokens.revoke(revoking.id); toast(`'${revoking.name}' 토큰을 폐기했습니다.`, 'success'); load() }
    catch (er) { toast(errorMessage(er, '토큰을 폐기하지 못했습니다.'), 'error') } finally { setBusy(false); setRevoking(null) }
  }

  return (
    <section className="card" aria-labelledby="token-title">
      <div className="card__head">
        <h3 id="token-title">API 토큰 (외부 프로그램·Claude Code 연동)</h3>
        <Link to="/help/claude-code" className="small" style={{ color: 'var(--sky)', fontWeight: 600 }}>사용법 보기</Link>
      </div>
      <div className="card__body stack">
        <p className="small muted">웹 로그인 대신 외부 프로그램이 내 이름으로 글을 읽고 쓸 때 쓰는 개인 토큰입니다. 토큰 원문은 발급 직후 한 번만 보입니다.</p>

        <form className="token-form" onSubmit={create}>
          <div className="field">
            <label htmlFor="tk-name">이름</label>
            <input id="tk-name" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} placeholder='예) 내 노트북 Claude Code' required />
          </div>
          <div className="field">
            <label htmlFor="tk-exp">만료</label>
            <select id="tk-exp" className="select" value={expiry} onChange={(e) => setExpiry(e.target.value as Expiry)}>
              <option value="">없음</option>
              <option value="30">30일</option>
              <option value="90">90일</option>
              <option value="365">365일</option>
            </select>
          </div>
          <button type="submit" className="btn btn--primary" disabled={busy || !name.trim()}>{busy ? '발급 중…' : '토큰 발급'}</button>
        </form>

        {issued && (
          <div className="token-reveal" role="status">
            <div className="row row--between row--wrap">
              <strong className="small">'{issued.info.name}' 토큰이 발급되었습니다.</strong>
              <button type="button" className="btn btn--ghost btn--sm" onClick={() => setIssued(null)} aria-label="닫기">✕</button>
            </div>
            <code>{issued.token}</code>
            <div className="row row--wrap">
              <button type="button" className="btn btn--sm btn--sky" onClick={copy}>{copied ? '복사됨' : '복사'}</button>
              <span className="small" style={{ color: 'var(--danger)', fontWeight: 600 }}>이 창을 닫으면 다시 볼 수 없습니다. 지금 안전한 곳에 저장하세요.</span>
            </div>
          </div>
        )}

        {tokens && tokens.length > 0 && (
          <div className="table-wrap">
            <table className="table">
              <thead><tr><th>이름</th><th>앞부분</th><th>만든 날</th><th>만료</th><th>마지막 사용</th><th>상태</th><th></th></tr></thead>
              <tbody>
                {tokens.map((t) => {
                  const st = tokenStatus(t)
                  return (
                    <tr key={t.id} style={{ opacity: t.revokedAt ? .55 : 1 }}>
                      <td style={{ fontWeight: 600 }}>{t.name}</td>
                      <td><code>{t.prefix}…</code></td>
                      <td className="small muted">{formatDate(t.createdAt)}</td>
                      <td className="small muted">{t.expiresAt ? formatDate(t.expiresAt) : '없음'}</td>
                      <td className="small muted">{t.lastUsedAt ? formatDate(t.lastUsedAt, true) : '–'}</td>
                      <td><span className={`chip ${st.cls}`}>{st.label}</span></td>
                      <td>{!t.revokedAt && <button type="button" className="btn btn--sm btn--danger" onClick={() => setRevoking(t)}>폐기</button>}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        )}
        {tokens && tokens.length === 0 && <p className="small muted">발급한 토큰이 없습니다.</p>}
      </div>

      {revoking && (
        <Modal title="토큰을 폐기할까요?" onClose={() => setRevoking(null)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setRevoking(null)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={revoke}>폐기</button>
               </>}>
          <p><strong>{revoking.name}</strong> ({revoking.prefix}…) 토큰을 쓰는 프로그램은 바로 접근할 수 없게 됩니다. 되돌릴 수 없습니다.</p>
        </Modal>
      )}
    </section>
  )
}
