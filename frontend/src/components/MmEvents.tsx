import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { errorMessage } from '../lib/api'
import { useAuth } from '../lib/auth'
import { EVENT_KINDS, MM_EVENTS_CHANGED, eventPeriod, mmEventApi, notifyEventsChanged, type MmEvent, type MmEventKind, type MmEventRequest } from '../lib/manMonthApi'
import { useToast } from '../lib/toast'
import Modal from './Modal'

const KIND_ORDER: MmEventKind[] = ['RELEASE', 'MILESTONE', 'REVIEW', 'MEETING', 'HOLIDAY', 'LEAVE', 'ETC']
const today = () => { const d = new Date(); return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}` }

/** 종류 표시(색 점 + 이름) */
export function KindBadge({ kind }: { kind: MmEventKind }) {
  const k = EVENT_KINDS[kind]
  return <span className="ev-kind" style={{ ['--ev' as string]: k.color }}><i />{k.label}</span>
}

/** 이벤트 추가·고치기. event 가 없으면 새로(initialDate·related 로 미리 채움). 고칠 수 없는 사람에게는 보기만. */
export function EventDialog({ event, initialDate, initialRelated, canEdit, onClose }:
  { event?: MmEvent; initialDate?: string; initialRelated?: string; canEdit: boolean; onClose: () => void }) {
  const toast = useToast()
  const [f, setF] = useState<MmEventRequest>(() => event
    ? { startDate: event.startDate, endDate: event.endDate, kind: event.kind, title: event.title, related: event.related ?? '', note: event.note ?? '' }
    : { startDate: initialDate ?? today(), endDate: null, kind: 'RELEASE', title: '', related: initialRelated ?? '', note: '' })
  const [multi, setMulti] = useState(!!event?.endDate)
  const [busy, setBusy] = useState(false)
  const [confirmDel, setConfirmDel] = useState(false)
  const set = <K extends keyof MmEventRequest>(k: K, v: MmEventRequest[K]) => setF((x) => ({ ...x, [k]: v }))
  const bad = !f.title.trim() ? '제목을 넣으세요' : !f.startDate ? '날짜를 고르세요' : multi && f.endDate && f.endDate < f.startDate ? '끝 날짜가 시작보다 빠릅니다' : null

  const save = async () => {
    setBusy(true)
    try {
      const body = { ...f, title: f.title.trim(), endDate: multi && f.endDate ? f.endDate : null }
      if (event) await mmEventApi.update(event.id, body); else await mmEventApi.create(body)
      notifyEventsChanged()
      toast(event ? '이벤트를 고쳤습니다.' : '이벤트를 넣었습니다. 기간이 걸친 모든 공수 표에 보입니다.', 'success')
      onClose()
    } catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }
  const remove = async () => {
    if (!event) return
    setBusy(true)
    try { await mmEventApi.remove(event.id); notifyEventsChanged(); toast('이벤트를 지웠습니다.', 'success'); onClose() }
    catch (e) { toast(errorMessage(e), 'error') } finally { setBusy(false) }
  }

  if (!canEdit && event) {
    return (
      <Modal title={event.title} onClose={onClose} footer={<button type="button" className="btn btn--primary" onClick={onClose}>닫기</button>}>
        <dl className="ev-view">
          <dt>날짜</dt><dd>{eventPeriod(event)}</dd>
          <dt>종류</dt><dd><KindBadge kind={event.kind} /></dd>
          {event.related && <><dt>관련</dt><dd>{event.related}</dd></>}
          {event.note && <><dt>메모</dt><dd className="ev-note">{event.note}</dd></>}
          {event.createdBy && <><dt>등록</dt><dd className="muted">{event.createdBy}</dd></>}
        </dl>
      </Modal>
    )
  }
  return (
    <Modal title={event ? '이벤트 고치기' : '이벤트 넣기'} onClose={onClose}
           footer={<>
             {event && <button type="button" className="btn btn--danger" style={{ marginRight: 'auto' }} disabled={busy}
                               onClick={() => (confirmDel ? remove() : setConfirmDel(true))}>{confirmDel ? '정말 지우기' : '지우기'}</button>}
             <span className="small muted" style={{ marginRight: event ? 0 : 'auto' }}>{bad ?? ''}</span>
             <button type="button" className="btn" onClick={onClose}>취소</button>
             <button type="button" className="btn btn--primary" disabled={busy || !!bad} onClick={save}>{event ? '저장' : '넣기'}</button>
           </>}>
      <div className="field"><label htmlFor="ev-title">제목</label>
        <input id="ev-title" className="input" value={f.title} onChange={(e) => set('title', e.target.value)} maxLength={200} autoFocus
               placeholder="예) AirDeck 2단계 릴리스" onKeyDown={(e) => { if (e.key === 'Enter' && !bad) save() }} />
      </div>
      <div className="field"><span className="field__label">종류</span>
        <div className="ev-kinds" role="radiogroup" aria-label="종류">
          {KIND_ORDER.map((k) => (
            <button key={k} type="button" role="radio" aria-checked={f.kind === k} className={f.kind === k ? 'is-on' : undefined}
                    style={{ ['--ev' as string]: EVENT_KINDS[k].color }} onClick={() => set('kind', k)}><i />{EVENT_KINDS[k].label}</button>
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
      <div className="field"><label htmlFor="ev-rel">관련 <span className="muted small">(선택 — 예: AirDeck. 공수 표에서 이름에 이 글자가 든 프로젝트 줄에 ◆ 로 표시)</span></label>
        <input id="ev-rel" className="input" value={f.related ?? ''} onChange={(e) => set('related', e.target.value)} maxLength={100} placeholder="AirDeck" />
      </div>
      <div className="field"><label htmlFor="ev-note">메모 <span className="muted small">(선택)</span></label>
        <textarea id="ev-note" className="input" rows={3} value={f.note ?? ''} onChange={(e) => set('note', e.target.value)} maxLength={2000} />
      </div>
    </Modal>
  )
}

/** 이벤트 표: 달마다 묶어서, 지난 것은 흐리게, 오늘 이후 첫 이벤트에 '다음' 표시 */
export function EventsTable({ events, canEdit, empty, onOpen }:
  { events: MmEvent[]; canEdit: boolean; empty?: string; onOpen: (e: MmEvent) => void }) {
  const t = today()
  const nextId = events.find((e) => (e.endDate ?? e.startDate) >= t)?.id
  const months = useMemo(() => {
    const m = new Map<string, MmEvent[]>()
    for (const e of events) { const k = e.startDate.slice(0, 7); m.set(k, [...(m.get(k) ?? []), e]) }
    return [...m.entries()]
  }, [events])
  if (!events.length) return <p className="muted small ev-empty">{empty ?? '이벤트가 없습니다.'}</p>
  return (
    <table className="ev-table">
      <thead><tr><th style={{ width: 150 }}>날짜</th><th style={{ width: 120 }}>종류</th><th>제목</th><th style={{ width: 130 }}>관련</th></tr></thead>
      {months.map(([m, list]) => (
        <tbody key={m}>
          <tr className="ev-month"><th colSpan={4}>{m.replace('-', '년 ')}월 <span className="muted">· {list.length}건</span></th></tr>
          {list.map((e) => {
            const past = (e.endDate ?? e.startDate) < t
            const now = e.startDate <= t && (e.endDate ?? e.startDate) >= t
            return (
              <tr key={e.id} className={`ev-row${past ? ' is-past' : ''}${now ? ' is-now' : ''}`} style={{ ['--ev' as string]: EVENT_KINDS[e.kind].color }}
                  onClick={() => onOpen(e)} tabIndex={0} onKeyDown={(k) => { if (k.key === 'Enter') onOpen(e) }}
                  title={canEdit ? '눌러서 고치기' : '눌러서 자세히'}>
                <td className="ev-date">{eventPeriod(e)}{e.id === nextId && !now && <span className="ev-next">다음</span>}{now && <span className="ev-next ev-next--now">진행 중</span>}</td>
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
          <Link to="/mm/events" className="btn btn--sm">전체 이벤트 관리</Link>
        </div>
      </div>
      <EventsTable events={data.events} canEdit={canEdit} onOpen={(e) => setOpen(e)} empty="이 기간에 이벤트가 없습니다." />
      {open && <EventDialog event={open === 'new' ? undefined : open} canEdit={canEdit} onClose={() => setOpen(null)} />}
    </div>
  )
}
