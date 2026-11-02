import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { EVENT_KINDS, type MmEvent, type MmEventKind } from '../lib/manMonthApi'
import { EventDialog, EventsTable, useMmEvents } from '../components/MmEvents'

type Span = 'upcoming' | 'year' | 'all'
const SPANS: [Span, string][] = [['upcoming', '오늘부터'], ['year', '올해'], ['all', '전체']]

/** 시기별 이벤트(공용) 관리: 모든 공수 표의 '이벤트' 줄과 글 속 이벤트 표가 이 목록을 쓴다. */
export default function MmEventsPage() {
  const [span, setSpan] = useState<Span>('upcoming')
  const [kind, setKind] = useState<MmEventKind | ''>('')
  const [q, setQ] = useState('')
  const [open, setOpen] = useState<MmEvent | 'new' | null>(null)
  const range = useMemo(() => {
    const d = new Date()
    const f = (x: Date) => `${x.getFullYear()}-${String(x.getMonth() + 1).padStart(2, '0')}-${String(x.getDate()).padStart(2, '0')}`
    if (span === 'upcoming') return [f(d), undefined] as const
    if (span === 'year') return [`${d.getFullYear()}-01-01`, `${d.getFullYear()}-12-31`] as const
    return [undefined, undefined] as const
  }, [span])
  const { data, err } = useMmEvents(range[0], range[1])
  const shown = useMemo(() => {
    const t = q.trim().toLowerCase()
    return (data?.events ?? []).filter((e) => (!kind || e.kind === kind) &&
      (!t || e.title.toLowerCase().includes(t) || (e.related ?? '').toLowerCase().includes(t) || (e.note ?? '').toLowerCase().includes(t)))
  }, [data, kind, q])
  const canEdit = !!data?.canEdit

  return (
    <div className="shell">
      <div className="page-head row row--between row--wrap">
        <div>
          <p className="small muted"><Link to="/mm">업무산정</Link> ›</p>
          <h2>이벤트</h2>
          <p className="muted">릴리스·마일스톤·회의·휴가 같은 날짜별 일정. 여기서 넣은 이벤트는 기간이 걸친 <strong>모든 공수 표</strong>의 주 머리 아래 '이벤트' 줄에 보이고,
            '관련' 에 적은 이름(예: AirDeck)이 든 프로젝트 줄에는 ◆ 로 표시됩니다.</p>
        </div>
        {canEdit && <button type="button" className="btn btn--primary" onClick={() => setOpen('new')}>+ 이벤트 넣기</button>}
      </div>

      <div className="ev-filter">
        <div className="seg" role="group" aria-label="기간">
          {SPANS.map(([k, l]) => <button key={k} type="button" aria-pressed={span === k} onClick={() => setSpan(k)}>{l}</button>)}
        </div>
        <div className="ev-kindfilter" role="group" aria-label="종류">
          <button type="button" aria-pressed={kind === ''} onClick={() => setKind('')}>모든 종류</button>
          {(Object.keys(EVENT_KINDS) as MmEventKind[]).map((k) => (
            <button key={k} type="button" aria-pressed={kind === k} style={{ ['--ev' as string]: EVENT_KINDS[k].color }} onClick={() => setKind(kind === k ? '' : k)}><i />{EVENT_KINDS[k].label}</button>
          ))}
        </div>
        <input className="input ev-search" type="search" value={q} onChange={(e) => setQ(e.target.value)} placeholder="제목·관련·메모 찾기" aria-label="이벤트 찾기" />
      </div>

      {err && <div className="alert alert--error">{err}</div>}
      {!data && !err && <div className="spinner" aria-label="불러오는 중" />}
      {data && (
        <div className="card ev-card">
          <EventsTable events={shown} canEdit={canEdit} onOpen={(e) => setOpen(e)}
                       empty={q || kind ? '찾는 이벤트가 없습니다.' : canEdit ? '이벤트가 없습니다. [+ 이벤트 넣기] 로 시작하세요.' : '이벤트가 없습니다.'} />
          {!canEdit && <p className="small muted" style={{ margin: '10px 4px 0' }}>보기 전용 — 이벤트는 관리자나 공수 표의 주인·편집자가 고칠 수 있습니다.</p>}
        </div>
      )}
      {open && <EventDialog event={open === 'new' ? undefined : open} canEdit={canEdit} onClose={() => setOpen(null)} />}
    </div>
  )
}
