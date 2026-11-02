import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage, http } from '../lib/api'
import { useAuth } from '../lib/auth'
import { EVENT_KINDS, MM_EVENTS_CHANGED, eventPeriod, mmEventApi, notifyEventsChanged, type MmEvent, type MmEventKind, type MmEventRequest } from '../lib/manMonthApi'
import { useToast } from '../lib/toast'
import Modal from './Modal'
import { relatedMatch } from './ManMonthGrid'

/** 모든 공수 표의 프로젝트 이름(가나다순, 중복 없음) */
const mmProjectNames = () => http.get<string[]>('/mm/assignments/projects').then((r) => r.data).catch(() => [] as string[])

const KIND_ORDER: MmEventKind[] = ['RELEASE', 'MILESTONE', 'REVIEW', 'MEETING', 'HOLIDAY', 'LEAVE', 'ETC']
const KIND_KEY = 'workblog.mm.eventKind'
const lastKind = (): MmEventKind => { try { const k = localStorage.getItem(KIND_KEY) as MmEventKind | null; return k && k in EVENT_KINDS ? k : 'RELEASE' } catch { return 'RELEASE' } }
const today = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/** 종류 표시(색 점 + 이름) */
export function KindBadge({ kind }: { kind: MmEventKind }) {
  const k = EVENT_KINDS[kind]
  return <span className="ev-kind" style={{ ['--ev' as string]: k.color }}><b aria-hidden>{k.sym}</b>{k.label}</span>
}

