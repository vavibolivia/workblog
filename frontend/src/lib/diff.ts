/** 줄 단위 LCS diff. 리비전 비교 화면용(수천 줄 이하 전제). */
export type DiffLine = { kind: 'ctx' | 'add' | 'del'; text: string }

export function diffLines(a: string, b: string): DiffLine[] {
  const A = a.split('\n'), B = b.split('\n')
  const n = A.length, m = B.length
  if (n * m > 4_000_000) {
    return [...A.map((t) => ({ kind: 'del' as const, text: t })), ...B.map((t) => ({ kind: 'add' as const, text: t }))]
  }
  const dp: Uint32Array[] = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = A[i] === B[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const out: DiffLine[] = []
  let i = 0, j = 0
  while (i < n && j < m) {
    if (A[i] === B[j]) { out.push({ kind: 'ctx', text: A[i] }); i++; j++ }
    else if (dp[i + 1][j] >= dp[i][j + 1]) { out.push({ kind: 'del', text: A[i] }); i++ }
    else { out.push({ kind: 'add', text: B[j] }); j++ }
  }
  while (i < n) out.push({ kind: 'del', text: A[i++] })
  while (j < m) out.push({ kind: 'add', text: B[j++] })
  return out
}

/** 변경 주변 context 줄만 남기고 나머지는 접는다. */
export function collapse(lines: DiffLine[], ctx = 3): (DiffLine | { kind: 'gap'; count: number })[] {
  const keep = new Array(lines.length).fill(false)
  lines.forEach((l, i) => { if (l.kind !== 'ctx') for (let k = Math.max(0, i - ctx); k <= Math.min(lines.length - 1, i + ctx); k++) keep[k] = true })
  const out: (DiffLine | { kind: 'gap'; count: number })[] = []
  let gap = 0
  lines.forEach((l, i) => {
    if (keep[i]) { if (gap) { out.push({ kind: 'gap', count: gap }); gap = 0 } out.push(l) }
    else gap++
  })
  if (gap) out.push({ kind: 'gap', count: gap })
  return out
}
