import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { mmApi, type MmSheetSummary } from '../lib/manMonthApi'
import ManMonthBoard from './ManMonthBoard'
import { useMmSheet } from '../lib/useMmSheet'

const TAB_KEY = 'workblog.mmTab'

/** 메인(대문) 상단: 관리자가 '메인 게시' 로 지정한 공수 표. 여러 개면 탭. 편집자는 여기서 바로 고친다. */
export default function ManMonthFeatured() {
  const [list, setList] = useState<MmSheetSummary[]>([])
  const [active, setActive] = useState<number | null>(null)

  useEffect(() => {
    let alive = true
    mmApi.list(true).then((l) => {
      if (!alive) return
      setList(l)
      let saved: number | null = null
      try { saved = Number(localStorage.getItem(TAB_KEY)) || null } catch { /* 저장소 없음 */ }
      setActive(l.some((s) => s.id === saved) ? saved : l[0]?.id ?? null)
    }).catch(() => null)
    return () => { alive = false }
  }, [])

  if (!list.length || active == null) return null
  const pick = (id: number) => { setActive(id); try { localStorage.setItem(TAB_KEY, String(id)) } catch { /* 무시 */ } }
  return (
    <section className="mm-featured" aria-label="공수 표">
      <div className="mm-featured__head">
        <div className="mm-tabs" role="tablist">
          {list.map((s) => (
            <button key={s.id} type="button" role="tab" aria-selected={s.id === active} className={s.id === active ? 'is-on' : undefined} onClick={() => pick(s.id)}>{s.title}</button>
          ))}
        </div>
        <Link to={`/mm/${active}`} className="btn btn--sm">전체 화면 · 관리</Link>
      </div>
      <FeaturedSheet key={active} id={active} />
    </section>
  )
}

function FeaturedSheet({ id }: { id: number }) {
  const { sheet, err, overlay, change, saving } = useMmSheet(id)
  if (err) return <div className="alert alert--error">{err}</div>
  if (!sheet) return <div className="spinner" aria-label="불러오는 중" />
  return <ManMonthBoard sheet={sheet} overlay={overlay} change={change} saving={saving} compact />
}
