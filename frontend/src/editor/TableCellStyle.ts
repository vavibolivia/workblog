import { TableCell, TableHeader } from '@tiptap/extension-table'

/**
 * 표 셀 배경색·정렬(X2). 속성은 style 로 렌더한다(서버 HtmlSanitizer 가 background-color·text-align 을 허용).
 * 엑셀·HTML 가져오기로 들어온 셀의 style 도 그대로 읽힌다.
 */
const cellAttributes = {
  backgroundColor: {
    default: null as string | null,
    parseHTML: (el: HTMLElement) => el.style.backgroundColor || null,
    renderHTML: (attrs: Record<string, unknown>) => attrs.backgroundColor ? { style: `background-color:${attrs.backgroundColor as string}` } : {},
  },
  cellAlign: {
    default: null as string | null,
    parseHTML: (el: HTMLElement) => { const v = el.style.textAlign; return v === 'left' || v === 'center' || v === 'right' ? v : null },
    renderHTML: (attrs: Record<string, unknown>) => attrs.cellAlign ? { style: `text-align:${attrs.cellAlign as string}` } : {},
  },
}

export const StyledTableCell = TableCell.extend({
  addAttributes() { return { ...this.parent?.(), ...cellAttributes } },
})

export const StyledTableHeader = TableHeader.extend({
  addAttributes() { return { ...this.parent?.(), ...cellAttributes } },
})

/** 셀 배경 팔레트: 연한 색 8개(글자가 잘 읽히는 밝기). null = 없음. */
export const CELL_COLORS: [string, string][] = [
  ['회색', '#f1f3f6'], ['하늘', '#e3f1fc'], ['파랑', '#dbe7f7'], ['청록', '#dcf3ee'],
  ['초록', '#e3f5e6'], ['노랑', '#fff6cc'], ['주황', '#fde9d4'], ['빨강', '#fbe1e4'],
]
