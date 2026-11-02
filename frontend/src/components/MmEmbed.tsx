import { Link } from 'react-router-dom'
import { useMmSheet } from '../lib/useMmSheet'
import ManMonthBoard from './ManMonthBoard'

/** 글 본문 안의 공수 표(본문에 `/mm/번호` 링크만 있는 줄). readOnly = 대문·이력 등 보기만 하는 곳. */
export default function MmEmbed({ id, readOnly }: { id: number; readOnly?: boolean }) {
  const state = useMmSheet(id)
  if (state.err) return <div className="alert alert--error">공수 표 #{id}: {state.err}</div>
  if (!state.sheet) return <div className="spinner" aria-label="공수 표 불러오는 중" />
  return (
    <div className="mm-embed">
      <div className="mm-embed__head">
        <strong>{state.sheet.title}</strong>
        <Link to={`/mm/${id}`} className="btn btn--sm">공수 표 열기 · 프로젝트·인원 관리</Link>
      </div>
      <ManMonthBoard state={state} compact={readOnly} />
    </div>
  )
}
