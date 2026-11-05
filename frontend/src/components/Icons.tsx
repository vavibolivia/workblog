import type { SVGProps } from 'react'

/**
 * 공용 아이콘(선 아이콘, 1.8px, currentColor). 이모지 대신 쓴다(2026-10-27 지시 "아이콘 변경").
 * 크기는 size(px), 색은 부모의 color 를 따른다.
 */
type P = SVGProps<SVGSVGElement> & { size?: number }
const base = (size: number): SVGProps<SVGSVGElement> => ({
  width: size, height: size, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.8, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true,
})

export const IcImage = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><rect x="3" y="4" width="18" height="16" rx="2.5" /><circle cx="8.5" cy="9.5" r="1.7" /><path d="m21 16-5.2-5.2a1.5 1.5 0 0 0-2.1 0L5 19.5" /></svg>
export const IcTable = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M3 10h18M3 15h18M9 4v16M15 4v16" /></svg>
export const IcExcel = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5" /><path d="m9 12 5 6m0-6-5 6" /></svg>
export const IcDocument = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z" /><path d="M14 3v5h5M8.5 13h7M8.5 17h5" /></svg>
export const IcDiagram = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><rect x="3" y="3" width="7" height="6" rx="1.5" /><rect x="14" y="3" width="7" height="6" rx="1.5" /><rect x="8.5" y="15" width="7" height="6" rx="1.5" /><path d="M6.5 9v3h11V9M12 12v3" /></svg>
export const IcPaperclip = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="m20.5 11.5-8.3 8.3a5 5 0 0 1-7.1-7.1l9-9a3.3 3.3 0 0 1 4.7 4.7l-9 9a1.7 1.7 0 0 1-2.4-2.4l8.3-8.3" /></svg>
export const IcLink = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1.2 1.2" /><path d="M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1.2-1.2" /></svg>
export const IcRule = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 12h16" /><path d="M7 7h10M9 17h6" opacity=".45" /></svg>
export const IcUndo = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M9 14 4 9l5-5" /><path d="M4 9h10a6 6 0 0 1 0 12h-3" /></svg>
export const IcRedo = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="m15 14 5-5-5-5" /><path d="M20 9H10a6 6 0 0 0 0 12h3" /></svg>
export const IcEraser = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="m7 21 11.5-11.5a2 2 0 0 0 0-2.8l-3.2-3.2a2 2 0 0 0-2.8 0L3 13l6 6" /><path d="M5.5 14.5 12 21M11 21h9" /></svg>
/* 에디터 문단 서식(정렬·들여쓰기·목록·인용·코드) — 2026-11-03 글꼴 기호(≡ ☰ ▤ ⇤ ⇥ •≡ 1.≡ ❝) 대체 */
export const IcAlignLeft = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 6h16M4 10h10M4 14h16M4 18h10" /></svg>
export const IcAlignCenter = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 6h16M7 10h10M4 14h16M7 18h10" /></svg>
export const IcAlignRight = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 6h16M10 10h10M4 14h16M10 18h10" /></svg>
export const IcAlignJustify = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 6h16M4 10h16M4 14h16M4 18h16" /></svg>
export const IcOutdent = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 5h16M11 10h9M11 14h9M4 19h16" /><path d="M7.5 9 4.5 12l3 3" /></svg>
export const IcIndent = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 5h16M11 10h9M11 14h9M4 19h16" /><path d="m4.5 9 3 3-3 3" /></svg>
export const IcListBullet = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M10 6h10M10 12h10M10 18h10" /><circle cx="5" cy="6" r="1.2" fill="currentColor" /><circle cx="5" cy="12" r="1.2" fill="currentColor" /><circle cx="5" cy="18" r="1.2" fill="currentColor" /></svg>
export const IcViewList = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><rect x="3" y="4" width="18" height="6" rx="1.5" /><rect x="3" y="14" width="18" height="6" rx="1.5" /></svg>
export const IcViewAlbum = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><rect x="3" y="3" width="7.5" height="7.5" rx="1.5" /><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.5" /><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.5" /><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.5" /></svg>
/** 정렬 기준: 날짜(시계) · 제목(A/Z 글자) */
export const IcSortLatest = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></svg>
export const IcSortTitle = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 11 7.5 3 11 11M5.3 8h4.4" /><path d="M13 13.5h6.5L13 21h6.5" /></svg>
/** 정렬 방향: 오름(짧은 줄 → 긴 줄, 위 화살) · 내림(긴 줄 → 짧은 줄, 아래 화살) */
export const IcSortAsc = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M11 6h4M11 12h7M11 18h10" /><path d="M5 19V5M2.5 7.5 5 5l2.5 2.5" /></svg>
export const IcSortDesc = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M11 6h10M11 12h7M11 18h4" /><path d="M5 5v14M2.5 16.5 5 19l2.5-2.5" /></svg>
export const IcListOrdered = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M11 6h9M11 12h9M11 18h9" /><path d="M4 4.5h1.5V9M4 9h3M4 14.2a1.6 1.6 0 0 1 3 .4c0 1.2-3 2.2-3 4.4h3" strokeWidth={1.5} /></svg>
export const IcQuote = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M10 7H5v5h5v1a4 4 0 0 1-4 4M20 7h-5v5h5v1a4 4 0 0 1-4 4" /></svg>
export const IcCode = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="m8 8-4 4 4 4M16 8l4 4-4 4M13.5 5.5l-3 13" /></svg>
export const IcBell = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M6 16.5V11a6 6 0 0 1 12 0v5.5l1.5 1.5h-15z" /><path d="M10 20.5a2.2 2.2 0 0 0 4 0" /></svg>
export const IcEye = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M2.5 12S6 5.5 12 5.5 21.5 12 21.5 12 18 18.5 12 18.5 2.5 12 2.5 12Z" /><circle cx="12" cy="12" r="2.8" /></svg>

/** 브랜드 마크: 펼친 노트(지식)와 펜촉을 합친 'W'. 네이비 바탕 + 하늘색 점. */
export function BrandMark({ size = 32 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden>
      <rect width="32" height="32" rx="8" fill="#0B2A5B" />
      <path d="M7.5 9.5 11 22l5-9 5 9 3.5-12.5" fill="none" stroke="#fff" strokeWidth="2.6" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="24.5" cy="8.3" r="2" fill="#2F9BE3" />
    </svg>
  )
}
export const IcNote = ({ size = 15, ...p }: P) => <svg {...base(size)} {...p}><path d="M5 4h10l4 4v12H5z" /><path d="M15 4v4h4M8.5 12h7M8.5 16h5" /></svg>
export const IcChart = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M4 20h16" /><rect x="6" y="11" width="3" height="7" rx=".8" /><rect x="11" y="6" width="3" height="12" rx=".8" /><rect x="16" y="9" width="3" height="9" rx=".8" /></svg>
