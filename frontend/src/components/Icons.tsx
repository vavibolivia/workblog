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
export const IcDownload = ({ size = 16, ...p }: P) => <svg {...base(size)} {...p}><path d="M12 4v11m0 0 4-4m-4 4-4-4" /><path d="M4 17v2a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-2" /></svg>
export const IcHeart = ({ size = 16, filled = false, ...p }: P & { filled?: boolean }) => <svg {...base(size)} fill={filled ? 'currentColor' : 'none'} {...p}><path d="M12 20.5s-7.5-4.6-7.5-10A4.2 4.2 0 0 1 12 8.2a4.2 4.2 0 0 1 7.5 2.3c0 5.4-7.5 10-7.5 10Z" /></svg>

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
