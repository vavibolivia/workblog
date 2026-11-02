import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage, formatDate, type UserResponse } from '../lib/api'
import { useAuth } from '../lib/auth'
import { mmApi, type MmImportResult, type MmProject, type MmRow, type MmSheet } from '../lib/manMonthApi'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'
import ManMonthBoard from '../components/ManMonthBoard'
import { useMmSheet } from '../lib/useMmSheet'

type Dialog =
  | { kind: 'project'; project?: MmProject }
  | { kind: 'row'; row?: MmRow; projectId?: number }
  | { kind: 'editors' }
  | { kind: 'settings' }
  | { kind: 'import'; result: MmImportResult }
  | null

let usersCache: Promise<UserResponse[]> | null = null
function activeUsers() {
  if (!usersCache) usersCache = api.users.list().then((l) => l.filter((u) => u.active)).catch(() => { usersCache = null; return [] })
  return usersCache
}

/** 공수 표 한 장: 표 + 프로젝트·인원·편집자·설정·엑셀·변경 이력 */
export default function ManMonthPage() {
  const id = Number(useParams().id)
  const { sheet, setSheet, err, overlay, change, saving, reload } = useMmSheet(id)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const file = useRef<HTMLInputElement>(null)

  if (err) return <div className="shell"><div className="alert alert--error">{err}</div><Link to="/mm" className="btn">공수 표 목록</Link></div>
  if (!sheet) return <div className="shell"><div className="spinner" aria-label="불러오는 중" /></div>

  const run = async (f: () => Promise<MmSheet | void>, ok?: string) => {
    setBusy(true)
    try {
      const s = await f()
      if (s) setSheet(s)
      if (ok) toast(ok, 'success')
      setDialog(null)
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  const onUpload = async (f: File | undefined) => {
    if (!f) return
    setBusy(true)
    try {
      const r = await mmApi.uploadExcel(id, f)
      await reload()
      setDialog({ kind: 'import', result: r })
    } catch (e) { toast(errorMessage(e, '엑셀을 반영하지 못했습니다.'), 'error') } finally { setBusy(false); if (file.current) file.current.value = '' }
  }

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <p className="small muted"><Link to="/mm">공수 표</Link> ›</p>
          <h2>{sheet.title} {sheet.featured && <span className="chip">메인 게시</span>}</h2>
          {sheet.description && <p>{sheet.description}</p>}
          <p className="small muted">주인 {sheet.ownerName ?? '—'} · 편집자 {sheet.editors.length ? sheet.editors.map((e) => e.displayName).join(', ') : '없음'} (관리자는 항상 편집 가능)</p>
        </div>
        <div className="row row--wrap" style={{ gap: 6 }}>
          {sheet.canEdit && <button type="button" className="btn btn--primary" onClick={() => setDialog({ kind: 'project' })}>프로젝트 추가</button>}
          {sheet.canEdit && <button type="button" className="btn" onClick={() => setDialog({ kind: 'row' })} disabled={!sheet.projects.length}>인원 추가</button>}
          <button type="button" className="btn" onClick={() => mmApi.downloadExcel(id, false).catch((e) => toast(errorMessage(e), 'error'))}>엑셀 내려받기</button>
          <button type="button" className="btn" onClick={() => mmApi.downloadExcel(id, true).catch((e) => toast(errorMessage(e), 'error'))}>빈 양식</button>
          {sheet.canEdit && <button type="button" className="btn" onClick={() => file.current?.click()} disabled={busy}>엑셀 올리기</button>}
          <input ref={file} type="file" accept=".xlsx" hidden onChange={(e) => onUpload(e.target.files?.[0])} />
          {sheet.canManage && <button type="button" className="btn" onClick={() => setDialog({ kind: 'editors' })}>편집자</button>}
          {sheet.canManage && <button type="button" className="btn" onClick={() => setDialog({ kind: 'settings' })}>설정</button>}
        </div>
      </div>

      <ManMonthBoard sheet={sheet} overlay={overlay} change={change} saving={saving}
                     onProjectMenu={(p) => setDialog({ kind: 'project', project: p })} onRowMenu={(r) => setDialog({ kind: 'row', row: r })} />

      <details className="card mm-history">
        <summary>변경 이력 (최근 30건)</summary>
        <ul>{sheet.changes.map((c, i) => <li key={i}><span className="muted small">{formatDate(c.createdAt)}</span> {c.actorName ?? '—'} — {c.summary}</li>)}</ul>
      </details>

      {dialog?.kind === 'project' && <ProjectDialog sheet={sheet} project={dialog.project} busy={busy} onClose={() => setDialog(null)} run={run}
                                                    onAddPerson={(pid) => setDialog({ kind: 'row', projectId: pid })} />}
      {dialog?.kind === 'row' && <RowDialog sheet={sheet} row={dialog.row} projectId={dialog.projectId} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'editors' && <EditorsDialog sheet={sheet} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'settings' && <SettingsDialog sheet={sheet} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'import' && (
        <Modal title="엑셀 반영 결과" onClose={() => setDialog(null)} footer={<button type="button" className="btn btn--primary" onClick={() => setDialog(null)}>확인</button>}>
          <p>프로젝트 +{dialog.result.projectsCreated} · 인원 +{dialog.result.rowsCreated} · 값 {dialog.result.cellsSet}칸 · 비움 {dialog.result.cellsCleared}칸</p>
          {dialog.result.warnings.length > 0 && <ul className="small">{dialog.result.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>}
        </Modal>
      )}
    </div>
  )
}

type Run = (f: () => Promise<MmSheet | void>, ok?: string) => Promise<void>

function ProjectDialog({ sheet, project, busy, onClose, run, onAddPerson }: { sheet: MmSheet; project?: MmProject; busy: boolean; onClose: () => void; run: Run; onAddPerson: (pid: number) => void }) {
  const [name, setName] = useState(project?.name ?? '')
  const [confirm, setConfirm] = useState(false)
  const count = project ? sheet.rows.filter((r) => r.projectId === project.id).length : 0
  return (
    <Modal title={project ? `프로젝트 — ${project.name}` : '프로젝트 추가'} onClose={onClose}
           footer={<>
             {project && <button type="button" className="btn btn--danger" disabled={busy} onClick={() => (confirm ? run(() => mmApi.removeProject(sheet.id, project.id), '프로젝트를 지웠습니다.') : setConfirm(true))}>{confirm ? `정말 삭제 (인원 ${count}명 포함)` : '삭제'}</button>}
             {project && <button type="button" className="btn" onClick={() => onAddPerson(project.id)}>이 프로젝트에 인원 추가</button>}
             <button type="button" className="btn btn--primary" disabled={busy || !name.trim()}
                     onClick={() => run(() => (project ? mmApi.renameProject(sheet.id, project.id, name.trim()) : mmApi.addProject(sheet.id, name.trim())))}>저장</button>
           </>}>
      <div className="field"><label htmlFor="mm-pname">프로젝트 이름</label><input id="mm-pname" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus /></div>
    </Modal>
  )
}

function RowDialog({ sheet, row, projectId, busy, onClose, run }: { sheet: MmSheet; row?: MmRow; projectId?: number; busy: boolean; onClose: () => void; run: Run }) {
  const [users, setUsers] = useState<UserResponse[]>([])
  const [pid, setPid] = useState<number>(row?.projectId ?? projectId ?? sheet.projects[0]?.id)
  const [userId, setUserId] = useState<number | null>(row?.userId ?? null)
  const [name, setName] = useState(row?.personName ?? '')
  const [dept, setDept] = useState(row?.department ?? '')
  const [role, setRole] = useState(row?.role ?? '')
  const [q, setQ] = useState('')
  const [confirm, setConfirm] = useState(false)
  useEffect(() => { activeUsers().then(setUsers) }, [])
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    return t ? users.filter((u) => u.displayName.toLowerCase().includes(t) || u.username.toLowerCase().includes(t) || (u.department ?? '').toLowerCase().includes(t)).slice(0, 8) : []
  }, [users, q])
  const pick = (u: UserResponse) => { setUserId(u.id); setName(u.displayName); setDept(u.department ?? ''); setQ('') }
  const body = { projectId: pid, userId, personName: name.trim(), department: dept.trim(), role: role.trim() }
  return (
    <Modal title={row ? `인원 — ${row.personName}` : '인원 추가'} onClose={onClose}
           footer={<>
             {row && <button type="button" className="btn btn--danger" disabled={busy} onClick={() => (confirm ? run(() => mmApi.removeRow(sheet.id, row.id), '행을 지웠습니다.') : setConfirm(true))}>{confirm ? '정말 삭제 (입력한 값 포함)' : '삭제'}</button>}
             <button type="button" className="btn btn--primary" disabled={busy || !name.trim() || !pid}
                     onClick={() => run(() => (row ? mmApi.updateRow(sheet.id, row.id, body) : mmApi.addRow(sheet.id, body)))}>{row ? '저장' : '추가'}</button>
           </>}>
      <div className="field"><label htmlFor="mm-proj">프로젝트</label>
        <select id="mm-proj" className="select" value={pid} onChange={(e) => setPid(Number(e.target.value))}>{sheet.projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
      </div>
      <div className="field"><label htmlFor="mm-user">계정에서 찾기 <span className="muted small">(없으면 아래에 이름 직접 입력 — 외부 인력)</span></label>
        <input id="mm-user" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름 · 아이디 · 부서" />
        {shown.length > 0 && <ul className="mm-pick">{shown.map((u) => <li key={u.id}><button type="button" onClick={() => pick(u)}>{u.displayName} <span className="muted small">{u.username} · {u.department ?? '부서 없음'}</span></button></li>)}</ul>}
        {userId != null && <p className="small">연결된 계정: <strong>{users.find((u) => u.id === userId)?.displayName ?? userId}</strong> <button type="button" className="btn btn--ghost btn--sm" onClick={() => setUserId(null)}>연결 해제</button></p>}
      </div>
      <div className="field"><label htmlFor="mm-name">이름</label><input id="mm-name" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} /></div>
      <div className="row" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-dept">소속</label><input id="mm-dept" className="input" value={dept} onChange={(e) => setDept(e.target.value)} maxLength={100} /></div>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-role">역할</label><input id="mm-role" className="input" value={role} onChange={(e) => setRole(e.target.value)} maxLength={100} placeholder="예) PM · 개발 · QA" /></div>
      </div>
    </Modal>
  )
}

