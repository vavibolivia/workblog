import { useMemo } from 'react'

/**
 * 로그인 소개 그림: 점과 선으로 이어진 두뇌.
 * 글 하나하나(점)가 서로 이어져(선) 회사의 두뇌가 된다는 뜻. 색은 남색 하나와 회색뿐.
 * 점은 고정된 씨앗으로 만들어 새로고침해도 같은 그림. 처음 열릴 때 가운데서 바깥으로 선이 한 번 그려진다.
 */
type Pt = { x: number; y: number; hub?: string; edge?: boolean }

// 옆에서 본 두뇌(왼쪽이 앞). 원 여러 개를 합친 모양 안에 점을 뿌린다.
const LOBES: [number, number, number][] = [
  [128, 150, 88],  // 전두엽
  [220, 118, 94],  // 두정엽
  [300, 168, 72],  // 후두엽
  [190, 212, 66],  // 측두엽
  [118, 204, 50],  // 앞 아래
  [238, 232, 36],  // 뒤 아래
]
const SMALL: [number, number, number][] = [
  [304, 262, 38],  // 소뇌
  [262, 292, 14],  // 뇌간
  [257, 318, 12],
]
const CENTER = { x: 212, y: 178 }
// 꼬리표가 붙는 점(고정 좌표): 이 프로그램의 부품들
const HUBS: [number, number, string][] = [
  [112, 132, '글'],
  [212, 96, '엑셀 표'],
  [306, 150, '다이어그램'],
  [176, 238, '댓글'],
  [242, 184, '이력 v3'],
  [304, 262, '검색'],
]

function rng(seed: number) {
  let s = seed >>> 0
  return () => { s = (s * 1664525 + 1013904223) >>> 0; return s / 4294967296 }
}
const inside = (x: number, y: number, cs: [number, number, number][]) => cs.some(([cx, cy, r]) => (x - cx) ** 2 + (y - cy) ** 2 <= r * r)

function build() {
  const rand = rng(20261105)
  const pts: Pt[] = HUBS.map(([x, y, hub]) => ({ x, y, hub }))
  const all = [...LOBES, ...SMALL]
  // 속: 거부 표본 추출. 점끼리 너무 가깝지 않게
  let guard = 0
  while (pts.length < 205 && guard++ < 30000) {
    const x = 20 + rand() * 340, y = 20 + rand() * 320
    if (!inside(x, y, all)) continue
    if (pts.some((p) => (p.x - x) ** 2 + (p.y - y) ** 2 < 14.5 * 14.5)) continue
    pts.push({ x, y })
  }
  // 겉: 원 둘레 중 다른 원 안에 들어가지 않는 점만 — 두뇌 윤곽이 또렷해진다
  for (const [cx, cy, r] of all) {
    const n = Math.round(r / 5)
    for (let i = 0; i < n; i++) {
      const a = (i / n) * Math.PI * 2 + rand() * 0.25
      const x = cx + Math.cos(a) * r, y = cy + Math.sin(a) * r
      if (inside(x, y, all.filter((c) => !(c[0] === cx && c[1] === cy)).map(([a, b, c]) => [a, b, c - 1.5]))) continue
      if (pts.some((p) => (p.x - x) ** 2 + (p.y - y) ** 2 < 11 * 11)) continue
      pts.push({ x, y, edge: true })
    }
  }
  // 선: 점마다 가까운 점 2~3개와 잇는다(중복 제거)
  const edges: [number, number][] = []
  const seen = new Set<string>()
  pts.forEach((p, i) => {
    const near = pts.map((q, j) => ({ j, d: (p.x - q.x) ** 2 + (p.y - q.y) ** 2 })).filter((o) => o.j !== i).sort((a, b) => a.d - b.d)
    const k = p.hub ? 5 : 2 + (rand() < 0.5 ? 1 : 0)
    for (const o of near.slice(0, k)) {
      if (o.d > 44 * 44) break
      const key = i < o.j ? `${i}-${o.j}` : `${o.j}-${i}`
      if (!seen.has(key)) { seen.add(key); edges.push([i, o.j]) }
    }
  })
  // 신호가 흐르는 선: 꼬리표 점마다 가장 긴 선 하나
  const signals: [number, number][] = []
  pts.forEach((p, i) => {
    if (!p.hub) return
    const mine = edges.filter(([a, b]) => a === i || b === i).map(([a, b]) => (a === i ? [i, b] : [i, a]) as [number, number])
    mine.sort((e1, e2) => Math.hypot(pts[e2[1]].x - p.x, pts[e2[1]].y - p.y) - Math.hypot(pts[e1[1]].x - p.x, pts[e1[1]].y - p.y))
    if (mine[0]) signals.push(mine[0])
  })
  return { pts, edges, signals }
}

export default function BrainArt({ className }: { className?: string }) {
  const { pts, edges, signals } = useMemo(build, [])
  const still = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches
  const maxD = 190
  const delay = (x: number, y: number) => `${(Math.hypot(x - CENTER.x, y - CENTER.y) / maxD * 1.5).toFixed(2)}s`
  return (
    <svg className={className} viewBox="0 0 380 340" role="img" aria-label="점과 선으로 이어진 두뇌 그림: 글·엑셀 표·다이어그램·댓글·이력·검색이 서로 이어져 있습니다">
      <g className="brain__edges">
        {edges.map(([a, b], i) => {
          const p = pts[a], q = pts[b]
          const len = Math.hypot(p.x - q.x, p.y - q.y)
          const strong = p.hub || q.hub
          return <line key={i} x1={p.x} y1={p.y} x2={q.x} y2={q.y} strokeDasharray={len} strokeDashoffset={len} className={strong ? 'is-strong' : undefined} style={{ animationDelay: delay((p.x + q.x) / 2, (p.y + q.y) / 2) }} />
        })}
      </g>
      <g className="brain__nodes">
        {pts.map((p, i) => p.hub ? null : <circle key={i} cx={p.x} cy={p.y} r={p.edge ? 1.6 : 1.9} style={{ animationDelay: delay(p.x, p.y) }} />)}
      </g>
      {!still && (
        <g className="brain__signals">
          {signals.map(([a, b], i) => {
            const p = pts[a], q = pts[b]
            return (
              <circle key={i} r={2.2}>
                <animateMotion dur="2.6s" begin={`${2 + i * 0.45}s`} repeatCount="indefinite" keyPoints="0;1;1" keyTimes="0;0.55;1" calcMode="linear" path={`M${p.x} ${p.y} L${q.x} ${q.y}`} />
                <animate attributeName="opacity" values="0;1;1;0;0" keyTimes="0;0.1;0.45;0.55;1" dur="2.6s" begin={`${2 + i * 0.45}s`} repeatCount="indefinite" />
              </circle>
            )
          })}
        </g>
      )}
      <g className="brain__hubs">
        {pts.filter((p) => p.hub).map((p, i) => {
          const left = p.x < CENTER.x
          const tx = left ? p.x - 12 : p.x + 12
          return (
            <g key={i} style={{ animationDelay: delay(p.x, p.y) }}>
              <circle cx={p.x} cy={p.y} r={9} className="brain__halo" />
              <circle cx={p.x} cy={p.y} r={4} className="brain__core" />
              <text x={tx} y={p.y + 4} textAnchor={left ? 'end' : 'start'}>{p.hub}</text>
            </g>
          )
        })}
      </g>
    </svg>
  )
}
