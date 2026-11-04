import { useEffect, useState } from 'react'
import { errorMessage, http } from '../lib/api'
import { useToast } from '../lib/toast'
import Modal from './Modal'

/**
 * 태그·부서 관리(관리자). 둘 다 글자로 여러 곳에 복사돼 있어(태그: 글·템플릿, 부서: 사용자·카테고리 열람 제한·공수 표)
 * 여기서 바꾸면 서버가 모든 곳을 한 번에 바꾼다. 대소문자만 다른 것은 같은 것으로 본다.
 */
interface TagUsage { name: string; posts: number; templates: number }
interface DeptUsage { name: string; users: number; categories: number }
interface RenameResult { posts: number; templates: number; users: number; categories: number }
type Kind = 'tag' | 'dept'

const api = {
  tags: () => http.get<TagUsage[]>('/admin/tags').then((r) => r.data),
  departments: () => http.get<DeptUsage[]>('/admin/departments').then((r) => r.data),
  renameTag: (from: string, to: string) => http.put<RenameResult>('/admin/tags/rename', { from, to }).then((r) => r.data),
  renameDept: (from: string, to: string) => http.put<RenameResult>('/admin/departments/rename', { from, to }).then((r) => r.data),
}

export function TagAdmin() {
  const [list, setList] = useState<TagUsage[] | null>(null)
  const [q, setQ] = useState('')
  const [edit, setEdit] = useState<{ name: string; del: boolean } | null>(null)
  const load = () => api.tags().then(setList).catch(() => setList([]))
  useEffect(() => { load() }, [])
  const lower = (s: string) => s.toLowerCase()
  const dupKeys = new Set((list ?? []).map((t) => lower(t.name)).filter((k, i, a) => a.indexOf(k) !== i))
  const shown = (list ?? []).filter((t) => !q.trim() || lower(t.name).includes(lower(q.trim())))
  return (
    <div className="card">
      <div className="card__head"><h3>태그</h3><span className="small muted">전체 {list?.length ?? 0}개</span></div>
      <div className="card__body">
        <p className="small muted" style={{ marginTop: 0 }}>이름을 바꾸면 그 태그를 쓴 모든 글·템플릿이 함께 바뀝니다. 이미 있는 태그 이름으로 바꾸면 하나로 합쳐집니다.
          {dupKeys.size > 0 && <> <strong style={{ color: 'var(--danger)' }}>대소문자만 다른 태그 {dupKeys.size}쌍</strong>이 있습니다(표시 ⚠). 하나로 합치세요.</>}</p>
        <input className="input" style={{ maxWidth: 320, marginBottom: 8 }} value={q} onChange={(e) => setQ(e.target.value)} placeholder="태그 찾기" aria-label="태그 찾기" />
        <div className="table-wrap tax-scroll">
          <table className="table">
            <thead><tr><th>태그</th><th className="num">글</th><th className="num">템플릿</th><th /></tr></thead>
            <tbody>
              {shown.map((t) => (
                <tr key={t.name}>
                  <td>{dupKeys.has(lower(t.name)) && <span title="대소문자만 다른 태그가 있습니다">⚠ </span>}{t.name}</td>
                  <td className="num">{t.posts}</td><td className="num">{t.templates}</td>
                  <td className="nowrap" style={{ textAlign: 'right' }}>
                    <button type="button" className="btn btn--ghost btn--sm" onClick={() => setEdit({ name: t.name, del: false })}>바꾸기·합치기</button>
                    <button type="button" className="btn btn--ghost btn--sm" onClick={() => setEdit({ name: t.name, del: true })}>지우기</button>
                  </td>
                </tr>
              ))}
              {list && !shown.length && <tr><td colSpan={4} className="muted small">태그가 없습니다.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {edit && <RenameDialog kind="tag" from={edit.name} remove={edit.del} names={(list ?? []).map((t) => t.name)}
                             onClose={() => setEdit(null)} onDone={() => { setEdit(null); load() }} />}
    </div>
  )
}

export function DepartmentAdmin({ onChanged }: { onChanged?: () => void }) {
  const [list, setList] = useState<DeptUsage[] | null>(null)
  const [edit, setEdit] = useState<string | null>(null)
  const load = () => api.departments().then(setList).catch(() => setList([]))
  useEffect(() => { load() }, [])
  return (
    <div className="card">
      <div className="card__head"><h3>부서</h3><span className="small muted">전체 {list?.length ?? 0}개</span></div>
      <div className="card__body">
        <p className="small muted" style={{ marginTop: 0 }}>부서 이름을 바꾸면 그 부서 사용자, 카테고리 열람 제한, 공수 표 소속이 함께 바뀝니다(한쪽만 바뀌어 제한 글이 안 보이는 일 방지). 이미 있는 부서 이름으로 바꾸면 합쳐집니다. 사람마다 부서를 바꾸려면 위 사용자 목록에서 고치세요.</p>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th>부서</th><th className="num">사용자</th><th className="num">열람 제한 카테고리</th><th /></tr></thead>
            <tbody>
              {(list ?? []).map((d) => (
                <tr key={d.name}>
                  <td>{d.name}{d.users === 0 && <span className="small muted"> (사용자 없음 — 열람 제한에만 있음)</span>}</td>
                  <td className="num">{d.users}</td><td className="num">{d.categories}</td>
                  <td style={{ textAlign: 'right' }}><button type="button" className="btn btn--ghost btn--sm" onClick={() => setEdit(d.name)}>바꾸기·합치기</button></td>
                </tr>
              ))}
              {list && !list.length && <tr><td colSpan={4} className="muted small">부서가 없습니다. 사용자 정보에 부서를 적으면 여기에 나옵니다.</td></tr>}
            </tbody>
          </table>
        </div>
      </div>
      {edit && <RenameDialog kind="dept" from={edit} names={(list ?? []).map((d) => d.name)}
                             onClose={() => setEdit(null)} onDone={() => { setEdit(null); load(); onChanged?.() }} />}
    </div>
  )
}

function RenameDialog({ kind, from, remove, names, onClose, onDone }: { kind: Kind; from: string; remove?: boolean; names: string[]; onClose: () => void; onDone: () => void }) {
  const [to, setTo] = useState(remove ? '' : from)
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const what = kind === 'tag' ? '태그' : '부서'
  const merge = !remove && names.some((n) => n !== from && n.toLowerCase() === to.trim().toLowerCase())
  const changed = remove || (to.trim() !== '' && to.trim() !== from)
  const submit = async () => {
    setBusy(true)
    try {
      const r = kind === 'tag' ? await api.renameTag(from, remove ? '' : to.trim()) : await api.renameDept(from, to.trim())
      toast(kind === 'tag' ? `${remove ? '지웠습니다' : '바꿨습니다'} — 글 ${r.posts}건 · 템플릿 ${r.templates}개` : `바꿨습니다 — 사용자 ${r.users}명 · 카테고리 ${r.categories}개`, 'success')
      onDone()
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title={remove ? `태그 '${from}' 지우기` : `${what} '${from}' 이름 바꾸기`} onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className={`btn ${remove ? 'btn--danger' : 'btn--primary'}`} disabled={busy || !changed} onClick={submit}>{remove ? '모든 글에서 지우기' : merge ? '합치기' : '바꾸기'}</button></>}>
      {remove
        ? <p>모든 글·템플릿에서 <strong>{from}</strong> 태그를 뺍니다. 글 내용은 그대로입니다.</p>
        : <div className="field"><label htmlFor="rn-to">새 이름</label>
            <input id="rn-to" className="input" value={to} onChange={(e) => setTo(e.target.value)} maxLength={100} autoFocus list="rn-names" />
            <datalist id="rn-names">{names.filter((n) => n !== from).map((n) => <option key={n} value={n} />)}</datalist>
            {merge && <p className="small" style={{ margin: '6px 0 0' }}>이미 있는 {what} <strong>{to.trim()}</strong> 와 하나로 합쳐집니다.</p>}
          </div>}
    </Modal>
  )
}