function EditorsDialog({ sheet, busy, onClose, run }: { sheet: MmSheet; busy: boolean; onClose: () => void; run: Run }) {
  const [users, setUsers] = useState<UserResponse[]>([])
  const [ids, setIds] = useState<Set<number>>(new Set(sheet.editors.map((e) => e.id)))
  const [q, setQ] = useState('')
  useEffect(() => { activeUsers().then(setUsers) }, [])
  const t = q.trim().toLowerCase()
  const shown = users.filter((u) => u.role !== 'ADMIN' && u.id !== sheet.ownerId && (!t || u.displayName.toLowerCase().includes(t) || u.username.toLowerCase().includes(t) || (u.department ?? '').toLowerCase().includes(t)))
  return (
    <Modal title="편집자 지정" onClose={onClose} wide
           footer={<button type="button" className="btn btn--primary" disabled={busy} onClick={() => run(() => mmApi.setEditors(sheet.id, [...ids]), '편집자를 저장했습니다.')}>저장 ({ids.size}명)</button>}>
      <p className="small muted">편집자는 모든 칸·프로젝트·인원을 고칠 수 있습니다. 표 주인과 관리자는 항상 편집·설정 가능. 나머지는 보기만.</p>
      <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름 · 아이디 · 부서로 찾기" />
      <div className="mm-editors">
        {shown.map((u) => (
          <label key={u.id} className="mm-editor">
            <input type="checkbox" checked={ids.has(u.id)} onChange={(e) => setIds((s) => { const n = new Set(s); if (e.target.checked) n.add(u.id); else n.delete(u.id); return n })} />
            {u.displayName} <span className="muted small">{u.department ?? ''}</span>
          </label>
        ))}
      </div>
    </Modal>
  )
}