/** 이벤트 추가·고치기. event 가 없으면 새로(initialDate·related 로 미리 채움). 고칠 수 없는 사람에게는 보기만. */
export function EventDialog({ event, initialDate, initialRelated, canEdit, projectNames: given, onClose }:
  { event?: MmEvent; initialDate?: string; initialRelated?: string; canEdit: boolean; projectNames?: string[]; onClose: () => void }) {
  const toast = useToast()
  const [f, setF] = useState<MmEventRequest>(() => event
    ? { startDate: event.startDate, endDate: event.endDate, kind: event.kind, title: event.title, related: event.related ?? '', note: event.note ?? '' }
    : { startDate: initialDate ?? today(), endDate: null, kind: lastKind(), title: '', related: initialRelated ?? '', note: '' })
  const [multi, setMulti] = useState(!!event?.endDate)
  const [busy, setBusy] = useState(false)
  const [confirmDel, setConfirmDel] = useState(false)
  const [more, setMore] = useState(false)
  const set = <K extends keyof MmEventRequest>(k: K, v: MmEventRequest[K]) => { setTouched(true); setF((x) => ({ ...x, [k]: v })) }
  const bad = !f.title.trim() ? '제목을 넣으세요' : !f.startDate ? '날짜를 고르세요' : multi && !f.endDate ? '끝 날짜를 고르세요' : multi && f.endDate && f.endDate < f.startDate ? '끝 날짜가 시작보다 빠릅니다' : null
  const [touched, setTouched] = useState(!!event)
  // '관련' 고르기 목록: 넘겨받은 이름(지금 공수 표의 프로젝트)을 앞에, 그 뒤에 모든 공수 표의 프로젝트 이름
  const [allNames, setAllNames] = useState<string[]>([])
  useEffect(() => { let alive = true; mmProjectNames().then((l) => { if (alive) setAllNames(l) }); return () => { alive = false } }, [])
  const projectNames = useMemo(() => [...new Set([...(given ?? []).map((n) => n.trim()).filter(Boolean), ...allNames])], [given, allNames])

  const save = async () => {
    if (busy || bad) return
    setBusy(true)
    try {
      const body = { ...f, title: f.title.trim(), endDate: multi && f.endDate ? f.endDate : null }
      if (event) await mmEventApi.update(event.id, body); else await mmEventApi.create(body)
      try { localStorage.setItem(KIND_KEY, f.kind) } catch { /* 저장소 없음 */ }
      notifyEventsChanged()
      toast(event ? '이벤트를 고쳤습니다.' : `넣었습니다: ${body.title} — 기간이 걸친 모든 공수 표에 보입니다.`, 'success')
      // '계속 넣기': 창을 닫지 않고 제목·날짜만 비워 다음 것을 바로(공휴일 여러 개 등)
      if (!event && more) { setF((x) => ({ ...x, title: '', endDate: null, note: '' })); setTouched(false); document.getElementById('ev-title')?.focus() }
      else onClose()
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }
  const remove = async () => {
    if (!event) return
    setBusy(true)
    try { await mmEventApi.remove(event.id); notifyEventsChanged(); toast('이벤트를 삭제했습니다.', 'success'); onClose() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  if (!canEdit && event) {
    return (
      <Modal title={event.title} onClose={onClose} footer={<button type="button" className="btn btn--primary" onClick={onClose}>닫기</button>}>
        <dl className="ev-view">
          <dt>날짜</dt><dd>{eventPeriod(event)}</dd>
          <dt>종류</dt><dd><KindBadge kind={event.kind} /></dd>
          {event.related && <><dt>관련</dt><dd>{event.related}</dd></>}
          {event.note && <><dt>설명</dt><dd className="ev-note">{event.note}</dd></>}
          {event.createdBy && <><dt>등록</dt><dd className="muted">{event.createdBy}</dd></>}
        </dl>
        <p className="small muted">고치거나 삭제는 등록한 사람과 관리자만 할 수 있습니다.</p>
      </Modal>
    )
  }
  return (
    <Modal title={event ? '이벤트 고치기' : '이벤트 넣기'} onClose={onClose} dirty={touched && !event ? !!f.title.trim() : touched} busy={busy}
           footer={<>
             {event && <button type="button" className="btn btn--danger" style={{ marginRight: 'auto' }} disabled={busy}
                               onClick={() => (confirmDel ? remove() : setConfirmDel(true))}>{confirmDel ? '정말 삭제' : '삭제'}</button>}
             <span className="small muted" style={{ marginRight: event ? 0 : 'auto' }}>{touched ? bad ?? '' : ''}</span>
             <button type="button" className="btn" onClick={onClose}>취소</button>
             {!event && <label className="check small"><input type="checkbox" checked={more} onChange={(e) => setMore(e.target.checked)} /> 계속 넣기</label>}
             <button type="button" className="btn btn--primary" disabled={busy || !!bad} onClick={save}>{event ? '저장' : '넣기'}</button>
           </>}>
      <p className="small muted ev-shared">공용 이벤트 — 기간이 걸친 <strong>모든 공수 표</strong>와 글 속 이벤트 표에 보입니다. 고치기·삭제는 등록한 사람과 관리자.</p>
      <div className="field"><label htmlFor="ev-title">제목</label>
        <input id="ev-title" className="input" value={f.title} onChange={(e) => set('title', e.target.value)} maxLength={200} autoFocus
               placeholder="예) AirDeck 2단계 릴리스" onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) save() }} />
      </div>
      <div className="field"><span className="field__label">종류</span>
        <div className="ev-kinds" role="radiogroup" aria-label="종류">
          {KIND_ORDER.map((k) => (
            <button key={k} type="button" role="radio" aria-checked={f.kind === k} className={f.kind === k ? 'is-on' : undefined}
                    style={{ ['--ev' as string]: EVENT_KINDS[k].color }} onClick={() => set('kind', k)}><b aria-hidden>{EVENT_KINDS[k].sym}</b>{EVENT_KINDS[k].label}</button>
          ))}
        </div>
      </div>
      <div className="row row--wrap" style={{ gap: 10, alignItems: 'flex-end' }}>
        <div className="field" style={{ minWidth: 170 }}><label htmlFor="ev-start">{multi ? '시작' : '날짜'}</label>
          <input id="ev-start" type="date" className="input" value={f.startDate} onChange={(e) => set('startDate', e.target.value)} />
        </div>
        {multi && <div className="field" style={{ minWidth: 170 }}><label htmlFor="ev-end">끝</label>
          <input id="ev-end" type="date" className="input" value={f.endDate ?? ''} min={f.startDate} onChange={(e) => set('endDate', e.target.value)} />
        </div>}
        <label className="check" style={{ marginBottom: 12 }}><input type="checkbox" checked={multi} onChange={(e) => { setMulti(e.target.checked); if (e.target.checked && !f.endDate) set('endDate', f.startDate) }} /> 여러 날</label>
      </div>
      <div className="field"><label htmlFor="ev-rel">관련 프로젝트 <span className="muted small">(선택)</span></label>
        <input id="ev-rel" className="input" list="ev-rel-list" value={f.related ?? ''} onChange={(e) => set('related', e.target.value)} maxLength={100} placeholder="예) AirDeck"
               onKeyDown={(e) => { if (e.key === 'Enter' && !e.nativeEvent.isComposing) save() }} />
        <datalist id="ev-rel-list">{projectNames.map((n) => <option key={n} value={n} />)}</datalist>
        <span className="field__hint">이름이 같거나 이 이름 + 띄어쓰기로 시작하는 프로젝트 줄에 표시됩니다. 예) AirDeck → 'AirDeck 2단계' 에도{f.related?.trim() && (given ?? []).length ? ` · 이 표에서는: ${(given ?? []).filter((n) => relatedMatch(n, f.related ?? '')).join(', ') || '없음'}` : ''}</span>
      </div>
      <div className="field"><label htmlFor="ev-note">설명 <span className="muted small">(선택)</span></label>
        <textarea id="ev-note" className="textarea" rows={3} value={f.note ?? ''} onChange={(e) => set('note', e.target.value)} maxLength={2000} />
      </div>
    </Modal>
  )
}

