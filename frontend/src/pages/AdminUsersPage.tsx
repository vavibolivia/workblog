import { useEffect, useState, type FormEvent } from 'react'
import { api, errorMessage, formatDate, type Role, type UserResponse } from '../lib/api'
import { useAuth } from '../lib/auth'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'
import { Avatar } from '../components/Avatar'
import { passwordHint, passwordProblem } from '../lib/password'

type Form = { username: string; password: string; displayName: string; email: string; department: string; role: Role; active: boolean }
const empty: Form = { username: '', password: '', displayName: '', email: '', department: '', role: 'USER', active: true }

export default function AdminUsersPage() {
  const { user: me } = useAuth()
  const toast = useToast()
  const [users, setUsers] = useState<UserResponse[]>([])
  const [filter, setFilter] = useState('')
  const [editing, setEditing] = useState<UserResponse | null | 'new'>(null)
  const [form, setForm] = useState<Form>(empty)
  const [busy, setBusy] = useState(false)
  const [err, setErr] = useState<string | null>(null)
  const [removing, setRemoving] = useState<UserResponse | null>(null)

  const load = () => api.users.list().then(setUsers).catch((e) => toast(errorMessage(e), 'error'))
  useEffect(() => { load() }, [])

  // 영구 삭제: 글을 쓴 적 없는 계정만 가능(글이 있으면 서버가 400 메시지로 거절 → 그대로 표시).
  const remove = async () => {
    if (!removing) return
    setBusy(true)
    try { await api.users.remove(removing.id); toast(`${removing.displayName} 계정을 삭제했습니다.`, 'success'); load() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false); setRemoving(null) }
  }

  const open = (u: UserResponse | 'new') => {
    setErr(null)
    setForm(u === 'new' ? empty : { username: u.username, password: '', displayName: u.displayName, email: u.email ?? '', department: u.department ?? '', role: u.role, active: u.active })
    setEditing(u)
  }

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    const pwProblem = form.password ? passwordProblem(form.password) : null
    if (pwProblem) { setErr(`비밀번호는 ${pwProblem}`); return }
    setBusy(true); setErr(null)
    try {
      if (editing === 'new') {
        await api.users.create({ username: form.username.trim(), password: form.password, displayName: form.displayName.trim(), email: form.email.trim() || undefined, department: form.department.trim() || undefined, role: form.role })
        toast(`${form.displayName} 계정을 만들었습니다.`, 'success')
      } else if (editing) {
        await api.users.update(editing.id, { displayName: form.displayName.trim(), email: form.email.trim() || undefined, department: form.department.trim() || undefined, role: form.role, active: form.active, newPassword: form.password || undefined })
        toast('저장했습니다.', 'success')
      }
      setEditing(null); load()
    } catch (e) { setErr(errorMessage(e)) } finally { setBusy(false) }
  }

  const shown = users.filter((u) => !filter || [u.username, u.displayName, u.department ?? '', u.email ?? ''].some((s) => s.toLowerCase().includes(filter.toLowerCase())))

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <h2>사용자 관리</h2>
          <p>구성원 계정을 만들고 역할과 상태를 관리합니다. 비활성 계정은 로그인할 수 없고 작성한 글은 유지됩니다.</p>
        </div>
        <button type="button" className="btn btn--primary" onClick={() => open('new')}>사용자 추가</button>
      </div>

      <div className="card">
        <div className="card__head">
          <input className="input" style={{ maxWidth: 320, height: 36 }} value={filter} onChange={(e) => setFilter(e.target.value)} placeholder="이름, 아이디, 부서로 찾기" aria-label="사용자 검색" />
          <span className="small muted">전체 {users.length}명 · 활성 {users.filter((u) => u.active).length}명</span>
        </div>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>이름</th><th>아이디</th><th>부서</th><th>이메일</th><th>역할</th><th>상태</th><th>등록일</th><th></th></tr></thead>
            <tbody>
              {shown.map((u) => (
                <tr key={u.id} style={{ opacity: u.active ? 1 : .55 }}>
                  <td><span className="row"><Avatar name={u.displayName} size="xs" />{u.displayName}{u.id === me?.id && <span className="small muted">(나)</span>}</span></td>
                  <td><code>{u.username}</code></td>
                  <td>{u.department ?? <span className="muted">–</span>}</td>
                  <td>{u.email ?? <span className="muted">–</span>}</td>
                  <td>{u.role === 'ADMIN' ? <span className="chip">관리자</span> : <span className="chip chip--neutral">구성원</span>}</td>
                  <td>{u.active ? <span className="chip chip--success">활성</span> : <span className="chip chip--danger">비활성</span>}</td>
                  <td className="small muted">{formatDate(u.createdAt)}</td>
                  <td>
                    <div className="row" style={{ justifyContent: 'flex-end', gap: 6 }}>
                      <button type="button" className="btn btn--sm" onClick={() => open(u)}>편집</button>
                      {u.id !== me?.id && <button type="button" className="btn btn--sm btn--danger" onClick={() => setRemoving(u)}>삭제</button>}
                    </div>
                  </td>
                </tr>
              ))}
              {shown.length === 0 && <tr><td colSpan={8} className="empty">조건에 맞는 사용자가 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <Modal title={editing === 'new' ? '사용자 추가' : `${editing.displayName} 편집`} onClose={() => setEditing(null)}
               footer={<><button type="button" className="btn" onClick={() => setEditing(null)}>취소</button><button type="submit" form="user-form" className="btn btn--primary" disabled={busy}>{busy ? '저장 중…' : editing === 'new' ? '계정 만들기' : '저장'}</button></>}>
          <form id="user-form" onSubmit={submit} className="stack">
            <div className="form-row">
              <div className="field">
                <label htmlFor="u-username">아이디</label>
                <input id="u-username" className="input" value={form.username} onChange={(e) => setForm({ ...form, username: e.target.value })} disabled={editing !== 'new'} required minLength={3} maxLength={50} pattern="[a-zA-Z0-9._\-]+" autoComplete="off" />
                <span className="field__hint">영문, 숫자, . _ - 만 사용</span>
              </div>
              <div className="field">
                <label htmlFor="u-password">{editing === 'new' ? '초기 비밀번호' : '비밀번호 재설정'}</label>
                <input id="u-password" type="password" className="input" value={form.password} onChange={(e) => setForm({ ...form, password: e.target.value })} required={editing === 'new'} minLength={8} autoComplete="new-password" placeholder={editing === 'new' ? passwordHint() : '변경할 때만 입력'} />
                <span className="field__hint">{passwordHint()}. 기존 비밀번호로 로그인하는 것은 막지 않습니다.</span>
              </div>
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="u-name">이름</label>
                <input id="u-name" className="input" value={form.displayName} onChange={(e) => setForm({ ...form, displayName: e.target.value })} required maxLength={100} />
              </div>
              <div className="field">
                <label htmlFor="u-dept">부서</label>
                <input id="u-dept" className="input" value={form.department} onChange={(e) => setForm({ ...form, department: e.target.value })} maxLength={100} placeholder="예) IT전략실" />
              </div>
            </div>
            <div className="field">
              <label htmlFor="u-email">이메일</label>
              <input id="u-email" type="email" className="input" value={form.email} onChange={(e) => setForm({ ...form, email: e.target.value })} maxLength={200} placeholder="name@example.com" />
            </div>
            <div className="form-row">
              <div className="field">
                <label htmlFor="u-role">역할</label>
                <select id="u-role" className="select" value={form.role} onChange={(e) => setForm({ ...form, role: e.target.value as Role })} disabled={editing !== 'new' && editing.id === me?.id}>
                  <option value="USER">구성원 — 글 작성·수정·검색</option>
                  <option value="ADMIN">관리자 — 사용자 관리와 모든 글 편집</option>
                </select>
              </div>
              {editing !== 'new' && (
                <div className="field">
                  <label htmlFor="u-active">상태</label>
                  <select id="u-active" className="select" value={form.active ? '1' : '0'} onChange={(e) => setForm({ ...form, active: e.target.value === '1' })} disabled={editing.id === me?.id}>
                    <option value="1">활성</option>
                    <option value="0">비활성 (로그인 차단)</option>
                  </select>
                </div>
              )}
            </div>
            {err && <div className="alert alert--error">{err}</div>}
          </form>
        </Modal>
      )}

      {removing && (
        <Modal title={`${removing.displayName} 계정을 삭제할까요?`} onClose={() => setRemoving(null)}
               footer={<>
                 <button type="button" className="btn" onClick={() => setRemoving(null)}>취소</button>
                 <button type="button" className="btn btn--danger" disabled={busy} onClick={remove}>{busy ? '삭제 중…' : '삭제'}</button>
               </>}>
          <p>아이디 <code>{removing.username}</code> 계정을 영구 삭제합니다. 되돌릴 수 없습니다.</p>
          <p className="small muted">글을 쓴 적 있는 계정은 삭제할 수 없습니다. 그런 계정은 편집에서 '비활성'으로 바꾸세요.</p>
        </Modal>
      )}
    </div>
  )
}
