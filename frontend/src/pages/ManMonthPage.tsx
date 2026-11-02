import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, errorMessage, formatDate, type CategoryNode, type UserResponse } from '../lib/api'
import { mmApi, mondayOf, periodText, type MmImportResult, type MmProject, type MmRow, type MmSheet } from '../lib/manMonthApi'
import { useToast } from '../lib/toast'
import Modal from '../components/Modal'
import ManMonthBoard from '../components/ManMonthBoard'
import { useMmSheet } from '../lib/useMmSheet'

type Dialog =
  | { kind: 'project'; project?: MmProject }
  | { kind: 'row'; row?: MmRow; projectId?: number }
  | { kind: 'editors' }
  | { kind: 'settings' }
  | { kind: 'publish' }
  | { kind: 'import'; file: File; keepBlank: boolean; result: MmImportResult }
  | null

let usersCache: Promise<UserResponse[]> | null = null
function activeUsers() {
  if (!usersCache) usersCache = api.users.list().then((l) => l.filter((u) => u.active)).catch(() => { usersCache = null; return [] })
  return usersCache
}

/** 버튼 하나 + 펼침 메뉴(엑셀 ▾, 더 보기 ▾) */
function MenuButton({ label, children }: { label: string; children: (close: () => void) => ReactNode }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!open) return
    const onDoc = (e: MouseEvent) => { if (!ref.current?.contains(e.target as Node)) setOpen(false) }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', onDoc); document.addEventListener('keydown', onKey)
    return () => { document.removeEventListener('mousedown', onDoc); document.removeEventListener('keydown', onKey) }
  }, [open])
  return (
    <div className="export" ref={ref}>
      <button type="button" className="btn" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen((v) => !v)}>{label} ▾</button>
      {open && <div className="menu" role="menu">{children(() => setOpen(false))}</div>}
    </div>
  )
}