/** 이벤트 표: 달마다 묶어서, 지난 것은 흐리게, 오늘 이후 첫 이벤트에 '다음' 표시 */
export function EventsTable({ events, canEdit, empty, onOpen }:
  { events: MmEvent[]; canEdit: boolean; empty?: string; onOpen: (e: MmEvent) => void }) {
  const t = today()
  // '다음' = 아직 시작하지 않은 첫 이벤트(진행 중인 것은 '진행 중' 으로 따로 표시)
  const nextId = events.find((e) => e.startDate > t)?.id
  // 여러 달에 걸친 이벤트는 걸친 달마다 보이게(시작 달이 아니면 '이어짐')
  const months = useMemo(() => {
    const m = new Map<string, { e: MmEvent; cont: boolean }[]>()
    for (const e of events) {
      const end = (e.endDate ?? e.startDate).slice(0, 7)
      let [y, mo] = e.startDate.slice(0, 7).split('-').map(Number)
      for (let k = e.startDate.slice(0, 7), first = true; k <= end; first = false) {
        m.set(k, [...(m.get(k) ?? []), { e, cont: !first }])
        mo++; if (mo > 12) { mo = 1; y++ }
        k = `${y}-${String(mo).padStart(2, '0')}`
      }
    }
    return [...m.entries()].sort((a, b) => a[0].localeCompare(b[0]))
  }, [events])
  if (!events.length) return <p className="muted small ev-empty">{empty ?? '이벤트가 없습니다.'}</p>
  return (
    <table className="ev-table">
      <thead><tr><th style={{ width: 150 }}>날짜</th><th style={{ width: 120 }}>종류</th><th>제목</th><th style={{ width: 130 }}>관련</th></tr></thead>
      {months.map(([m, list]) => (
        <tbody key={m}>
          <tr className="ev-month"><th colSpan={4}>{m.replace('-', '년 ')}월 <span className="muted">· {list.length}건</span></th></tr>
          {list.map(({ e, cont }) => {
            const past = (e.endDate ?? e.startDate) < t
            const now = e.startDate <= t && (e.endDate ?? e.startDate) >= t
            return (
              <tr key={`${e.id}${cont ? 'c' : ''}`} className={`ev-row${past ? ' is-past' : ''}${now ? ' is-now' : ''}${cont ? ' is-cont' : ''}`} style={{ ['--ev' as string]: EVENT_KINDS[e.kind].color }}
                  onClick={() => onOpen(e)} tabIndex={0} onKeyDown={(k) => { if (k.key === 'Enter') onOpen(e) }}
                  title={canEdit ? '눌러서 고치기' : '눌러서 자세히'}>
                <td className="ev-date">{cont && <span className="ev-cont">이어짐 · </span>}{eventPeriod(e)}{!cont && e.id === nextId && <span className="ev-next">다음</span>}{!cont && now && <span className="ev-next ev-next--now">진행 중</span>}</td>
                <td><KindBadge kind={e.kind} /></td>
                <td className="ev-title">{e.title}{e.note && <span className="ev-hasnote" title={e.note}> 📝</span>}</td>
                <td className="muted">{e.related ?? ''}</td>
              </tr>
            )
          })}
        </tbody>
      ))}
    </table>
  )
}

