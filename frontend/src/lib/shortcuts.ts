import { useEffect, useRef } from 'react'

/**
 * 키보드 단축키(X5).
 * - 전역: `/` 검색창 포커스, `g h` 홈, `g n` 새 글, `g s` 통계, `?` 안내.
 *   입력 요소·contenteditable 안에서는 무시한다(에디터 안에서 `/` 를 치면 글자가 들어가야 한다).
 * - 에디터: Ctrl+S 임시 저장, Ctrl+Enter 발행은 RichEditor 가 ProseMirror handleKeyDown 에서 처리하고,
 *   제목 칸 등 에디터 밖에서는 EditorPage 가 `useEditorShortcuts` 로 받는다.
 */

export interface ShortcutItem { keys: string; label: string }

export const GLOBAL_SHORTCUTS: ShortcutItem[] = [
  { keys: '/', label: '검색창으로' },
  { keys: 'g h', label: '홈으로' },
  { keys: 'g n', label: '새 글 쓰기' },
  { keys: 'g s', label: '통계로' },
  { keys: '?', label: '이 안내 열기/닫기' },
  { keys: 'Esc', label: '메뉴·대화상자 닫기' },
]

export const EDITOR_SHORTCUTS: ShortcutItem[] = [
  { keys: 'Ctrl+S', label: '임시 저장' },
  { keys: 'Ctrl+Enter', label: '발행' },
  { keys: 'Ctrl+K', label: '링크 넣기/고치기' },
  { keys: 'Ctrl+B / I / U', label: '굵게 / 기울임 / 밑줄' },
  { keys: 'Tab / Shift+Tab', label: '들여쓰기 / 내어쓰기' },
  { keys: 'Ctrl+Z / Y', label: '되돌리기 / 다시 실행' },
]

/** 글자를 받는 요소 안에 있으면 true — 전역 단축키를 건너뛴다. */
export function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return true
  if (el.isContentEditable) return true
  return !!el.closest('[contenteditable="true"], .ProseMirror')
}

export interface GlobalShortcutHandlers {
  focusSearch: () => void
  goHome: () => void
  goWrite: () => void
  goStats?: () => void
  toggleHelp: () => void
}

/** 전역 단축키 설치. Layout 에서 한 번만 쓴다. */
export function useGlobalShortcuts(handlers: GlobalShortcutHandlers) {
  // 핸들러는 렌더마다 새 함수라 ref 로 받아 리스너는 한 번만 건다('g' 연속 키 상태가 렌더에 끊기지 않게).
  const ref = useRef(handlers)
  ref.current = handlers
  useEffect(() => {
    let pendingG = 0 // 'g' 를 누른 시각(연속 키 1.2초 안에 다음 키)
    const onKey = (e: KeyboardEvent) => {
      const h = ref.current
      if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return
      if (isTypingTarget(e.target)) return
      // 대화상자가 열려 있으면 안내(?)만 허용
      const modalOpen = !!document.querySelector('.modal-backdrop')
      const now = Date.now()
      if (pendingG && now - pendingG < 1200 && !modalOpen) {
        pendingG = 0
        if (e.key === 'h') { e.preventDefault(); h.goHome(); return }
        if (e.key === 'n') { e.preventDefault(); h.goWrite(); return }
        if (e.key === 's' && h.goStats) { e.preventDefault(); h.goStats(); return }
      }
      pendingG = 0
      if (e.key === '?') { e.preventDefault(); h.toggleHelp(); return }
      if (modalOpen) return
      if (e.key === '/') { e.preventDefault(); h.focusSearch(); return }
      if (e.key === 'g') { pendingG = now; return }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])
}

/**
 * 에디터 화면 단축키: 본문(ProseMirror) 밖(제목·요약·태그 칸)에서도 Ctrl+S / Ctrl+Enter 가 통한다.
 * 본문 안은 RichEditor 의 handleKeyDown 이 먼저 처리하고 preventDefault 하므로(Ctrl+Enter 가 줄바꿈으로 먹히지 않게) 여기서는 건너뛴다.
 */
export function useEditorShortcuts(onSaveDraft: () => void, onPublish: () => void) {
  const ref = useRef({ onSaveDraft, onPublish })
  ref.current = { onSaveDraft, onPublish }
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.defaultPrevented || !(e.ctrlKey || e.metaKey) || e.altKey || e.shiftKey) return
      if (e.key.toLowerCase() === 's') { e.preventDefault(); ref.current.onSaveDraft() }
      else if (e.key === 'Enter') { e.preventDefault(); ref.current.onPublish() }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])
}