function SettingsDialog({ sheet, busy, onClose, run }: { sheet: MmSheet; busy: boolean; onClose: () => void; run: Run }) {
  const { user } = useAuth()
  const navigate = useNavigate()
  const [title, setTitle] = useState(sheet.title)
  const [desc, setDesc] = useState(sheet.description ?? '')
  const [start, setStart] = useState(sheet.startDate)
  const [weeks, setWeeks] = useState(sheet.weeks)
  const [featured, setFeatured] = useState(sheet.featured)
  const [confirm, setConfirm] = useState(false)
  return (
    <Modal title="표 설정" onClose={onClose}
           footer={<>
             <button type="button" className="btn btn--danger" disabled={busy} onClick={() => (confirm ? run(async () => { await mmApi.remove(sheet.id); navigate('/mm') }, '표를 지웠습니다.') : setConfirm(true))}>{confirm ? '정말 표 삭제' : '표 삭제'}</button>
             <button type="button" className="btn btn--primary" disabled={busy || !title.trim()}
                     onClick={() => run(() => mmApi.update(sheet.id, { title: title.trim(), description: desc, startDate: start, weeks, ...(user?.role === 'ADMIN' ? { featured } : {}) }), '설정을 저장했습니다.')}>저장</button>
           </>}>
      <div className="field"><label htmlFor="mm-t">제목</label><input id="mm-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></div>
      <div className="field"><label htmlFor="mm-d">설명</label><input id="mm-d" className="input" value={desc} onChange={(e) => setDesc(e.target.value)} maxLength={1000} /></div>
      <div className="row" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-s">시작 주 (월요일로 맞춤)</label><input id="mm-s" type="date" className="input" value={start} onChange={(e) => setStart(e.target.value)} /></div>
        <div className="field" style={{ width: 120 }}><label htmlFor="mm-w">주 수</label><input id="mm-w" type="number" min={1} max={104} className="input" value={weeks} onChange={(e) => setWeeks(Math.max(1, Math.min(104, Number(e.target.value) || 1)))} /></div>
      </div>
      <p className="small muted">시작을 바꾸면 입력한 값은 날짜 기준으로 유지되고, 새 기간 밖의 값은 지워집니다.</p>
      {user?.role === 'ADMIN' && <label className="row" style={{ gap: 6 }}><input type="checkbox" checked={featured} onChange={(e) => setFeatured(e.target.checked)} /> 메인 화면 상단에 게시</label>}
    </Modal>
  )
}