/** 이벤트 목록을 불러와 바뀔 때마다 다시 읽는 훅 */
export function useMmEvents(from?: string, to?: string) {
  const [data, setData] = useState<{ events: MmEvent[]; canEdit: boolean } | null>(null)
  const [err, setErr] = useState<string | null>(null)
  useEffect(() => {
    let alive = true
    const load = () => mmEventApi.list(from, to).then((d) => { if (alive) { setData(d); setErr(null) } }).catch((e) => { if (alive) setErr(errorMessage(e)) })
    load()
    window.addEventListener(MM_EVENTS_CHANGED, load)
    return () => { alive = false; window.removeEventListener(MM_EVENTS_CHANGED, load) }
  }, [from, to])
  return { data, err }
}

/** 글 본문 속 이벤트 표(본문에 `/mm/events` 링크만 있는 줄): 오늘 기준 한 달 전 ~ 여섯 달 뒤 */
export function MmEventsEmbed({ readOnly }: { readOnly?: boolean }) {
  const { user } = useAuth()
  const range = useMemo(() => {
    const d = new Date(); const f = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`
    return [f(new Date(d.getFullYear(), d.getMonth() - 1, d.getDate())), f(new Date(d.getFullYear(), d.getMonth() + 6, d.getDate()))]
  }, [])
  const { data, err } = useMmEvents(range[0], range[1])
  const [open, setOpen] = useState<MmEvent | 'new' | null>(null)
  const canEdit = !readOnly && !!user && !!data?.canEdit
  if (err) return <div className="alert alert--error">이벤트: {err}</div>
  if (!data) return <div className="spinner" aria-label="이벤트 불러오는 중" />
  return (
    <div className="mm-embed ev-embed">
      <div className="mm-embed__head">
        <strong>이벤트 <span className="muted small">— {range[0]} ~ {range[1]}</span></strong>
        <div className="row" style={{ gap: 6 }}>
          {canEdit && <button type="button" className="btn btn--sm btn--primary" onClick={() => setOpen('new')}>+ 이벤트</button>}
          <Link to="/mm/events" className="btn btn--sm">이벤트 관리</Link>
        </div>
      </div>
      <EventsTable events={data.events} canEdit={canEdit} onOpen={(e) => setOpen(e)} empty="이 기간에 이벤트가 없습니다." />
      {open && <EventDialog event={open === 'new' ? undefined : open} canEdit={open === 'new' ? canEdit : open.canEdit && !readOnly} onClose={() => setOpen(null)} />}
    </div>
  )
}
