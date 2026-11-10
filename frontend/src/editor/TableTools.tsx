import { useEffect, useLayoutEffect, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react'
import type { Editor } from '@tiptap/react'
import { TableRow } from '@tiptap/extension-table'
import { TableMap, addColumn, addRow } from '@tiptap/pm/tables'

/** 행 높이(px). style="height:..px" 로 저장한다(서버 HtmlSanitizer 가 height 를 허용). 내용이 더 길면 행은 그만큼 늘어난다. */
export const SizedTableRow = TableRow.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      height: {
        default: null as number | null,
        parseHTML: (el: HTMLElement) => { const v = parseInt(el.style.height, 10); return v > 0 ? v : null },
        renderHTML: (attrs: Record<string, unknown>) => attrs.height ? { style: `height:${attrs.height as number}px` } : {},
      },
    }
  },
})

const PICK_MAX_ROWS = 20, PICK_MAX_COLS = 12
const ROW_MIN = 24

/** 표 만들기 격자: 마우스를 올린 칸까지가 표 크기. 끝 칸에 닿으면 격자가 한 줄씩 늘어난다(최대 20행 × 12열). */
export function TableSizePicker({ onPick }: { onPick: (rows: number, cols: number) => void }) {
  const [h, setH] = useState({ r: 3, c: 3 })
  const rows = Math.min(PICK_MAX_ROWS, Math.max(6, h.r + 1)), cols = Math.min(PICK_MAX_COLS, Math.max(8, h.c + 1))
  return (
    <div className="tbl-pick">
      <div className="tbl-pick__grid" style={{ gridTemplateColumns: `repeat(${cols}, 18px)` }} aria-label="표 크기 고르기">
        {Array.from({ length: rows * cols }, (_, i) => {
          const r = Math.floor(i / cols) + 1, c = (i % cols) + 1
          return <button key={i} type="button" className={r <= h.r && c <= h.c ? 'on' : undefined} aria-label={`${r}행 ${c}열 표 넣기`}
                         onMouseEnter={() => setH({ r, c })} onFocus={() => setH({ r, c })} onClick={() => onPick(r, c)} />
        })}
      </div>
      <span className="rte__menu-label">{h.r}행 × {h.c}열 · 끝으로 가면 더 늘어납니다</span>
    </div>
  )
}

/** 커서가 들어 있는 표와 행(가장 안쪽 표 기준). */
function locate(editor: Editor) {
  const { $from } = editor.state.selection
  let row: { pos: number } | null = null
  for (let d = $from.depth; d > 0; d--) {
    const n = $from.node(d)
    if (!row && n.type.name === 'tableRow') row = { pos: $from.before(d) }
    if (n.type.name === 'table') return { table: n, pos: $from.before(d), start: $from.start(d), row }
  }
  return null
}

interface Box { barTop: number; top: number; left: number; width: number; height: number; rowBottom: number | null; rowSized: boolean }

/**
 * 표 안에 커서가 있을 때 표 둘레에 뜨는 도구(2026-11-10 지시 "행/열 단위 삽입·삭제, 크기 변경").
 * - 위 도구줄: 행·열 추가/삭제, 열 너비 같게, 행 높이 기본, 표 삭제
 * - 아래 [+] 띠 = 맨 아래에 행, 오른쪽 [+] 띠 = 맨 오른쪽에 열 (커서는 그대로)
 * - 커서가 있는 행의 아래 선을 끌면 행 높이. 열 너비는 세로 선을 끈다(표 확장 기본 기능).
 */
