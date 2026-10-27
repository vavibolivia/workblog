import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage, type StatsNameCount, type StatsPostRow, type StatsResponse } from '../lib/api'

const PERIODS = [7, 30, 90, 365] as const
const STORE = 'workblog.stats.days'

/** 글 통계(X6): 숫자 타일 + 일별 새 글 SVG 막대 + TOP 표. 색면 패널 없이 흰 카드. */
export default function StatsPage() {
  const [params, setParams] = useSearchParams()
  const initial = Number(params.get('days')) || Number((() => { try { return localStorage.getItem(STORE) } catch { return null } })()) || 30
  const [days, setDays] = useState<number>(PERIODS.includes(initial as typeof PERIODS[number]) ? initial : 30)
  const [data, setData] = useState<StatsResponse | null>(null)
  const [err, setErr] = useState<string | null>(null)

  useEffect(() => {
    setData(null); setErr(null)
    api.stats.get(days).then(setData).catch((e) => setErr(errorMessage(e, '통계를 불러오지 못했습니다.')))
    try { localStorage.setItem(STORE, String(days)) } catch { /* ignore */ }
    if (params.get('days') !== String(days)) setParams({ days: String(days) }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [days])

  return (
    <div className="shell shell--wide">
      <section className="stats">
        <div className="page-head row row--between row--wrap">
          <div>
            <h2>통계</h2>
            <p>발행된 전체 공개 글 기준입니다. 임시 저장·비공개·삭제한 글은 포함하지 않습니다.</p>
          </div>
          <div className="segmented" role="radiogroup" aria-label="기간">
            {PERIODS.map((p) => (
              <button key={p} type="button" role="radio" aria-checked={days === p} className={days === p ? 'is-on' : undefined} onClick={() => setDays(p)}>
                {p === 365 ? '1년' : `${p}일`}
              </button>
            ))}
          </div>
        </div>

        {err && <div className="alert alert--error">{err}</div>}
        {!data && !err && <div className="empty"><div className="spinner" style={{ margin: '0 auto' }} /></div>}

        {data && (
          <div className="stack">
            <div className="stat-tiles">
              <Tile label="전체 글" value={data.totals.posts} />
              <Tile label={`최근 ${data.days}일 새 글`} value={data.totals.publishedInPeriod} />
              <Tile label="작성자" value={data.totals.authors} />
              <Tile label="조회 합계" value={data.totals.views} />
              <Tile label="공감 합계" value={data.totals.likes} />
              <Tile label="댓글 합계" value={data.totals.comments} />
            </div>

            <div className="card">
              <div className="card__head"><h3>일별 새 글</h3><span className="small muted">최근 {data.days}일 · 합계 {data.totals.publishedInPeriod.toLocaleString()}</span></div>
              <div className="card__body"><BarChart data={data} /></div>
            </div>

            <div className="stats-grid">
              <PostTable title="많이 읽은 글 TOP 10" rows={data.mostViewed} primary="viewCount" />
              <PostTable title="공감 많은 글 TOP 10" rows={data.mostLiked} primary="likeCount" />
            </div>
            <div className="stats-grid stats-grid--3">
              <NameTable title="카테고리별 글 (1단계)" rows={data.byCategory} />
              <NameTable title="부서별 글" rows={data.byDepartment} />
              <NameTable title="작성자 TOP 10" rows={data.topAuthors} />
            </div>
          </div>
        )}
      </section>
    </div>
  )
}

function Tile({ label, value }: { label: string; value: number }) {
  return (
    <div className="stat-tile">
      <span className="stat-tile__label">{label}</span>
      <strong className="stat-tile__value">{value.toLocaleString()}</strong>
    </div>
  )
}

/** 일별 새 글 SVG 막대 차트(라이브러리 없음). 날짜 수에 따라 가로 눈금 간격을 조절한다. */
function BarChart({ data }: { data: StatsResponse }) {
  const [hover, setHover] = useState<number | null>(null)
  const W = 920, H = 220, padL = 36, padR = 8, padT = 10, padB = 28
  const d = data.daily
  // 눈금은 정수 간격(1·2·5·10…처럼 고르게), 막대 높이 기준은 맨 위 눈금
  const dataMax = Math.max(1, ...d.map((x) => x.count))
  const step = Math.max(1, Math.ceil(dataMax / 4))
  const max = step * Math.ceil(dataMax / step)
  const innerW = W - padL - padR, innerH = H - padT - padB
  const slot = innerW / Math.max(1, d.length)
  const barW = Math.max(1.5, Math.min(26, slot * 0.72))
  const ticks = useMemo(() => Array.from({ length: max / step + 1 }, (_, i) => i * step), [max, step])
  const labelEvery = d.length <= 14 ? 1 : d.length <= 31 ? 5 : d.length <= 90 ? 15 : 60
  const fmt = (iso: string, short = true) => { const [, m, day] = iso.split('-'); return short ? `${Number(m)}/${Number(day)}` : `${Number(m)}월 ${Number(day)}일` }

  return (
    <div className="bar-chart">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="일별 새 글 수 막대 차트" onMouseLeave={() => setHover(null)}>
        {ticks.map((v) => {
          const y = padT + innerH - (v / max) * innerH
          return (
            <g key={v}>
              <line x1={padL} x2={W - padR} y1={y} y2={y} className="bar-chart__grid" />
              <text x={padL - 6} y={y + 4} textAnchor="end" className="bar-chart__tick">{v}</text>
            </g>
          )
        })}
        {d.map((x, i) => {
          const h = (x.count / max) * innerH
          const cx = padL + slot * i + slot / 2
          return (
            <g key={x.date} onMouseEnter={() => setHover(i)}>
              <rect x={padL + slot * i} y={padT} width={slot} height={innerH} fill="transparent" />
              <rect x={cx - barW / 2} y={padT + innerH - h} width={barW} height={Math.max(h, x.count > 0 ? 2 : 0)} rx={barW > 6 ? 3 : 1}
                    className={`bar-chart__bar${hover === i ? ' is-hover' : ''}`} />
              {(i % labelEvery === 0 || i === d.length - 1) && d.length - 1 - i >= (i === d.length - 1 ? 0 : labelEvery / 2) && (
                <text x={cx} y={H - 8} textAnchor="middle" className="bar-chart__tick">{fmt(x.date)}</text>
              )}
            </g>
          )
        })}
        {hover != null && d[hover] && (() => {
          const x = d[hover]; const cx = padL + slot * hover + slot / 2
          const label = `${fmt(x.date, false)} · ${x.count}건`
          const tw = label.length * 7.2 + 16
          const tx = Math.min(Math.max(cx - tw / 2, padL), W - padR - tw)
          return (
            <g className="bar-chart__tip" pointerEvents="none">
              <rect x={tx} y={padT} width={tw} height={22} rx={5} />
              <text x={tx + tw / 2} y={padT + 15} textAnchor="middle">{label}</text>
            </g>
          )
        })()}
      </svg>
    </div>
  )
}

function PostTable({ title, rows, primary }: { title: string; rows: StatsPostRow[]; primary: 'viewCount' | 'likeCount' }) {
  return (
    <div className="card">
      <div className="card__head"><h3>{title}</h3></div>
      <div className="table-wrap">
        {rows.length === 0 ? <div className="empty small">아직 글이 없습니다.</div> : (
          <table className="table stats-table">
            <thead><tr><th style={{ width: 36 }}>#</th><th>제목</th><th>작성자</th><th className="num">조회</th><th className="num">공감</th><th className="num">댓글</th></tr></thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id}>
                  <td className="muted">{i + 1}</td>
                  <td className="stats-table__title"><Link to={`/posts/${r.id}`} className="ellipsis" title={r.title}>{r.title}</Link></td>
                  <td className="small muted">{r.author}</td>
                  <td className={`num${primary === 'viewCount' ? ' is-primary' : ''}`}>{r.viewCount.toLocaleString()}</td>
                  <td className={`num${primary === 'likeCount' ? ' is-primary' : ''}`}>{r.likeCount.toLocaleString()}</td>
                  <td className="num">{r.commentCount.toLocaleString()}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}

function NameTable({ title, rows }: { title: string; rows: StatsNameCount[] }) {
  const total = rows.reduce((s, r) => s + r.count, 0)
  const max = Math.max(1, ...rows.map((r) => r.count))
  return (
    <div className="card">
      <div className="card__head"><h3>{title}</h3><span className="small muted">{total.toLocaleString()}건</span></div>
      <div className="table-wrap">
        {rows.length === 0 ? <div className="empty small">아직 글이 없습니다.</div> : (
          <table className="table stats-table">
            <tbody>
              {rows.map((r) => (
                <tr key={r.name}>
                  <td className="stats-table__title"><span className="ellipsis" title={r.name}>{r.name}</span></td>
                  <td className="stats-table__bar"><span style={{ width: `${(r.count / max) * 100}%` }} /></td>
                  <td className="num is-primary">{r.count.toLocaleString()}</td>
                  <td className="num small muted">{total ? Math.round((r.count / total) * 100) : 0}%</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
