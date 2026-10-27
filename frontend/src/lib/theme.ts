import { useEffect, useState } from 'react'

/**
 * 다크 모드(X4). 설정은 localStorage `workblog.theme` 에 light|dark|system 으로 두고,
 * 실제 적용은 `<html data-theme="light|dark">` 로 한다(CSS 는 `:root[data-theme="dark"]` 만 본다).
 * 첫 그리기 전 적용은 index.html 의 인라인 스크립트가 같은 키·같은 규칙으로 먼저 해 둔다.
 */
export type ThemePref = 'light' | 'dark' | 'system'
export type ResolvedTheme = 'light' | 'dark'

const KEY = 'workblog.theme'
const listeners = new Set<() => void>()

export function getThemePref(): ThemePref {
  try {
    const v = localStorage.getItem(KEY)
    return v === 'light' || v === 'dark' ? v : 'system'
  } catch { return 'system' }
}

function systemTheme(): ResolvedTheme {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'
}

export function resolveTheme(pref: ThemePref = getThemePref()): ResolvedTheme {
  return pref === 'system' ? systemTheme() : pref
}

export function applyTheme(pref: ThemePref = getThemePref()) {
  document.documentElement.setAttribute('data-theme', resolveTheme(pref))
  listeners.forEach((fn) => fn())
}

export function setThemePref(pref: ThemePref) {
  try { if (pref === 'system') localStorage.removeItem(KEY); else localStorage.setItem(KEY, pref) } catch { /* ignore */ }
  applyTheme(pref)
}

/** 시스템 설정을 따를 때 OS 테마가 바뀌면 같이 바뀐다. 앱에서 한 번만 호출. */
export function watchSystemTheme(): () => void {
  const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
  if (!mq) return () => {}
  const onChange = () => { if (getThemePref() === 'system') applyTheme('system') }
  mq.addEventListener('change', onChange)
  return () => mq.removeEventListener('change', onChange)
}

/** 메뉴 표시용: 현재 설정과 실제 적용된 테마. */
export function useTheme(): { pref: ThemePref; resolved: ResolvedTheme; set: (p: ThemePref) => void } {
  const [pref, setPref] = useState<ThemePref>(getThemePref)
  const [resolved, setResolved] = useState<ResolvedTheme>(() => resolveTheme())
  useEffect(() => {
    const sync = () => { setPref(getThemePref()); setResolved(resolveTheme()) }
    listeners.add(sync)
    const stop = watchSystemTheme()
    return () => { listeners.delete(sync); stop() }
  }, [])
  return { pref, resolved, set: setThemePref }
}