export default function TableTools({ editor, host }: { editor: Editor; host: RefObject<HTMLDivElement | null> }) {
  const [box, setBox] = useState<Box | null>(null)

  const measure = (): Box | null => {
    const h = host.current
    if (!h || !editor.isEditable) return null
    const loc = locate(editor)
    if (!loc) return null
    const dom = editor.view.nodeDOM(loc.pos)
    if (!(dom instanceof HTMLElement)) return null
    const tableEl = dom.tagName === 'TABLE' ? dom : dom.querySelector('table')
    if (!tableEl) return null
    const hr = h.getBoundingClientRect(), wr = dom.getBoundingClientRect(), tr = tableEl.getBoundingClientRect()
    // 표가 넓어 가로로 스크롤될 때는 보이는 범위(tableWrapper)에 맞춘다
    const left = Math.max(tr.left, wr.left), right = Math.min(tr.right, wr.right)
    const rowDom = loc.row ? editor.view.nodeDOM(loc.row.pos) : null
    const rowNode = loc.row ? editor.state.doc.nodeAt(loc.row.pos) : null
    // 도구줄 자리: 표 위가 기본. 위에 자리가 없으면(글 맨 앞의 표, 스크롤로 표 머리가 서식 도구줄 밑에 들어감) 표 아래,
    // 표가 길어 아래도 화면 밖이면 서식 도구줄 바로 밑에 붙인다.
    const bar = h.previousElementSibling instanceof HTMLElement ? h.previousElementSibling.getBoundingClientRect().bottom : hr.top
    const floor = Math.max(bar, hr.top) + 4
    const barTop = tr.top - 40 >= floor ? tr.top - 40 : tr.bottom + 26 + 30 <= window.innerHeight ? tr.bottom + 26 : floor
    return {
      barTop: Math.round(barTop - hr.top),
      top: Math.round(tr.top - hr.top), left: Math.round(left - hr.left), width: Math.round(right - left), height: Math.round(tr.height),
      rowBottom: rowDom instanceof HTMLElement ? Math.round(rowDom.getBoundingClientRect().bottom - hr.top) : null,
      rowSized: !!rowNode?.attrs.height,
    }
  }
  const sync = () => setBox((prev) => { const next = measure(); return JSON.stringify(prev) === JSON.stringify(next) ? prev : next })

  // 트랜잭션마다 부모가 다시 그리므로 그때마다 위치를 다시 잰다
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useLayoutEffect(sync)
  useEffect(() => {
    window.addEventListener('resize', sync)
    window.addEventListener('scroll', sync, true)
    const ro = host.current ? new ResizeObserver(sync) : null
    if (host.current) ro?.observe(host.current)
    return () => { window.removeEventListener('resize', sync); window.removeEventListener('scroll', sync, true); ro?.disconnect() }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editor])

  if (!box) return null
  const c = () => editor.chain().focus()

  const append = (what: 'row' | 'col') => {
    const loc = locate(editor)
    if (!loc) return
    const map = TableMap.get(loc.table)
    const rect = { map, tableStart: loc.start, table: loc.table, left: 0, top: 0, right: map.width, bottom: map.height }
    editor.view.dispatch(what === 'row' ? addRow(editor.state.tr, rect, map.height) : addColumn(editor.state.tr, rect, map.width))
  }
  const setRowHeight = (pos: number, height: number | null) => {
    const node = editor.state.doc.nodeAt(pos)
    if (!node || node.type.name !== 'tableRow' || node.attrs.height === height) return
    editor.view.dispatch(editor.state.tr.setNodeMarkup(pos, undefined, { ...node.attrs, height }))
  }
  const resetRowHeight = () => { const loc = locate(editor); if (loc?.row) setRowHeight(loc.row.pos, null) }
  const evenColumns = () => {
    const loc = locate(editor)
    if (!loc) return
    const tr = editor.state.tr
    loc.table.descendants((n, p) => {
      if (n.type.name !== 'tableCell' && n.type.name !== 'tableHeader') return true
      if (n.attrs.colwidth) tr.setNodeMarkup(loc.start + p, undefined, { ...n.attrs, colwidth: null })
      return false
    })
    if (tr.docChanged) editor.view.dispatch(tr)
  }
  const startRowDrag = (e: ReactPointerEvent) => {
    const loc = locate(editor)
    if (!loc?.row) return
    const dom = editor.view.nodeDOM(loc.row.pos)
    if (!(dom instanceof HTMLElement)) return
    e.preventDefault()
    const pos = loc.row.pos, startY = e.clientY, startH = dom.getBoundingClientRect().height
    document.body.classList.add('tbl-row-resizing')
    const move = (ev: PointerEvent) => setRowHeight(pos, Math.max(ROW_MIN, Math.round(startH + ev.clientY - startY)))
    const up = () => { document.body.classList.remove('tbl-row-resizing'); window.removeEventListener('pointermove', move); window.removeEventListener('pointerup', up) }
    window.addEventListener('pointermove', move)
    window.addEventListener('pointerup', up)
  }

  const keep = (e: { preventDefault: () => void }) => e.preventDefault() // 단추를 눌러도 표 안 커서가 유지되게
  return (
    <>
      <div className="tbl-tools" role="toolbar" aria-label="표 편집" style={{ left: box.left, top: box.barTop }} onMouseDown={keep}>
        <span className="tbl-tools__label">행</span>
        <button type="button" title="커서가 있는 행 위에 행 추가" onClick={() => c().addRowBefore().run()}>위에 추가</button>
        <button type="button" title="커서가 있는 행 아래에 행 추가" onClick={() => c().addRowAfter().run()}>아래에 추가</button>
        <button type="button" title="커서가 있는 행 삭제" onClick={() => c().deleteRow().run()}>삭제</button>
        <span className="tbl-tools__sep" />
        <span className="tbl-tools__label">열</span>
        <button type="button" title="커서가 있는 열 왼쪽에 열 추가" onClick={() => c().addColumnBefore().run()}>왼쪽에 추가</button>
        <button type="button" title="커서가 있는 열 오른쪽에 열 추가" onClick={() => c().addColumnAfter().run()}>오른쪽에 추가</button>
        <button type="button" title="커서가 있는 열 삭제" onClick={() => c().deleteColumn().run()}>삭제</button>
        <span className="tbl-tools__sep" />
        <button type="button" title="끌어서 바꾼 열 너비를 지우고 모두 같은 너비로" onClick={evenColumns}>열 너비 같게</button>
        {box.rowSized && <button type="button" title="이 행의 높이를 내용에 맞게 되돌리기" onClick={resetRowHeight}>행 높이 기본</button>}
        <span className="tbl-tools__sep" />
        <button type="button" className="danger" onClick={() => c().deleteTable().run()}>표 삭제</button>
      </div>
      {box.rowBottom !== null && (
        <div className="tbl-rowsize" title="끌어서 행 높이 바꾸기" style={{ left: box.left, top: box.rowBottom, width: box.width }} onPointerDown={startRowDrag} />
      )}
      <button type="button" className="tbl-add tbl-add--row" title="맨 아래에 행 추가" aria-label="맨 아래에 행 추가"
              style={{ left: box.left, top: box.top + box.height + 4, width: box.width }} onMouseDown={keep} onClick={() => append('row')}>+</button>
      <button type="button" className="tbl-add tbl-add--col" title="맨 오른쪽에 열 추가" aria-label="맨 오른쪽에 열 추가"
              style={{ left: box.left + box.width + 2, top: box.top, height: box.height }} onMouseDown={keep} onClick={() => append('col')}>+</button>
    </>
  )
}