/** 공수 표 한 장: 표 + 프로젝트·인원·편집자·설정·엑셀·변경 이력 */
export default function ManMonthPage() {
  const id = Number(useParams().id)
  const state = useMmSheet(id)
  const { sheet, setSheet, err, reload } = state
  const [dialog, setDialog] = useState<Dialog>(null)
  const [busy, setBusy] = useState(false)
  const toast = useToast()
  const file = useRef<HTMLInputElement>(null)
  const [titleEdit, setTitleEdit] = useState<string | null>(null)

  if (err) return <div className="shell"><div className="alert alert--error">{err}</div><Link to="/mm" className="btn">공수 표 목록</Link></div>
  if (!sheet) return <div className="shell"><div className="spinner" aria-label="불러오는 중" /></div>

  const run = async (f: () => Promise<MmSheet | void>, ok?: string, keepOpen = false) => {
    setBusy(true)
    try {
      const s = await f()
      if (s) setSheet(s)
      if (ok) toast(ok, 'success')
      if (!keepOpen) setDialog(null)
      return true
    } catch (e) { toast(errorMessage(e), 'error'); return false } finally { setBusy(false) }
  }

  /** 엑셀 올리기 1단계: 미리보기(아무것도 바꾸지 않음) */
  const preview = async (f: File | undefined, keepBlank = true) => {
    if (!f) return
    setBusy(true)
    try {
      const r = await mmApi.uploadExcel(id, f, { dryRun: true, keepBlank })
      setDialog({ kind: 'import', file: f, keepBlank, result: r })
    } catch (e) { toast(errorMessage(e, '엑셀을 읽지 못했습니다.'), 'error') } finally { setBusy(false); if (file.current) file.current.value = '' }
  }
  /** 2단계: 반영 */
  const applyImport = async (f: File, keepBlank: boolean) => {
    setBusy(true)
    try {
      const r = await mmApi.uploadExcel(id, f, { dryRun: false, keepBlank })
      await reload()
      setDialog(null)
      toast(`엑셀을 반영했습니다 — 프로젝트 +${r.projectsCreated} · 인원 +${r.rowsCreated} · 바뀜 ${r.cellsSet}칸 · 비움 ${r.cellsCleared}칸`, 'success')
    } catch (e) { toast(errorMessage(e, '엑셀을 반영하지 못했습니다.'), 'error') } finally { setBusy(false) }
  }
  const download = (template: boolean) => mmApi.downloadExcel(id, template).catch((e) => toast(errorMessage(e), 'error'))
  const noProjects = sheet.projects.length === 0

  return (
    <div className="shell shell--wide">
      <div className="page-head row row--between row--wrap">
        <div>
          <p className="small muted"><Link to="/mm">공수 표</Link> ›</p>
          {titleEdit !== null ? (
            <form className="row mm-title-edit" style={{ gap: 6 }} onSubmit={(e) => { e.preventDefault(); if (titleEdit.trim() && titleEdit.trim() !== sheet.title) run(() => mmApi.update(sheet.id, { title: titleEdit.trim() }), '제목을 바꿨습니다.').then((ok) => { if (ok) setTitleEdit(null) }); else setTitleEdit(null) }}>
              <input className="input" value={titleEdit} onChange={(e) => setTitleEdit(e.target.value)} maxLength={200} autoFocus aria-label="표 제목"
                     onKeyDown={(e) => { if (e.key === 'Escape') setTitleEdit(null) }} />
              <button type="submit" className="btn btn--primary btn--sm" disabled={busy || !titleEdit.trim()}>저장</button>
              <button type="button" className="btn btn--sm" onClick={() => setTitleEdit(null)}>취소</button>
            </form>
          ) : (
            <h2>{sheet.title}{sheet.canManage && <button type="button" className="btn btn--ghost btn--sm mm-title-btn" onClick={() => setTitleEdit(sheet.title)} title="제목 고치기 (표 주인·관리자)">✎ 제목 고치기</button>}</h2>
          )}
          {sheet.description && <p>{sheet.description}</p>}
          <p className="small muted">주인 {sheet.ownerName ?? '—'} · 편집자 {sheet.editors.length ? sheet.editors.map((e) => e.displayName).join(', ') : '없음'} (관리자는 항상 편집 가능)</p>
        </div>
        <div className="row row--wrap" style={{ gap: 6 }}>
          <button type="button" className="btn" onClick={() => setDialog({ kind: 'publish' })} title="이 표를 담은 글을 만듭니다. 글에서도 바로 고칠 수 있고, 그 글을 대문에 올릴 수 있습니다.">글로 게시</button>
          {sheet.canEdit && <button type="button" className="btn btn--primary" onClick={() => setDialog({ kind: 'project' })}>프로젝트 추가</button>}
          {sheet.canEdit && <button type="button" className="btn" onClick={() => setDialog({ kind: 'row' })} disabled={noProjects} title={noProjects ? '먼저 프로젝트를 추가하세요' : undefined}>인원 추가</button>}
          <MenuButton label="엑셀">
            {(close) => <>
              <button type="button" role="menuitem" onClick={() => { close(); download(false) }}><span className="grow">내려받기</span><span className="small muted">지금 표 그대로</span></button>
              <button type="button" role="menuitem" onClick={() => { close(); download(true) }}><span className="grow">양식 내려받기</span><span className="small muted">값 없이 프로젝트·인원만</span></button>
              {sheet.canEdit && <button type="button" role="menuitem" disabled={busy} onClick={() => { close(); file.current?.click() }}><span className="grow">올리기…</span><span className="small muted">반영 전에 미리보기</span></button>}
            </>}
          </MenuButton>
          <input ref={file} type="file" accept=".xlsx" hidden onChange={(e) => preview(e.target.files?.[0])} />
          {sheet.canManage && (
            <MenuButton label="더 보기">
              {(close) => <>
                <button type="button" role="menuitem" onClick={() => { close(); setDialog({ kind: 'editors' }) }}><span className="grow">편집자 지정</span><span className="small muted">{sheet.editors.length}명</span></button>
                <button type="button" role="menuitem" onClick={() => { close(); setDialog({ kind: 'settings' }) }}><span className="grow">표 설정</span><span className="small muted">제목 · 기간 · 삭제</span></button>
              </>}
            </MenuButton>
          )}
        </div>
      </div>

      {noProjects && sheet.canEdit ? (
        <div className="card mm-start">
          <h3>표가 비어 있습니다 — 두 가지 방법으로 시작하세요</h3>
          <ol>
            <li><strong>화면에서</strong>: <button type="button" className="btn btn--primary btn--sm" onClick={() => setDialog({ kind: 'project' })}>프로젝트 추가</button> → 프로젝트 줄의 <strong>+ 인원</strong> → 칸을 눌러 숫자 입력</li>
            <li><strong>엑셀로</strong>: <button type="button" className="btn btn--sm" onClick={() => download(true)}>양식 내려받기</button> → 프로젝트·이름·주별 값 채우기 → <button type="button" className="btn btn--sm" onClick={() => file.current?.click()}>엑셀 올리기</button></li>
          </ol>
        </div>
      ) : (
        <ManMonthBoard state={state} onProjectMenu={(p) => setDialog({ kind: 'project', project: p })} onRowMenu={(r) => setDialog({ kind: 'row', row: r })}
                       onAddPerson={(pid) => setDialog({ kind: 'row', projectId: pid })} />
      )}

      <details className="card mm-history">
        <summary>변경 이력 (최근 30건)</summary>
        <ul>{sheet.changes.map((c, i) => <li key={i}><span className="muted small">{formatDate(c.createdAt)}</span> {c.actorName ?? '—'} — {c.summary}</li>)}</ul>
      </details>

      {dialog?.kind === 'project' && <ProjectDialog sheet={sheet} project={dialog.project} busy={busy} onClose={() => setDialog(null)} run={run}
                                                    onAddPerson={(pid) => setDialog({ kind: 'row', projectId: pid })} />}
      {dialog?.kind === 'row' && <RowDialog sheet={sheet} row={dialog.row} projectId={dialog.projectId} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'editors' && <EditorsDialog sheet={sheet} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'settings' && <SettingsDialog sheet={sheet} busy={busy} onClose={() => setDialog(null)} run={run} />}
      {dialog?.kind === 'publish' && <PublishDialog sheet={sheet} onClose={() => setDialog(null)} />}
      {dialog?.kind === 'import' && (() => {
        const r = dialog.result
        const nothing = !r.projectsCreated && !r.rowsCreated && !r.cellsSet && !r.cellsCleared
        return (
          <Modal title={`엑셀 올리기 — 미리보기: ${dialog.file.name}`} onClose={() => setDialog(null)}
                 footer={<>
                   <button type="button" className="btn" onClick={() => setDialog(null)}>취소</button>
                   <button type="button" className="btn btn--primary" disabled={busy || nothing} onClick={() => applyImport(dialog.file, dialog.keepBlank)}>{nothing ? '바뀌는 것 없음' : '이대로 반영'}</button>
                 </>}>
            <p>아직 아무것도 바뀌지 않았습니다. 반영하면 아래처럼 바뀝니다.</p>
            <ul className="mm-preview">
              <li>새 프로젝트 <strong>{r.projectsCreated}</strong>개 · 새 인원 <strong>{r.rowsCreated}</strong>명</li>
              <li>값이 바뀌는 칸 <strong>{r.cellsSet}</strong>칸</li>
              <li className={r.cellsCleared ? 'mm-preview__warn' : undefined}>지워지는 칸 <strong>{r.cellsCleared}</strong>칸</li>
            </ul>
            <label className="check">
              <input type="checkbox" checked={dialog.keepBlank} disabled={busy} onChange={(e) => preview(dialog.file, e.target.checked)} />
              파일의 빈 칸은 지금 값 그대로 두기 (권장 — 끄면 빈 칸이 지워집니다)
            </label>
            <p className="small muted">파일에 없는 프로젝트·인원은 그대로 둡니다. 같은 프로젝트의 같은 이름은 같은 사람으로 봅니다.</p>
            {r.warnings.length > 0 && <><p className="small"><strong>확인할 것 {r.warnings.length}건</strong></p><ul className="small">{r.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul></>}
          </Modal>
        )
      })()}
    </div>
  )
}

type Run = (f: () => Promise<MmSheet | void>, ok?: string, keepOpen?: boolean) => Promise<boolean>

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

function RowDialog(props: { sheet: MmSheet; row?: MmRow; projectId?: number; busy: boolean; onClose: () => void; run: Run }) {
  return props.row ? <RowEditDialog {...props} row={props.row} /> : <RowAddDialog {...props} />
}

/** 인원 추가: 계정을 누르면 바로 추가(창은 열린 채), 외부 인력은 이름을 여러 줄로 한 번에 */
function RowAddDialog({ sheet, projectId, busy, onClose, run }: { sheet: MmSheet; projectId?: number; busy: boolean; onClose: () => void; run: Run }) {
  const [users, setUsers] = useState<UserResponse[]>([])
  const [pid, setPid] = useState<number>(projectId ?? sheet.projects[0]?.id)
  const [q, setQ] = useState('')
  const [role, setRole] = useState('')
  const [names, setNames] = useState('')
  const [dept, setDept] = useState('')
  const [added, setAdded] = useState<string[]>([])
  useEffect(() => { activeUsers().then(setUsers) }, [])
  const inProject = useMemo(() => new Set(sheet.rows.filter((r) => r.projectId === pid && r.userId != null).map((r) => r.userId)), [sheet.rows, pid])
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    return t ? users.filter((u) => u.displayName.toLowerCase().includes(t) || u.username.toLowerCase().includes(t) || (u.department ?? '').toLowerCase().includes(t)).slice(0, 12) : []
  }, [users, q])
  const pname = sheet.projects.find((p) => p.id === pid)?.name ?? ''
  const addUser = async (u: UserResponse) => {
    if (await run(() => mmApi.addRow(sheet.id, { projectId: pid, userId: u.id, personName: u.displayName, department: u.department ?? '', role: role.trim() }), undefined, true))
      setAdded((a) => [...a, u.displayName])
  }
  const lines = names.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  const addNames = async () => {
    let last: MmSheet | undefined
    const ok = await run(async () => {
      for (const n of lines) last = await mmApi.addRow(sheet.id, { projectId: pid, userId: null, personName: n.slice(0, 100), department: dept.trim(), role: role.trim() })
      return last
    }, undefined, true)
    if (ok) { setAdded((a) => [...a, ...lines]); setNames('') }
  }
  return (
    <Modal title="인원 추가" onClose={onClose} wide
           footer={<>
             <span className="small muted" style={{ marginRight: 'auto' }}>{added.length ? `이번에 ${added.length}명 추가: ${added.slice(-5).join(', ')}${added.length > 5 ? ' …' : ''}` : '계정을 누르면 바로 추가됩니다. 창은 닫히지 않습니다.'}</span>
             <button type="button" className="btn btn--primary" onClick={onClose}>{added.length ? '다 했어요' : '닫기'}</button>
           </>}>
      <div className="row row--wrap" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 2, minWidth: 200 }}><label htmlFor="mm-proj">어느 프로젝트에</label>
          <select id="mm-proj" className="select" value={pid} onChange={(e) => setPid(Number(e.target.value))}>{sheet.projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
        </div>
        <div className="field" style={{ flex: 1, minWidth: 140 }}><label htmlFor="mm-role">역할 <span className="muted small">(선택)</span></label>
          <input id="mm-role" className="input" value={role} onChange={(e) => setRole(e.target.value)} maxLength={100} placeholder="예) PM · 개발 · QA" />
        </div>
      </div>
      <div className="mm-add">
        <div>
          <h4>계정에서 찾기</h4>
          <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름 · 아이디 · 부서" autoFocus aria-label="계정 찾기" />
          {q.trim() && shown.length === 0 && <p className="small muted">찾는 계정이 없습니다. 오른쪽에 이름을 직접 넣으세요.</p>}
          <ul className="mm-pick">
            {shown.map((u) => {
              const has = inProject.has(u.id)
              return (
                <li key={u.id}>
                  <button type="button" disabled={busy || has} onClick={() => addUser(u)}>
                    {has ? '✓ ' : '+ '}{u.displayName} <span className="muted small">{u.username} · {u.department ?? '부서 없음'}{has ? ` · 이미 ${pname}에 있음` : ''}</span>
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
        <div>
          <h4>직접 입력 <span className="muted small">— 계정 없는 외부 인력, 한 줄에 한 명</span></h4>
          <textarea className="input" rows={5} value={names} onChange={(e) => setNames(e.target.value)} placeholder={'홍길동\n김협력\n(엑셀 이름 열을 복사해 붙여넣어도 됩니다)'} aria-label="이름 여러 줄" />
          <input className="input" style={{ marginTop: 6 }} value={dept} onChange={(e) => setDept(e.target.value)} maxLength={100} placeholder="소속 (선택, 모두 같게)" aria-label="소속" />
          <button type="button" className="btn" style={{ marginTop: 6 }} disabled={busy || !lines.length || !pid} onClick={addNames}>{lines.length ? `${lines.length}명 추가` : '이름을 넣으세요'}</button>
        </div>
      </div>
    </Modal>
  )
}

/** 인원 고치기: 이름·소속·역할·계정 연결·프로젝트 옮기기·삭제 */
function RowEditDialog({ sheet, row, busy, onClose, run }: { sheet: MmSheet; row: MmRow; busy: boolean; onClose: () => void; run: Run }) {
  const [users, setUsers] = useState<UserResponse[]>([])
  const [pid, setPid] = useState<number>(row.projectId)
  const [userId, setUserId] = useState<number | null>(row.userId)
  const [name, setName] = useState(row.personName)
  const [dept, setDept] = useState(row.department ?? '')
  const [role, setRole] = useState(row.role ?? '')
  const [q, setQ] = useState('')
  const [confirm, setConfirm] = useState(false)
  useEffect(() => { activeUsers().then(setUsers) }, [])
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    return t ? users.filter((u) => u.displayName.toLowerCase().includes(t) || u.username.toLowerCase().includes(t) || (u.department ?? '').toLowerCase().includes(t)).slice(0, 8) : []
  }, [users, q])
  const pick = (u: UserResponse) => { setUserId(u.id); setName(u.displayName); setDept(u.department ?? ''); setQ('') }
  const body = { projectId: pid, userId, personName: name.trim(), department: dept.trim(), role: role.trim() }
  const filled = Object.keys(row.cells).length
  return (
    <Modal title={`인원 — ${row.personName}`} onClose={onClose}
           footer={<>
             <button type="button" className="btn btn--danger" disabled={busy} style={{ marginRight: 'auto' }}
                     onClick={() => (confirm ? run(() => mmApi.removeRow(sheet.id, row.id), '행을 지웠습니다.') : setConfirm(true))}>{confirm ? `정말 삭제 (입력한 ${filled}칸 포함)` : '이 행 삭제'}</button>
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className="btn btn--primary" disabled={busy || !name.trim() || !pid} onClick={() => run(() => mmApi.updateRow(sheet.id, row.id, body), '저장했습니다.')}>저장</button>
           </>}>
      <div className="field"><label htmlFor="mm-proj">프로젝트 <span className="muted small">(바꾸면 입력한 값과 함께 옮겨집니다)</span></label>
        <select id="mm-proj" className="select" value={pid} onChange={(e) => setPid(Number(e.target.value))}>{sheet.projects.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}</select>
      </div>
      <div className="field"><label htmlFor="mm-name">이름</label><input id="mm-name" className="input" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus /></div>
      <div className="row" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-dept">소속</label><input id="mm-dept" className="input" value={dept} onChange={(e) => setDept(e.target.value)} maxLength={100} /></div>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-role">역할</label><input id="mm-role" className="input" value={role} onChange={(e) => setRole(e.target.value)} maxLength={100} placeholder="예) PM · 개발 · QA" /></div>
      </div>
      <div className="field"><label htmlFor="mm-user">계정 연결 <span className="muted small">(연결하면 사람별 부하에서 다른 프로젝트와 합산)</span></label>
        {userId != null
          ? <p className="small">연결됨: <strong>{users.find((u) => u.id === userId)?.displayName ?? row.personName}</strong> <button type="button" className="btn btn--ghost btn--sm" onClick={() => setUserId(null)}>연결 해제</button></p>
          : <>
              <input id="mm-user" className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름 · 아이디 · 부서로 계정 찾기" />
              {shown.length > 0 && <ul className="mm-pick">{shown.map((u) => <li key={u.id}><button type="button" onClick={() => pick(u)}>{u.displayName} <span className="muted small">{u.username} · {u.department ?? '부서 없음'}</span></button></li>)}</ul>}
            </>}
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
  const candidates = users.filter((u) => u.role !== 'ADMIN' && u.id !== sheet.ownerId)
  const shown = candidates.filter((u) => !ids.has(u.id) && (!t || u.displayName.toLowerCase().includes(t) || u.username.toLowerCase().includes(t) || (u.department ?? '').toLowerCase().includes(t)))
  const chosen = candidates.filter((u) => ids.has(u.id))
  const flip = (id: number, on: boolean) => setIds((s) => { const n = new Set(s); if (on) n.add(id); else n.delete(id); return n })
  return (
    <Modal title="편집자 지정" onClose={onClose} wide
           footer={<button type="button" className="btn btn--primary" disabled={busy} onClick={() => run(() => mmApi.setEditors(sheet.id, [...ids]), '편집자를 저장했습니다.')}>저장 ({ids.size}명)</button>}>
      <p className="small muted">편집자는 모든 칸·프로젝트·인원을 고칠 수 있습니다. 표 주인과 관리자는 항상 편집·설정 가능. 나머지는 보기만.</p>
      <div className="mm-chips">
        {chosen.length === 0 ? <span className="small muted">아직 편집자가 없습니다. 아래에서 고르세요.</span>
          : chosen.map((u) => <span key={u.id} className="chip">{u.displayName}<button type="button" className="chip__x" onClick={() => flip(u.id, false)} aria-label={`${u.displayName} 빼기`}>✕</button></span>)}
      </div>
      <input className="input" value={q} onChange={(e) => setQ(e.target.value)} placeholder="이름 · 아이디 · 부서로 찾기" autoFocus />
      <div className="mm-editors">
        {shown.map((u) => (
          <label key={u.id} className="mm-editor">
            <input type="checkbox" checked={false} onChange={() => flip(u.id, true)} />
            {u.displayName} <span className="muted small">{u.department ?? ''}</span>
          </label>
        ))}
      </div>
    </Modal>
  )
}

/** 표 기간을 바꾸면 기간 밖으로 나가 지워지는 칸 수(서버와 같은 규칙: 날짜 기준으로 옮김) */
function lostCells(sheet: MmSheet, start: string, weeks: number): number {
  const day = (s: string) => { const [y, m, d] = s.split('-').map(Number); return new Date(y, m - 1, d) }
  const s0 = day(sheet.startDate)
  const n = day(start)
  n.setDate(n.getDate() - ((n.getDay() + 6) % 7))   // 그 주 월요일
  const shift = Math.round((s0.getTime() - n.getTime()) / (7 * 864e5))
  let lost = 0
  for (const r of sheet.rows) for (const w of Object.keys(r.cells)) { const nw = Number(w) + shift; if (nw < 0 || nw >= weeks) lost++ }
  return lost
}

function SettingsDialog({ sheet, busy, onClose, run }: { sheet: MmSheet; busy: boolean; onClose: () => void; run: Run }) {
  const navigate = useNavigate()
  const [title, setTitle] = useState(sheet.title)
  const [desc, setDesc] = useState(sheet.description ?? '')
  const [start, setStart] = useState(sheet.startDate)
  const [weeks, setWeeks] = useState(sheet.weeks)
  const [confirm, setConfirm] = useState(false)
  const [confirmLoss, setConfirmLoss] = useState(false)
  const lost = start && weeks ? lostCells(sheet, start, weeks) : 0
  const save = () => {
    if (lost > 0 && !confirmLoss) { setConfirmLoss(true); return }
    const mon = mondayOf(start)
    const same = mon === sheet.startDate && weeks === sheet.weeks && title.trim() === sheet.title && desc === (sheet.description ?? '')
    const msg = same ? `바뀐 것이 없습니다 — ${start} 은 지금과 같은 주(${mon} 월요일)입니다.`
      : mon !== start ? `저장했습니다. 시작은 그 주 월요일 ${mon} 로 맞췄습니다.` : '설정을 저장했습니다.'
    run(() => mmApi.update(sheet.id, { title: title.trim(), description: desc, startDate: start, weeks }), msg)
  }
  return (
    <Modal title="표 설정" onClose={onClose}
           footer={<>
             <button type="button" className="btn btn--danger" disabled={busy} style={{ marginRight: 'auto' }} onClick={() => (confirm ? run(async () => { await mmApi.remove(sheet.id); navigate('/mm') }, '표를 지웠습니다.') : setConfirm(true))}>{confirm ? '정말 표 삭제 (되돌릴 수 없음)' : '표 삭제'}</button>
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className={`btn ${confirmLoss ? 'btn--danger' : 'btn--primary'}`} disabled={busy || !title.trim() || !start} onClick={save}>{confirmLoss ? `${lost}칸 지우고 저장` : '저장'}</button>
           </>}>
      <div className="field"><label htmlFor="mm-t">제목</label><input id="mm-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} /></div>
      <div className="field"><label htmlFor="mm-d">설명</label><input id="mm-d" className="input" value={desc} onChange={(e) => setDesc(e.target.value)} maxLength={1000} /></div>
      <div className="row" style={{ gap: 8 }}>
        <div className="field" style={{ flex: 1 }}><label htmlFor="mm-s">시작 주 (월요일로 맞춤)</label><input id="mm-s" type="date" className="input" value={start} onChange={(e) => { setStart(e.target.value); setConfirmLoss(false) }} /></div>
        <div className="field" style={{ width: 120 }}><label htmlFor="mm-w">주 수</label><input id="mm-w" type="number" min={1} max={104} className="input" value={weeks} onChange={(e) => { setWeeks(Math.max(1, Math.min(104, Number(e.target.value) || 1))); setConfirmLoss(false) }} /></div>
      </div>
      <p className="small">기간: <strong>{periodText(start, weeks)}</strong>{start && mondayOf(start) !== start && <span className="muted"> — 고른 {start} 은 그 주 월요일로 맞춥니다</span>}</p>
      {lost > 0
        ? <div className="alert alert--warn">이 기간으로 바꾸면 기간 밖이 되는 <strong>{lost}칸</strong>의 값이 지워집니다.</div>
        : <p className="small muted">시작을 바꿔도 입력한 값은 날짜 기준으로 그대로 남습니다.</p>}
    </Modal>
  )
}

function flatCategories(nodes: CategoryNode[], out: { id: number; label: string }[] = [], prefix = ''): { id: number; label: string }[] {
  for (const n of nodes) { out.push({ id: n.id, label: prefix + n.name }); flatCategories(n.children, out, prefix + n.name + ' › ') }
  return out
}
const esc = (t: string) => t.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

/** 이 표를 담은 글 만들기. 본문의 `/mm/번호` 링크 줄이 글에서 살아 있는 표로 보인다. */
function PublishDialog({ sheet, onClose }: { sheet: MmSheet; onClose: () => void }) {
  const navigate = useNavigate()
  const toast = useToast()
  const [cats, setCats] = useState<{ id: number; label: string }[]>([])
  const [cat, setCat] = useState<number | ''>('')
  const [title, setTitle] = useState(sheet.title)
  const [busy, setBusy] = useState(false)
  useEffect(() => { api.categories.tree().then((t) => setCats(flatCategories(t))).catch(() => null) }, [])
  const publish = async () => {
    setBusy(true)
    try {
      const content = (sheet.description ? `<p>${esc(sheet.description)}</p>` : '')
        + `<p><a href="/mm/${sheet.id}">공수 표: ${esc(sheet.title)}</a></p>`
        + '<p>위 표는 이 글에서 바로 고칠 수 있습니다(편집자만). 프로젝트·인원 추가와 엑셀은 [공수 표 열기] 에서.</p>'
      const p = await api.posts.create({ title: title.trim(), content, contentFormat: 'HTML', categoryId: cat === '' ? null : cat, tags: ['공수'], status: 'PUBLISHED' })
      toast('글을 만들었습니다. 관리자는 글 화면의 [☆ 대문에 올리기] 로 대문에 띄울 수 있습니다.', 'success')
      navigate(`/posts/${p.id}`)
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }
  return (
    <Modal title="글로 게시" onClose={onClose}
           footer={<><button type="button" className="btn" onClick={onClose}>취소</button><button type="button" className="btn btn--primary" disabled={busy || !title.trim()} onClick={publish}>글 만들기</button></>}>
      <p className="small muted">이 표를 담은 글을 만듭니다. 글 안의 표는 이 표 그 자체라 어디서 고쳐도 같은 값입니다. 글은 검색·댓글·변경 이력·대문 게시가 됩니다.</p>
      <div className="field"><label htmlFor="mmp-t">글 제목</label><input id="mmp-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={200} autoFocus /></div>
      <div className="field"><label htmlFor="mmp-c">분류</label>
        <select id="mmp-c" className="select" value={cat} onChange={(e) => setCat(e.target.value ? Number(e.target.value) : '')}>
          <option value="">(분류 없음)</option>
          {cats.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
        </select>
      </div>
    </Modal>
  )
}
